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

            stepLabels[i] = new JLabel(steps[i].getDisplayName());
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
            for (int i = 0; i < steps.length; i++) {
                StepStatus status = result.getStepStatus(steps[i]);
                String message = result.getStepMessage(steps[i]);
                statusIcons[i].setText(iconFor(status));

                // Always keep the step display name visible
                String label = steps[i].getDisplayName();
                if (status == StepStatus.COMPLETED && message != null && !message.isEmpty()) {
                    label += " \u2014 " + message;
                } else if (status == StepStatus.FAILED && message != null && !message.isEmpty()) {
                    label += " \u2014 " + message;
                }
                stepLabels[i].setText(label);
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

                // Resize to fit content
                pack();
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
}
