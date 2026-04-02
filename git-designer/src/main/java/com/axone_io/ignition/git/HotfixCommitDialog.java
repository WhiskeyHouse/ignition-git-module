package com.axone_io.ignition.git;

import com.axone_io.ignition.git.components.SelectAllHeader;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Hotfix commit dialog for production mode. Collects hotfix description,
 * commit message, and change selection. Explains the full pipeline that
 * will execute after confirmation.
 *
 * <p>Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class HotfixCommitDialog extends JDialog {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JTextField descriptionField;
    private final JTextArea messageArea;
    private final JTable changesTable;
    private boolean confirmed = false;

    public HotfixCommitDialog(Component parent, Object[][] changeData, String productionBranch) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "\uD83D\uDE91 Production Hotfix", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(true);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        // Header
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBackground(new Color(255, 235, 238));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(244, 67, 54), 2),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel headerLabel = new JLabel("HOTFIX — Production Commit Detected");
        headerLabel.setFont(new Font("Dialog", Font.BOLD, 14));
        headerPanel.add(headerLabel);

        mainPanel.add(headerPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Pipeline explanation
        JTextArea pipelineInfo = new JTextArea(
            "You are committing on the production branch '" + productionBranch + "'.\n" +
            "This will automatically:\n" +
            "  1. Create a hotfix branch\n" +
            "  2. Commit your changes there\n" +
            "  3. Push to remote\n" +
            "  4. Create a PR \u2192 " + productionBranch + " (labeled hotfix)\n" +
            "  5. Merge into local " + productionBranch + "\n" +
            "  6. Switch you back to " + productionBranch
        );
        pipelineInfo.setEditable(false);
        pipelineInfo.setBackground(mainPanel.getBackground());
        pipelineInfo.setFont(new Font("Dialog", Font.PLAIN, 11));
        pipelineInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(pipelineInfo);
        mainPanel.add(Box.createVerticalStrut(10));

        // Hotfix description
        JLabel descLabel = new JLabel("Hotfix description (used in branch name + PR title):");
        descLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(descLabel);
        mainPanel.add(Box.createVerticalStrut(3));

        descriptionField = new JTextField();
        descriptionField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        descriptionField.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(descriptionField);
        mainPanel.add(Box.createVerticalStrut(10));

        // Changes table
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
        tableScroll.setPreferredSize(new Dimension(500, 150));
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(tableScroll);
        mainPanel.add(Box.createVerticalStrut(10));

        // Commit message
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

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> {
            confirmed = false;
            dispose();
        });

        JButton proceedBtn = new JButton("Proceed with Hotfix");
        proceedBtn.setBackground(new Color(244, 67, 54));
        proceedBtn.setForeground(Color.WHITE);
        proceedBtn.setOpaque(true);
        proceedBtn.addActionListener(e -> {
            if (descriptionField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Hotfix description is required.",
                    "Missing Description", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (messageArea.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Commit message is required.",
                    "Missing Message", JOptionPane.WARNING_MESSAGE);
                return;
            }
            confirmed = true;
            dispose();
        });

        btnPanel.add(cancelBtn);
        btnPanel.add(proceedBtn);
        mainPanel.add(btnPanel);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(550, getHeight()));
        CommonUI.centerComponent(this, parent);
    }

    public boolean isConfirmed() { return confirmed; }
    public String getHotfixDescription() { return descriptionField.getText().trim(); }
    public String getCommitMessage() { return messageArea.getText().trim(); }

    public List<String> getSelectedChanges() {
        List<String> selected = new ArrayList<>();
        DefaultTableModel model = (DefaultTableModel) changesTable.getModel();
        for (int row = 0; row < model.getRowCount(); row++) {
            if ((Boolean) model.getValueAt(row, 0)) {
                selected.add((String) model.getValueAt(row, 1));
            }
        }
        return selected;
    }
}
