package com.axone_io.ignition.git;

import com.axone_io.ignition.git.components.SelectAllHeader;
import com.inductiveautomation.ignition.designer.gui.CommonUI;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal commit dialog used by the production-mode save gate.
 *
 * <p>{@link CommitPopup} is a non-modal {@link JFrame}, so it cannot be used to block a save
 * in progress. This dialog collects the same information (change selection + commit message)
 * but blocks its caller, letting {@code DesignerHook.notifyProjectSaveStart} abort the save
 * when the user cancels.</p>
 *
 * <p>Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class ProductionCommitDialog extends JDialog {

    private final JTextArea messageArea;
    private final JTable changesTable;
    private boolean confirmed = false;

    public ProductionCommitDialog(Component parent, Object[][] changeData, String currentBranch) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "Commit to Production Gateway", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(true);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBackground(new Color(255, 243, 205));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 193, 7), 2),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel headerLabel = new JLabel("PRODUCTION MODE — commit details required before saving");
        headerLabel.setFont(new Font("Dialog", Font.BOLD, 13));
        headerPanel.add(headerLabel);

        mainPanel.add(headerPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        JTextArea info = new JTextArea(
            "Your changes have not been saved to the gateway yet.\n" +
            "They will be saved and committed to '" + currentBranch + "' (then pushed) once you confirm.\n" +
            "Cancelling leaves your changes unsaved and still open in the Designer."
        );
        info.setEditable(false);
        info.setBackground(mainPanel.getBackground());
        info.setFont(new Font("Dialog", Font.PLAIN, 11));
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(info);
        mainPanel.add(Box.createVerticalStrut(10));

        JLabel changesLabel = new JLabel("Changes:");
        changesLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        changesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(changesLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        String[] columnNames = {"", "Resource Name", "Type", "Author"};
        DefaultTableModel model = new DefaultTableModel(changeData, columnNames) {
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }
        };
        changesTable = new JTable(model);
        changesTable.getColumn("").setPreferredWidth(20);
        changesTable.getColumn("Resource Name").setPreferredWidth(330);
        changesTable.getColumn("Type").setPreferredWidth(100);
        changesTable.getColumn("Author").setPreferredWidth(100);

        TableColumn tc = changesTable.getColumnModel().getColumn(0);
        tc.setHeaderRenderer(new SelectAllHeader(changesTable, 0));

        JScrollPane tableScroll = new JScrollPane(changesTable);
        tableScroll.setPreferredSize(new Dimension(500, 180));
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(tableScroll);
        mainPanel.add(Box.createVerticalStrut(10));

        JLabel msgLabel = new JLabel("Commit message:");
        msgLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        msgLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(msgLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        messageArea = new JTextArea(4, 40);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        JScrollPane msgScroll = new JScrollPane(messageArea);
        msgScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(msgScroll);
        mainPanel.add(Box.createVerticalStrut(15));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton cancelBtn = new JButton("Cancel Save");
        cancelBtn.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        JButton commitBtn = new JButton("Save and Commit");
        commitBtn.setBackground(new Color(56, 142, 60));
        commitBtn.setForeground(Color.WHITE);
        commitBtn.setOpaque(true);
        commitBtn.addActionListener(e -> {
            if (messageArea.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Commit message is required.",
                    "Missing Message", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (getSelectedChanges().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Select at least one change to commit.",
                    "No Changes Selected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });

        btnPanel.add(cancelBtn);
        btnPanel.add(commitBtn);
        mainPanel.add(btnPanel);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(550, getHeight()));
        CommonUI.centerComponent(this, parent);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getCommitMessage() {
        return messageArea.getText().trim();
    }

    public List<String> getSelectedChanges() {
        List<String> selected = new ArrayList<>();
        DefaultTableModel model = (DefaultTableModel) changesTable.getModel();
        for (int row = 0; row < model.getRowCount(); row++) {
            if (Boolean.TRUE.equals(model.getValueAt(row, 0))) {
                selected.add((String) model.getValueAt(row, 1));
            }
        }
        return selected;
    }
}
