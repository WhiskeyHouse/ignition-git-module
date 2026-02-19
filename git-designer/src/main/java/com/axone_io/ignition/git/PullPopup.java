package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.designer.gui.CommonUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;

/**
 * Pull settings dialog. Allows the user to select which gateway resources to import
 * after pulling from Git.
 *
 * <p>Note: Uses standard Swing layouts only (no IntelliJ forms library).</p>
 */
public class PullPopup extends JFrame {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final JCheckBox imagesCheckBox;
    private final JCheckBox themesCheckBox;
    private final JCheckBox tagsCheckBox;

    public PullPopup(Component parent) {
        try {
            InputStream iconStream = getClass().getResourceAsStream(
                    "/com/axone_io/ignition/git/icons/ic_commit.svg");
            if (iconStream != null) {
                ImageIcon icon = new ImageIcon(ImageIO.read(iconStream));
                setIconImage(icon.getImage());
            }
        } catch (IOException e) {
            logger.trace(e.toString(), e);
        }

        setTitle("Pull Settings");
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
