package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.HotfixResult.Step;
import com.axone_io.ignition.git.dto.HotfixResult.StepStatus;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.Desktop;

/**
 * Progress dialog that polls the gateway for hotfix pipeline status.
 * Shows each step with a status icon, updates in real time.
 *
 * <p>Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class HotfixProgressDialog extends JDialog {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JLabel[] stepLabels;
    private final JLabel[] statusIcons;
    private final JEditorPane summaryPane;
    private final JButton closeButton;
    private final Timer pollTimer;
    private final GitScriptInterface rpc;
    private final String projectName;

    /**
     * Width the step text wraps at. The dialog's minimum width is 550px; this leaves room for
     * the status icon, the panel border and the FlowLayout gap. Bounding the label's preferred
     * width is what stops a long message from being pushed out of its row and disappearing.
     */
    private static final int STEP_TEXT_WIDTH_PX = 430;

    /** Longer messages are elided in the row; the full text stays available as a tooltip. */
    private static final int MAX_MESSAGE_CHARS = 300;

    private static final String ICON_PENDING = "\u2B1C";     // white square
    private static final String ICON_IN_PROGRESS = "\u23F3"; // hourglass
    private static final String ICON_COMPLETED = "\u2705";   // green check
    private static final String ICON_FAILED = "\u274C";      // red X
    private static final String ICON_SKIPPED = "\u23ED";     // skip forward

    public HotfixProgressDialog(Component parent, GitScriptInterface rpc, String projectName) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "\uD83D\uDE91 Hotfix in Progress", ModalityType.APPLICATION_MODAL);
        this.rpc = rpc;
        this.projectName = projectName;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setResizable(false);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        Step[] steps = Step.values();
        stepLabels = new JLabel[steps.length];
        statusIcons = new JLabel[steps.length];

        for (int i = 0; i < steps.length; i++) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            statusIcons[i] = new JLabel(ICON_PENDING);
            statusIcons[i].setFont(new Font("Dialog", Font.PLAIN, 14));
            row.add(statusIcons[i]);

            // Same HTML form as updates use, so the initial pack() already reflects the wrap
            // width and the update loop's change comparison starts from a matching value.
            stepLabels[i] = new JLabel(stepLabelHtml(steps[i].getDisplayName(), null));
            stepLabels[i].setFont(new Font("Dialog", Font.PLAIN, 12));
            row.add(stepLabels[i]);

            mainPanel.add(row);
        }

        mainPanel.add(Box.createVerticalStrut(10));

        summaryPane = new JEditorPane();
        summaryPane.setContentType("text/html");
        summaryPane.setEditable(false);
        summaryPane.setOpaque(false);
        summaryPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        summaryPane.setFont(new Font("Dialog", Font.BOLD, 12));
        summaryPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryPane.setPreferredSize(new Dimension(500, 40));
        summaryPane.setText("<html><i style='color:gray;'>Waiting for pipeline to complete...</i></html>");
        summaryPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (Exception ex) {
                    logger.error("Failed to open PR URL", ex);
                }
            }
        });
        mainPanel.add(summaryPane);
        mainPanel.add(Box.createVerticalStrut(10));

        closeButton = new JButton("Close");
        closeButton.setEnabled(false);
        closeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        closeButton.addActionListener(e -> dispose());
        mainPanel.add(closeButton);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(550, getHeight()));
        CommonUI.centerComponent(this, parent);

        // Poll every 500ms for progress updates
        pollTimer = new Timer(500, e -> pollProgress());
        pollTimer.start();
    }

    private void pollProgress() {
        try {
            HotfixResult result = rpc.getHotfixProgress(projectName);
            if (result == null) return;

            Step[] steps = Step.values();
            boolean layoutChanged = false;
            for (int i = 0; i < steps.length; i++) {
                StepStatus status = result.getStepStatus(steps[i]);
                String message = result.getStepMessage(steps[i]);
                statusIcons[i].setText(iconFor(status));

                // SKIPPED carries a reason too ("Push failed \u2014 skipping PR creation"), and it is
                // often the line that explains the whole run. It used to be dropped.
                boolean showMessage = message != null && !message.isEmpty()
                        && (status == StepStatus.COMPLETED
                            || status == StepStatus.FAILED
                            || status == StepStatus.SKIPPED);

                String html = stepLabelHtml(steps[i].getDisplayName(), showMessage ? message : null);
                if (!html.equals(stepLabels[i].getText())) {
                    stepLabels[i].setText(html);
                    stepLabels[i].setToolTipText(showMessage ? tooltipHtml(message) : null);
                    layoutChanged = true;
                }
            }

            if (layoutChanged) {
                // pack() ran at construction, before any step had a message, and the dialog is
                // non-resizable \u2014 so a row that grows to several lines would otherwise be clipped
                // out of view, which is how a failed step became a bare red X with no text.
                // Re-pack in place, keeping the dialog where the user left it.
                Point where = getLocation();
                pack();
                setLocation(where);
            }

            if (result.isPipelineComplete()) {
                pollTimer.stop();
                closeButton.setEnabled(true);
                setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

                if (result.isPipelineSuccess()) {
                    String prUrl = result.getPrUrl();
                    if (prUrl != null) {
                        summaryPane.setText("<html><b style='color:#388E3C;'>Hotfix complete.</b> " +
                            "PR created: <a href='" + prUrl + "'>" + prUrl + "</a></html>");
                    } else {
                        summaryPane.setText("<html><b style='color:#388E3C;'>Hotfix complete.</b> " +
                            "PR was not created (check logs).</html>");
                    }
                } else {
                    summaryPane.setText("<html><b style='color:#F44336;'>Hotfix completed with warnings</b> " +
                        "\u2014 check steps above.</html>");
                }

                summaryPane.revalidate();
                summaryPane.repaint();
            }
        } catch (Exception e) {
            logger.error("Error polling hotfix progress", e);
        }
    }

    private String iconFor(StepStatus status) {
        return switch (status) {
            case PENDING -> ICON_PENDING;
            case IN_PROGRESS -> ICON_IN_PROGRESS;
            case COMPLETED -> ICON_COMPLETED;
            case FAILED -> ICON_FAILED;
            case SKIPPED -> ICON_SKIPPED;
        };
    }

    /**
     * Render a step row as width-bounded HTML so long messages wrap instead of overflowing.
     *
     * <p>A plain single-line {@code JLabel} reports a preferred width as wide as its text. Inside
     * the row's {@code FlowLayout} that pushed an oversized label onto a second line, below the
     * row's already-packed height, where it was clipped — leaving a status icon with no text
     * beside it. Fixing the wrap width keeps the label inside its row.</p>
     */
    private static String stepLabelHtml(String displayName, String message) {
        StringBuilder sb = new StringBuilder("<html><body style='width:")
                .append(STEP_TEXT_WIDTH_PX)
                .append("px'>")
                .append(escapeHtml(displayName));
        if (message != null && !message.isEmpty()) {
            sb.append(" — ").append(escapeHtml(elide(message)));
        }
        return sb.append("</body></html>").toString();
    }

    private static String tooltipHtml(String message) {
        return "<html><body style='width:" + STEP_TEXT_WIDTH_PX + "px'>"
                + escapeHtml(message) + "</body></html>";
    }

    private static String elide(String message) {
        String collapsed = message.trim();
        if (collapsed.length() <= MAX_MESSAGE_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    /** Step messages carry exception text, which can contain markup-significant characters. */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>");
    }
}
