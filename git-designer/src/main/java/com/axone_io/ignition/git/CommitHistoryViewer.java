package com.axone_io.ignition.git;

import com.axone_io.ignition.git.utils.IconUtils;
import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CommitHistoryViewer extends JFrame {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private JPanel mainPanel;
    private JTable commitsTable;
    private JTextField searchField;
    private JButton closeBtn;
    private JLabel searchLabel;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private List<CommitInfo> allCommits;

    public CommitHistoryViewer(List<CommitInfo> commits, Component parent) {
        this.allCommits = commits;

        // TODO: Review IconUtils.getIcon() implementation to ensure it supports SVG (or add SVG handling there) so SVG icons are correctly loaded
        Icon historyIcon = IconUtils.getIcon("/com/axone_io/ignition/git/icons/ic_history.svg");
        if (historyIcon != null && historyIcon instanceof ImageIcon) {
            setIconImage(((ImageIcon) historyIcon).getImage());
        } else {
            if (historyIcon == null) {
                logger.error("Failed to load history icon: IconUtils.getIcon() returned null for /com/axone_io/ignition/git/icons/ic_history.svg");
            } else {
                logger.error("Failed to load history icon: Icon is not an ImageIcon (type: {})", historyIcon.getClass().getName());
            }
        }

        setupUI();
        setContentPane(mainPanel);
        setTitle("Commit History");
        setSize(900, 600);
        setMinimumSize(new Dimension(800, 500));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        setupTable();
        loadCommits(commits);
        setupSearchFilter();

        closeBtn.addActionListener(e -> this.dispose());

        setVisible(true);
        pack();
        CommonUI.centerComponent(this, parent);
        toFront();
    }

    private void setupUI() {
        mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Search panel at the top
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        searchLabel = new JLabel("Search files:");
        searchLabel.setFont(searchLabel.getFont().deriveFont(Font.BOLD));
        searchField = new JTextField(30);
        searchField.setToolTipText("Search for commits containing specific files");
        searchPanel.add(searchLabel);
        searchPanel.add(searchField);

        // Table in the center
        commitsTable = new JTable();
        commitsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        commitsTable.getTableHeader().setReorderingAllowed(false);
        commitsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(commitsTable);

        // Button panel at the bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        closeBtn = new JButton("Close");
        buttonPanel.add(closeBtn);

        mainPanel.add(searchPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
    }

    private void setupTable() {
        String[] columnNames = {"Hash", "Date", "Author", "Message", "Files"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // Make table read-only
            }
        };

        commitsTable.setModel(tableModel);
        commitsTable.getColumnModel().getColumn(0).setPreferredWidth(80);  // Hash
        commitsTable.getColumnModel().getColumn(1).setPreferredWidth(150); // Date
        commitsTable.getColumnModel().getColumn(2).setPreferredWidth(120); // Author
        commitsTable.getColumnModel().getColumn(3).setPreferredWidth(300); // Message
        commitsTable.getColumnModel().getColumn(4).setPreferredWidth(150); // Files count

        rowSorter = new TableRowSorter<>(tableModel);
        commitsTable.setRowSorter(rowSorter);
    }

    private void loadCommits(List<CommitInfo> commits) {
        tableModel.setRowCount(0); // Clear existing rows
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (CommitInfo commit : commits) {
            String shortHash = commit.getShortHash();
            String date = dateFormat.format(new Date(commit.getTimestamp()));
            String author = commit.getAuthor();
            String rawMessage = commit.getMessage();
            String message = rawMessage != null ? rawMessage.split("\n")[0] : "";

            List<String> filesChanged = commit.getFilesChanged();
            String filesInfo = filesChanged != null ? filesChanged.size() + " file(s)" : "0 file(s)";

            Object[] row = {shortHash, date, author, message, filesInfo};
            tableModel.addRow(row);
        }
    }

    private void setupSearchFilter() {
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterCommits();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterCommits();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterCommits();
            }
        });
    }

    private void filterCommits() {
        String searchText = searchField.getText().trim().toLowerCase();

        if (searchText.isEmpty()) {
            rowSorter.setRowFilter(null);
        } else {
            // Filter commits that contain files matching the search text
            List<RowFilter<DefaultTableModel, Integer>> filters = new ArrayList<>();

            for (int i = 0; i < allCommits.size(); i++) {
                CommitInfo commit = allCommits.get(i);
                List<String> filesChanged = commit.getFilesChanged();

                if (filesChanged != null) {
                    for (String file : filesChanged) {
                        if (file.toLowerCase().contains(searchText)) {
                            // Create a filter that shows this specific row
                            final int rowIndex = i;
                            filters.add(new RowFilter<DefaultTableModel, Integer>() {
                                @Override
                                public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                                    return entry.getIdentifier().equals(rowIndex);
                                }
                            });
                            break; // Found a match for this commit, no need to check more files
                        }
                    }
                }
            }

            if (!filters.isEmpty()) {
                rowSorter.setRowFilter(RowFilter.orFilter(filters));
            } else {
                // No matches, hide all rows
                rowSorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
                    @Override
                    public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                        return false;
                    }
                });
            }
        }
    }
}
