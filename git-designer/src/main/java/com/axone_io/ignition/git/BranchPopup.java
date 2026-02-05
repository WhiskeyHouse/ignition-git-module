package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;

public class BranchPopup extends JFrame {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private JPanel panel;
    private JTable branchesTable;
    private JTextField searchField;
    private JLabel searchLabel;
    private JButton switchBtn;
    private JButton refreshBtn;
    private JButton cancelBtn;
    private JPanel warningPanel;
    private JTextArea warningTextArea;
    private JCheckBox createNewCheckBox;
    private JTextField newBranchNameField;
    private JCheckBox forceCheckoutCheckBox;
    private JButton stashBtn;
    private JButton discardBtn;
    private JButton resolveOursBtn;
    private JButton resolveTheirsBtn;
    private JButton abortMergeBtn;
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

        // Build the UI using standard Swing layouts
        setupUI();

        setContentPane(panel);
        setTitle("Switch Branch");
        setSize(600, 600);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        branchesTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        branchesTable.getTableHeader().setReorderingAllowed(false);
        branchesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Setup warning panel visibility
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
            boolean forceCheckout = forceCheckoutCheckBox != null && forceCheckoutCheckBox.isSelected();

            if (createNewCheckBox.isSelected()) {
                String newBranchName = newBranchNameField.getText().trim();
                if (newBranchName.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "Please enter a branch name.",
                            "Invalid Input",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                onSwitchBranch(newBranchName, true, forceCheckout);
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

                onSwitchBranch(branchName, false, forceCheckout);
            }
        });

        // Refresh button action
        refreshBtn.addActionListener(e -> onRefresh());

        // Cancel button action
        cancelBtn.addActionListener(e -> this.dispose());

        pack();
        setVisible(true);
        CommonUI.centerComponent(this, parent);
        toFront();
    }

    private void setupUI() {
        panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(600, 600));

        // Top panel with label and search
        JPanel topPanel = new JPanel(new BorderLayout(10, 0));
        JLabel branchesLabel = new JLabel("Branches:");
        branchesLabel.setFont(branchesLabel.getFont().deriveFont(Font.BOLD));
        topPanel.add(branchesLabel, BorderLayout.WEST);

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        searchLabel = new JLabel("Search:");
        searchField = new JTextField(15);
        searchField.setToolTipText("Filter branches by name");
        searchPanel.add(searchLabel);
        searchPanel.add(searchField);
        topPanel.add(searchPanel, BorderLayout.EAST);

        panel.add(topPanel, BorderLayout.NORTH);

        // Center panel with table and warning
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

        // Table
        branchesTable = new JTable();
        JScrollPane scrollPane = new JScrollPane(branchesTable);
        scrollPane.setPreferredSize(new Dimension(-1, 300));
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        // Warning panel (initially hidden)
        warningPanel = new JPanel(new BorderLayout(5, 5));
        warningPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(255, 193, 7)),
                "⚠ Warning",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                null,
                new Color(102, 60, 0)));
        warningTextArea = new JTextArea(3, 40);
        warningTextArea.setEditable(false);
        warningTextArea.setLineWrap(true);
        warningTextArea.setWrapStyleWord(true);
        warningTextArea.setBackground(new Color(255, 243, 205));
        warningTextArea.setForeground(new Color(102, 60, 0));
        warningPanel.add(new JScrollPane(warningTextArea), BorderLayout.CENTER);
        warningPanel.setVisible(false);
        centerPanel.add(warningPanel, BorderLayout.SOUTH);

        panel.add(centerPanel, BorderLayout.CENTER);

        // Bottom panel with options and buttons
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        // Create new branch option
        JPanel newBranchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        createNewCheckBox = new JCheckBox("Create new branch:");
        newBranchNameField = new JTextField(20);
        newBranchPanel.add(createNewCheckBox);
        newBranchPanel.add(newBranchNameField);
        bottomPanel.add(newBranchPanel);

        // Spacer
        bottomPanel.add(Box.createVerticalStrut(10));

        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));

        switchBtn = new JButton("Switch");
        switchBtn.setBackground(new Color(76, 175, 80));
        switchBtn.setForeground(Color.WHITE);
        switchBtn.setOpaque(true);
        switchBtn.setBorderPainted(false);
        buttonsPanel.add(switchBtn);

        refreshBtn = new JButton("Refresh");
        buttonsPanel.add(refreshBtn);

        cancelBtn = new JButton("Cancel");
        buttonsPanel.add(cancelBtn);

        bottomPanel.add(buttonsPanel);

        panel.add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setupWarningPanel() {
        if (currentStatus != null && currentStatus.hasWarnings()) {
            warningPanel.setVisible(true);
            warningTextArea.setText(currentStatus.getWarningMessage());

            // Remove any existing action panel
            for (Component comp : warningPanel.getComponents()) {
                if (comp instanceof JPanel && !(comp instanceof JScrollPane)) {
                    warningPanel.remove(comp);
                }
            }

            // Create action panel with vertical layout for multiple rows
            JPanel actionContainer = new JPanel();
            actionContainer.setLayout(new BoxLayout(actionContainer, BoxLayout.Y_AXIS));

            // Check if in merging state with conflicts
            if (currentStatus.isMerging() && currentStatus.hasConflicts()) {
                JPanel conflictPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
                conflictPanel.setBorder(BorderFactory.createTitledBorder("Resolve Merge Conflicts"));

                resolveOursBtn = new JButton("Keep Ours");
                resolveOursBtn.setToolTipText("Keep your local version for all conflicted files");
                resolveOursBtn.addActionListener(e -> {
                    int result = JOptionPane.showConfirmDialog(this,
                            "This will resolve all conflicts by keeping YOUR version.\n" +
                            "The incoming changes will be discarded.\n\n" +
                            "Are you sure?",
                            "Confirm Resolve - Keep Ours",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (result == JOptionPane.YES_OPTION) {
                        onResolveConflicts("ours");
                    }
                });
                conflictPanel.add(resolveOursBtn);

                resolveTheirsBtn = new JButton("Accept Theirs");
                resolveTheirsBtn.setToolTipText("Accept the incoming version for all conflicted files");
                resolveTheirsBtn.addActionListener(e -> {
                    int result = JOptionPane.showConfirmDialog(this,
                            "This will resolve all conflicts by accepting INCOMING changes.\n" +
                            "Your local changes will be discarded.\n\n" +
                            "Are you sure?",
                            "Confirm Resolve - Accept Theirs",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (result == JOptionPane.YES_OPTION) {
                        onResolveConflicts("theirs");
                    }
                });
                conflictPanel.add(resolveTheirsBtn);

                abortMergeBtn = new JButton("Abort Merge");
                abortMergeBtn.setToolTipText("Cancel the merge and return to the previous state");
                abortMergeBtn.setForeground(new Color(180, 60, 60));
                abortMergeBtn.addActionListener(e -> {
                    int result = JOptionPane.showConfirmDialog(this,
                            "This will abort the merge and reset to the previous state.\n" +
                            "All merge changes will be lost.\n\n" +
                            "Are you sure?",
                            "Confirm Abort Merge",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (result == JOptionPane.YES_OPTION) {
                        onAbortMerge();
                    }
                });
                conflictPanel.add(abortMergeBtn);

                actionContainer.add(conflictPanel);
            }

            // Add action buttons for uncommitted changes (only if not just conflicts)
            if (currentStatus.hasUncommittedChanges() || currentStatus.getUnpushedCommits() > 0) {
                JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

                stashBtn = new JButton("Stash Changes");
                stashBtn.setToolTipText("Save uncommitted changes to stash");
                stashBtn.addActionListener(e -> {
                    String message = JOptionPane.showInputDialog(this,
                            "Enter stash message (optional):",
                            "Stash Changes",
                            JOptionPane.QUESTION_MESSAGE);
                    if (message != null) { // User didn't cancel
                        onStash(message);
                    }
                });
                actionPanel.add(stashBtn);

                discardBtn = new JButton("Discard All");
                discardBtn.setToolTipText("Discard all uncommitted changes (cannot be undone!)");
                discardBtn.setForeground(new Color(180, 60, 60));
                discardBtn.addActionListener(e -> {
                    int result = JOptionPane.showConfirmDialog(this,
                            "This will permanently discard ALL uncommitted changes.\n" +
                            "This action cannot be undone!\n\n" +
                            "Are you sure you want to continue?",
                            "Confirm Discard",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (result == JOptionPane.YES_OPTION) {
                        onDiscard();
                    }
                });
                actionPanel.add(discardBtn);

                // Add force checkout checkbox
                forceCheckoutCheckBox = new JCheckBox("Force checkout");
                forceCheckoutCheckBox.setToolTipText("Force checkout even if there are conflicting uncommitted changes");
                actionPanel.add(forceCheckoutCheckBox);

                actionContainer.add(actionPanel);
            }

            warningPanel.add(actionContainer, BorderLayout.SOUTH);
            warningPanel.revalidate();
        } else {
            warningPanel.setVisible(false);
            forceCheckoutCheckBox = null;
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
    public void onSwitchBranch(String branchName, boolean createNew, boolean forceCheckout) {
        // Override in GitActionManager
    }

    public void onRefresh() {
        // Override in GitActionManager
    }

    public void onStash(String message) {
        // Override in GitActionManager
    }

    public void onDiscard() {
        // Override in GitActionManager
    }

    public void onResolveConflicts(String strategy) {
        // Override in GitActionManager
    }

    public void onAbortMerge() {
        // Override in GitActionManager
    }
}
