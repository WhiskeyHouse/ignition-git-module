package com.axone_io.ignition.git;


import com.axone_io.ignition.git.components.SelectAllHeader;
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
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.swing.text.StyleContext;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class CommitPopup extends JFrame {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private JPanel panel;
    private JTextArea messageTextArea;
    private JLabel messageLabel;
    private JButton commitBtn;
    private JButton cancelBtn;
    private JLabel changesLabel;
    private JTable changesTable;
    private JTextField searchField;
    private JLabel searchLabel;
    private TableRowSorter<DefaultTableModel> rowSorter;

    public CommitPopup(Object[][] data, Component parent) {
        try {
            InputStream commitIconStream = getClass().getResourceAsStream("/com/axone_io/ignition/git/icons/ic_commit.svg");
            ImageIcon commitIcon = new ImageIcon(ImageIO.read(commitIconStream));
            setIconImage(commitIcon.getImage());
        } catch (IOException e) {
            logger.trace(e.toString(), e);
        }
        setContentPane(panel);
        setTitle("Commit");
        setSize(500, 500);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setVisible(true);

        changesTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        changesTable.getTableHeader().setReorderingAllowed(false);

        // Add search field
        setupSearchField();

        setData(data);

        commitBtn.addActionListener(e -> {
            List<String> changes = new ArrayList<>();
            DefaultTableModel model = (DefaultTableModel) changesTable.getModel();

            // Iterate through the view (visible rows only) to get checked items
            for (int viewRow = 0; viewRow < changesTable.getRowCount(); viewRow++) {
                int modelRow = changesTable.convertRowIndexToModel(viewRow);
                if ((Boolean) model.getValueAt(modelRow, 0)) {
                    changes.add((String) model.getValueAt(modelRow, 1));
                }
            }

            onActionPerformed(changes, messageTextArea.getText());
            this.dispose();
        });

        cancelBtn.addActionListener(e -> this.dispose());

        pack();
        CommonUI.centerComponent(this, parent);
        toFront();
    }

    public void resetMessage() {
        messageTextArea.setText("");
    }

    private void setupSearchField() {
        // Create search components
        searchLabel = new JLabel("Search:");
        searchLabel.setFont(searchLabel.getFont().deriveFont(Font.BOLD));
        searchField = new JTextField(20);
        searchField.setToolTipText("Filter changes by resource name, type, or author");

        // Add to panel - insert between row 0 (Changes label) and row 1 (table)
        // We need to shift the existing components down
        GridLayoutManager layout = (GridLayoutManager) panel.getLayout();

        // Create a new panel for search
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        searchPanel.add(searchLabel);
        searchPanel.add(searchField);

        // Add to main panel at row 0, column 1 (next to Changes label)
        panel.add(searchPanel, new GridConstraints(0, 1, 1, 1,
            GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
            GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    public void setData(Object[][] data) {
        String[] columnNames = {"", "Resource Name", "Type", "Author"};
        DefaultTableModel model = new DefaultTableModel(data, columnNames) {
            public Class<?> getColumnClass(int column) {
                switch (column) {
                    case 0:
                        return Boolean.class;
                    case 1:
                    case 2:
                    case 3:
                    default:
                        return String.class;
                }
            }
        };

        changesTable.setModel(model);
        changesTable.getColumn("").setPreferredWidth(20);
        changesTable.getColumn("Resource Name").setPreferredWidth(330);
        changesTable.getColumn("Type").setPreferredWidth(100);
        changesTable.getColumn("Author").setPreferredWidth(100);

        TableColumn tc = changesTable.getColumnModel().getColumn(0);
        tc.setHeaderRenderer(new SelectAllHeader(changesTable, 0));

        // Setup row sorter for filtering
        rowSorter = new TableRowSorter<>(model);
        changesTable.setRowSorter(rowSorter);

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
            // Filter on columns 1 (Resource Name), 2 (Type), and 3 (Author)
            rowSorter.setRowFilter(RowFilter.orFilter(
                List.of(
                    RowFilter.regexFilter("(?i)" + Pattern.quote(searchText), 1),
                    RowFilter.regexFilter("(?i)" + Pattern.quote(searchText), 2),
                    RowFilter.regexFilter("(?i)" + Pattern.quote(searchText), 3)
                )
            ));
        }
    }


    public void onActionPerformed(List<String> changes, String message) {

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
        panel.setLayout(new GridLayoutManager(5, 2, new Insets(5, 5, 5, 5), -1, -1));
        panel.setPreferredSize(new Dimension(500, 500));
        messageLabel = new JLabel();
        Font messageLabelFont = this.$$$getFont$$$(null, Font.BOLD, -1, messageLabel.getFont());
        if (messageLabelFont != null) messageLabel.setFont(messageLabelFont);
        messageLabel.setText("Your commit message :");
        panel.add(messageLabel, new GridConstraints(2, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        commitBtn = new JButton();
        commitBtn.setBackground(new Color(-11555609));
        commitBtn.setForeground(new Color(-1));
        commitBtn.setText("Commit");
        panel.add(commitBtn, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        cancelBtn = new JButton();
        cancelBtn.setText("Cancel");
        panel.add(cancelBtn, new GridConstraints(4, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        changesLabel = new JLabel();
        Font changesLabelFont = this.$$$getFont$$$(null, Font.BOLD, -1, changesLabel.getFont());
        if (changesLabelFont != null) changesLabel.setFont(changesLabelFont);
        changesLabel.setText("Changes :");
        panel.add(changesLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JScrollPane scrollPane1 = new JScrollPane();
        panel.add(scrollPane1, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        changesTable = new JTable();
        scrollPane1.setViewportView(changesTable);
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel.add(panel1, new GridConstraints(3, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel1.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(-4143670)), null, TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        messageTextArea = new JTextArea();
        messageTextArea.setText("");
        panel1.add(messageTextArea, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(150, 50), null, 0, false));
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
