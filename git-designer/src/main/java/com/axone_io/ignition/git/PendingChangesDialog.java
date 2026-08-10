package com.axone_io.ignition.git;

import com.axone_io.ignition.git.dto.RepoDirtyState;

import javax.swing.*;
import java.awt.*;

/**
 * Non-production prompt shown once the working tree has drifted from HEAD. Deliberately
 * lighter than {@link ProductionModePopup}: on a development gateway the risk is forgetting
 * to commit, not committing something dangerous.
 */
public class PendingChangesDialog extends JDialog {

    public PendingChangesDialog(Window parent, RepoDirtyState state, Runnable onCommit) {
        super(parent, "Uncommitted Changes", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

        JLabel heading = new JLabel("This gateway has changes that are not in Git.");
        heading.setFont(new Font("Dialog", Font.BOLD, 13));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(heading);

        main.add(Box.createVerticalStrut(8));

        JLabel summary = new JLabel(state.describeChanges() + ".");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(summary);

        main.add(Box.createVerticalStrut(15));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton notNow = new JButton("Not now");
        notNow.addActionListener(e -> dispose());

        JButton commit = new JButton("Commit");
        commit.addActionListener(e -> {
            dispose();
            onCommit.run();
        });

        buttons.add(notNow);
        buttons.add(commit);
        main.add(buttons);

        getRootPane().setDefaultButton(commit);
        setContentPane(main);
        pack();
        setLocationRelativeTo(parent);
        setVisible(true);
    }
}
