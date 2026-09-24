package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.designer.gui.CommonUI;

import javax.swing.*;
import java.awt.*;

/**
 * Pull settings dialog. Allows the user to select which gateway resources to import
 * after pulling from Git.
 *
 * <p>An <em>owned</em> {@link JDialog}, not a {@link JFrame}. As an unowned frame this opened
 * behind the Designer window: an unowned top-level window has no z-order relationship to the
 * Designer, and {@code toFront()} is only a request the window manager may ignore — which it
 * reliably did when this was created just after a modal dialog closed, because the queued
 * focus-restoration to the Designer frame ran afterwards and lifted it back over the top. A
 * dialog owned by the Designer frame is kept above its owner by the window manager, so
 * correctness no longer depends on winning that race. Modeless, so it does not block the
 * Designer, matching the previous behaviour.</p>
 *
 * <p>Note: Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class PullPopup extends JDialog {

    private final JCheckBox imagesCheckBox;
    private final JCheckBox themesCheckBox;
    private final JCheckBox tagsCheckBox;

    public PullPopup(Component parent) {
        super(parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent),
              "Pull Settings", ModalityType.MODELESS);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(false);

        // Main content panel using BoxLayout (vertical)
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        JLabel actionLabel = new JLabel("Do you also wish to import the following:");
        actionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(actionLabel);
        mainPanel.add(Box.createVerticalStrut(8));

        imagesCheckBox = new JCheckBox("Images");
        imagesCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(imagesCheckBox);

        themesCheckBox = new JCheckBox("Themes");
        themesCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(themesCheckBox);

        tagsCheckBox = new JCheckBox("Tags & Tag Groups");
        tagsCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(tagsCheckBox);

        mainPanel.add(Box.createVerticalStrut(12));

        // Button panel
        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 6, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        JButton pullButton = new JButton("Pull and Import");
        pullButton.setBackground(new Color(0x4E9AA7));
        pullButton.setForeground(Color.WHITE);
        pullButton.setOpaque(true);
        pullButton.addActionListener(e -> {
            onPullAction(
                    tagsCheckBox.isSelected(),
                    themesCheckBox.isSelected(),
                    imagesCheckBox.isSelected());
            dispose();
        });

        btnPanel.add(cancelButton);
        btnPanel.add(pullButton);
        mainPanel.add(btnPanel);

        setContentPane(mainPanel);
        pack();
        setMinimumSize(new Dimension(300, getHeight()));

        CommonUI.centerComponent(this, parent);
        setVisible(true);
        toFront();
    }

    public void resetCheckboxes() {
        tagsCheckBox.setSelected(false);
        themesCheckBox.setSelected(false);
        imagesCheckBox.setSelected(false);
    }

    /**
     * Called when the user clicks "Pull and Import". Override to implement the pull action.
     *
     * @param importTags   whether to import tags and tag groups
     * @param importTheme  whether to import themes
     * @param importImages whether to import images
     */
    public void onPullAction(boolean importTags, boolean importTheme, boolean importImages) {
        // override in anonymous subclass
    }
}
