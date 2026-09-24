package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Production mode warning dialog. Shows safety warnings and requires
 * explicit confirmation before proceeding with git operations in production mode.
 *
 * <p>Note: Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class ProductionModePopup extends JDialog {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private boolean confirmed = false;

    public ProductionModePopup(Component parent, ProductionModeConfig config, String operation) {
        this(parent, config, operation, null);
    }

    /**
     * @param divergenceNotice advisory text describing production-branch commits the remote has
     *                         not seen (typically an unmerged hotfix), or {@code null} if none.
     *                         Rendered as an advisory, not a blocker — it never gates Proceed.
     */
    public ProductionModePopup(Component parent, ProductionModeConfig config, String operation,
                               String divergenceNotice) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "⚠️ Production Mode Warning", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(false);

        // Main content panel using BoxLayout (vertical)
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        // Warning header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBackground(new Color(255, 243, 205)); // Light yellow
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 193, 7), 2),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel warningIcon = new JLabel("⚠️");
        warningIcon.setFont(new Font("Dialog", Font.BOLD, 24));
        headerPanel.add(warningIcon);
        headerPanel.add(Box.createHorizontalStrut(10));

        JLabel headerLabel = new JLabel("PRODUCTION MODE ACTIVE");
        headerLabel.setFont(new Font("Dialog", Font.BOLD, 16));
        headerPanel.add(headerLabel);

        mainPanel.add(headerPanel);
        mainPanel.add(Box.createVerticalStrut(15));

        // Operation info
        JLabel operationLabel = new JLabel("Operation: " + operation);
        operationLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        operationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(operationLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Configuration details
        JPanel configPanel = new JPanel();
        configPanel.setLayout(new BoxLayout(configPanel, BoxLayout.Y_AXIS));
        configPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        configPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Production Configuration"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        if (config.getProductionBranch() != null && !config.getProductionBranch().isEmpty()) {
            JLabel branchLabel = new JLabel("Protected Branch: " + config.getProductionBranch());
            branchLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            configPanel.add(branchLabel);
        }

        if (config.getProductionTagPattern() != null && !config.getProductionTagPattern().isEmpty()) {
            JLabel tagLabel = new JLabel("Required Tag Pattern: " + config.getProductionTagPattern());
            tagLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            configPanel.add(tagLabel);
        }

        mainPanel.add(configPanel);
        mainPanel.add(Box.createVerticalStrut(15));

        // Warning message
        if (config.hasWarnings()) {
            JTextArea warningArea = new JTextArea(config.getWarningMessage());
            warningArea.setEditable(false);
            warningArea.setLineWrap(true);
            warningArea.setWrapStyleWord(true);
            warningArea.setBackground(new Color(255, 235, 238)); // Light red
            warningArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(244, 67, 54), 1),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
            ));
            warningArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(warningArea);
            mainPanel.add(Box.createVerticalStrut(15));
        }

        // Unpushed production commits — advisory, distinct from the red hard-warning box above.
        // Amber signals "know this before you proceed", not "you cannot proceed".
        if (divergenceNotice != null && !divergenceNotice.isEmpty()) {
            JTextArea divergenceArea = new JTextArea(divergenceNotice);
            divergenceArea.setEditable(false);
            divergenceArea.setLineWrap(true);
            divergenceArea.setWrapStyleWord(true);
            divergenceArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
            divergenceArea.setBackground(new Color(255, 248, 225)); // Light amber
            divergenceArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JScrollPane divergenceScroll = new JScrollPane(divergenceArea);
            divergenceScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            divergenceScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Unpushed Production Commits"),
                BorderFactory.createLineBorder(new Color(255, 193, 7), 1)
            ));
            // Cap the height so a long hotfix backlog cannot push the checklist off screen.
            divergenceScroll.setPreferredSize(new Dimension(460, 130));
            divergenceScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

            mainPanel.add(divergenceScroll);
            mainPanel.add(Box.createVerticalStrut(15));
        }

        // Safety checklist with interactive checkboxes
        JPanel checklistPanel = new JPanel();
        checklistPanel.setLayout(new BoxLayout(checklistPanel, BoxLayout.Y_AXIS));
        checklistPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        checklistPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Safety Checklist"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));

        String[] safetyItems = {
            "I have reviewed the changes to be applied",
            "I have verified this is the correct environment",
            "I have a backup/rollback plan if needed",
            "I understand the impact of this operation"
        };

        JButton proceedButton = new JButton("Proceed with Caution");
        proceedButton.setEnabled(false); // Disabled until all checkboxes are checked

        JCheckBox[] checkboxes = new JCheckBox[safetyItems.length];
        for (int i = 0; i < safetyItems.length; i++) {
            checkboxes[i] = new JCheckBox(safetyItems[i]);
            checkboxes[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            checkboxes[i].setFont(new Font("Dialog", Font.PLAIN, 11));
            checkboxes[i].addActionListener(e -> {
                boolean allChecked = true;
                for (JCheckBox cb : checkboxes) {
                    if (!cb.isSelected()) {
                        allChecked = false;
                        break;
                    }
                }
                proceedButton.setEnabled(allChecked);
            });
            checklistPanel.add(checkboxes[i]);
            checklistPanel.add(Box.createVerticalStrut(3));
        }

        mainPanel.add(checklistPanel);
        mainPanel.add(Box.createVerticalStrut(15));

        // Confirmation message
        JLabel confirmLabel = new JLabel("Check all items above to enable proceed.");
        confirmLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        confirmLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        mainPanel.add(confirmLabel);
        mainPanel.add(Box.createVerticalStrut(15));

        // Button panel
        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.setPreferredSize(new Dimension(120, 30));
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        proceedButton.setBackground(new Color(255, 152, 0)); // Orange
        proceedButton.setForeground(Color.WHITE);
        proceedButton.setOpaque(true);
        proceedButton.setPreferredSize(new Dimension(120, 30));
        proceedButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                this,
                "This operation will be performed in PRODUCTION MODE.\n" +
                "Are you absolutely sure you want to proceed?",
                "Final Confirmation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            if (result == JOptionPane.YES_OPTION) {
                confirmed = true;
                // Only record the decision and close. This used to invoke an onProceed() callback
                // here, before dispose(), so the caller opened its next window while this
                // APPLICATION_MODAL dialog still held focus: the new window's toFront() was
                // discarded, and when the modal closed, focus fell back to the Designer frame,
                // leaving the new window stranded behind it. Callers now read isConfirmed()
                // after the constructor returns and open their next window themselves.
                dispose();
            }
        });

        btnPanel.add(cancelButton);
        btnPanel.add(proceedButton);
        mainPanel.add(btnPanel);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(500, getHeight()));

        CommonUI.centerComponent(this, parent);
        setVisible(true);
        toFront();
    }

    /**
     * Returns whether the user confirmed the operation.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

}
