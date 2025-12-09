package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.designer.gui.CommonUI;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.StyleContext;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class BranchPopup extends JFrame {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private JPanel panel;
    private JTable branchesTable;
    private JTextField searchField;
    private JLabel searchLabel;
    private JLabel branchesLabel;
    private JButton switchBtn;
    private JButton refreshBtn;
    private JButton cancelBtn;
    private JPanel warningPanel;
    private JTextArea warningTextArea;
    private JCheckBox createNewCheckBox;
    private JTextField newBranchNameField;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private BranchStatus currentStatus;

    public BranchPopup(Object[][] data, BranchStatus status, Component parent) {
        try {
            InputStream branchIconStream = getClass().getResourceAsStream("/com/axone_io/ignition/git/icons/ic_branch.svg");
            if (branchIconStream != null) {
                ImageIcon branchIcon = new ImageIcon(ImageIO.read(branchIconStream));
                setIconImage(branchIcon.getImage());
            }
        } catch (IOException e) {
            logger.trace(e.toString(), e);
        }

        this.currentStatus = status;

        setContentPane(panel);
        setTitle("Switch Branch");
        setSize(600, 600);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setVisible(true);

        branchesTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        branchesTable.getTableHeader().setReorderingAllowed(false);
        branchesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Setup search field
        setupSearchField();

        // Setup warning panel
        setupWarningPanel();

        // Set data
        setData(data);

        // Setup create new branch checkbox
        createNewCheckBox.addActionListener(e -> {
            newBranchNameField.setEnabled(createNewCheckBox.isSelected());
            if (createNewCheckBox.isSelected()) {
                newBranchNameField.requestFocus();
            }
        });
        newBranchNameField.setEnabled(false);

        // Switch button action
        switchBtn.addActionListener(e -> {
            if (createNewCheckBox.isSelected()) {
                String newBranchName = newBranchNameField.getText().trim();
                if (newBranchName.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "Please enter a branch name.",
                            "Invalid Input",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                onSwitchBranch(newBranchName, true);
            } else {
                int selectedRow = branchesTable.getSelectedRow();
                if (selectedRow == -1) {
                    JOptionPane.showMessageDialog(this,
                            "Please select a branch to switch to.",
                            "No Selection",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }

                int modelRow = branchesTable.convertRowIndexToModel(selectedRow);
                DefaultTableModel model = (DefaultTableModel) branchesTable.getModel();
                String branchName = (String) model.getValueAt(modelRow, 0);
                boolean isCurrent = "★".equals(model.getValueAt(modelRow, 1));

                if (isCurrent) {
                    JOptionPane.showMessageDialog(this,
                            "You are already on branch '" + branchName + "'.",
                            "Already On Branch",
                            JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                onSwitchBranch(branchName, false);
            }
        });

        // Refresh button action
        refreshBtn.addActionListener(e -> onRefresh());

        // Cancel button action
        cancelBtn.addActionListener(e -> this.dispose());

        pack();
        CommonUI.centerComponent(this, parent);
        toFront();
    }

    private void setupSearchField() {
        searchLabel = new JLabel("Search:");
        searchLabel.setFont(searchLabel.getFont().deriveFont(Font.BOLD));
        searchField = new JTextField(20);
        searchField.setToolTipText("Filter branches by name");

        GridLayoutManager layout = (GridLayoutManager) panel.getLayout();

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        searchPanel.add(searchLabel);
        searchPanel.add(searchField);

        panel.add(searchPanel, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    private void setupWarningPanel() {
        if (currentStatus != null && currentStatus.hasWarnings()) {
            warningPanel.setVisible(true);
            warningTextArea.setText(currentStatus.getWarningMessage());
            warningTextArea.setEditable(false);
            warningTextArea.setBackground(new Color(255, 243, 205)); // Light yellow
            warningTextArea.setForeground(new Color(102, 60, 0)); // Dark brown
            warningTextArea.setLineWrap(true);
            warningTextArea.setWrapStyleWord(true);
        } else {
            warningPanel.setVisible(false);
        }
    }

    public void setData(Object[][] data) {
        String[] columnNames = {"Branch Name", "  ", "Type", "Status"};
        DefaultTableModel model = new DefaultTableModel(data, columnNames) {
            @Override
            public Class<?> getColumnClass(int column) {
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        branchesTable.setModel(model);
        branchesTable.getColumn("Branch Name").setPreferredWidth(250);
        branchesTable.getColumn("  ").setPreferredWidth(30); // Current marker
        branchesTable.getColumn("Type").setPreferredWidth(100);
        branchesTable.getColumn("Status").setPreferredWidth(120);

        // Setup row sorter for filtering
        rowSorter = new TableRowSorter<>(model);
        branchesTable.setRowSorter(rowSorter);

        // Add filter listener
        setupFilterListener();
    }

    private void setupFilterListener() {
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterTable();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterTable();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterTable();
            }
        });
    }

    private void filterTable() {
        String searchText = searchField.getText().trim();
        if (searchText.isEmpty()) {
            rowSorter.setRowFilter(null);
        } else {
            // Filter on branch name column
            rowSorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(searchText), 0));
        }
    }

    public void updateData(Object[][] data, BranchStatus status) {
        this.currentStatus = status;
        setData(data);
        setupWarningPanel();
        panel.revalidate();
        panel.repaint();
    }

    // Override methods - to be implemented by GitActionManager
    public void onSwitchBranch(String branchName, boolean createNew) {
        // Override in GitActionManager
    }

    public void onRefresh() {
        // Override in GitActionManager
    }

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        panel = new JPanel();
        panel.setLayout(new GridLayoutManager(7, 2, new Insets(5, 5, 5, 5), -1, -1));
        panel.setPreferredSize(new Dimension(600, 600));

        // Branches label (row 0, col 0)
        branchesLabel = new JLabel();
        Font branchesLabelFont = this.$$$getFont$$$(null, Font.BOLD, -1, branchesLabel.getFont());
        if (branchesLabelFont != null) branchesLabel.setFont(branchesLabelFont);
        branchesLabel.setText("Branches:");
        panel.add(branchesLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        // Branches table scroll pane (row 1, col 0-1)
        final JScrollPane scrollPane1 = new JScrollPane();
        panel.add(scrollPane1, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(-1, 250), null, 0, false));
        branchesTable = new JTable();
        scrollPane1.setViewportView(branchesTable);

        // Warning panel (row 2, col 0-1)
        warningPanel = new JPanel();
        warningPanel.setLayout(new GridLayoutManager(1, 1, new Insets(5, 5, 5, 5), -1, -1));
        panel.add(warningPanel, new GridConstraints(2, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(-1, 100), null, 0, false));
        warningPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(-2987746)), "⚠ Warning", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));

        warningTextArea = new JTextArea();
        warningTextArea.setEditable(false);
        warningPanel.add(warningTextArea, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(150, 50), null, 0, false));

        // Create new branch checkbox (row 3, col 0-1)
        createNewCheckBox = new JCheckBox();
        createNewCheckBox.setText("Create new branch:");
        panel.add(createNewCheckBox, new GridConstraints(3, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        // New branch name field (row 4, col 0-1)
        JLabel newBranchLabel = new JLabel();
        newBranchLabel.setText("  Branch name:");
        panel.add(newBranchLabel, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        newBranchNameField = new JTextField();
        panel.add(newBranchNameField, new GridConstraints(4, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        // Spacer (row 5)
        final JPanel spacer = new JPanel();
        panel.add(spacer, new GridConstraints(5, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));

        // Buttons (row 6, col 0-1)
        switchBtn = new JButton();
        switchBtn.setBackground(new Color(-11555609));
        switchBtn.setForeground(new Color(-1));
        switchBtn.setText("Switch");
        panel.add(switchBtn, new GridConstraints(6, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        refreshBtn = new JButton();
        refreshBtn.setText("Refresh");
        buttonPanel.add(refreshBtn);

        cancelBtn = new JButton();
        cancelBtn.setText("Cancel");
        buttonPanel.add(cancelBtn);

        panel.add(buttonPanel, new GridConstraints(6, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    private Font $$$getFont$$$(String fontName, int style, int size, Font currentFont) {
        if (currentFont == null) return null;
        String resultName;
        if (fontName == null) {
            resultName = currentFont.getName();
        } else {
            Font testFont = new Font(fontName, Font.PLAIN, 10);
            if (testFont.canDisplay('a') && testFont.canDisplay('1')) {
                resultName = fontName;
            } else {
                resultName = currentFont.getName();
            }
        }
        Font font = new Font(resultName, style >= 0 ? style : currentFont.getStyle(), size >= 0 ? size : currentFont.getSize());
        boolean isMac = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).startsWith("mac");
        Font fontWithFallback = isMac ? new Font(font.getFamily(), font.getStyle(), font.getSize()) : new StyleContext().getFont(font.getFamily(), font.getStyle(), font.getSize());
        return fontWithFallback instanceof FontUIResource ? fontWithFallback : new FontUIResource(fontWithFallback);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return panel;
    }
}
