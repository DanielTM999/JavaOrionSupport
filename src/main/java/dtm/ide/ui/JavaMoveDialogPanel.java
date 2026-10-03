package dtm.ide.ui;

import dtm.ide.refactor.JavaPathTransferPlan;
import dtm.stools.i18n.I18n;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

public final class JavaMoveDialogPanel extends JPanel {

    public enum Choice { REFACTOR, MOVE_ONLY, CANCEL }

    private static final int MAX_LISTED = 6;

    private final Consumer<Choice> onChosen;
    private final JCheckBox updateReferences = new JCheckBox();
    private final JButton confirmButton = new JButton();
    private boolean answered;

    public JavaMoveDialogPanel(JavaPathTransferPlan plan, Consumer<Choice> onChosen) {
        super(new BorderLayout(0, 12));
        this.onChosen = onChosen;
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel header = new JLabel(headerText(plan));
        header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() + 1f));
        body.add(left(header));
        body.add(Box.createVerticalStrut(10));

        int listed = 0;
        for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
            if (listed++ >= MAX_LISTED) {
                break;
            }
            body.add(left(detail(file.oldType() + ".java", packageLabel(file.oldPackage()),
                    packageLabel(file.newPackage()))));
        }
        for (JavaPathTransferPlan.FolderTransfer folder : plan.folders()) {
            if (listed++ >= MAX_LISTED) {
                break;
            }
            body.add(left(detail(text("package", "package"), packageLabel(folder.oldPackage()),
                    packageLabel(folder.newPackage()))));
        }
        int total = plan.files().size() + plan.folders().size();
        if (total > MAX_LISTED) {
            JLabel more = new JLabel(text("more", "... and {count} more").replace("{count}",
                    String.valueOf(total - MAX_LISTED)));
            more.setForeground(muted(more.getForeground()));
            body.add(left(more));
        }

        body.add(Box.createVerticalStrut(12));
        updateReferences.setText(text("updateReferences", "Update package, imports and references"));
        updateReferences.setOpaque(false);
        updateReferences.setSelected(!plan.hasConflicts());
        updateReferences.setEnabled(!plan.hasConflicts());
        updateReferences.addActionListener(event -> refreshConfirmText());
        body.add(left(updateReferences));

        if (plan.hasConflicts()) {
            body.add(Box.createVerticalStrut(8));
            JLabel conflict = new JLabel(text("conflict",
                    "Name conflict in the target ({names}); only a plain move is possible.")
                    .replace("{names}", String.join(", ", plan.conflicts())));
            conflict.setForeground(dangerColor());
            body.add(left(conflict));
        }

        JButton cancelButton = new JButton(text("cancel", "Cancel"));
        cancelButton.addActionListener(event -> answer(Choice.CANCEL));
        confirmButton.addActionListener(event -> answer(updateReferences.isSelected() ? Choice.REFACTOR : Choice.MOVE_ONLY));
        confirmButton.putClientProperty("JButton.buttonType", "default");
        refreshConfirmText();

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(confirmButton);

        add(body, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        bind(KeyEvent.VK_ENTER, "javaMove.confirm", confirmButton::doClick);
        bind(KeyEvent.VK_ESCAPE, "javaMove.cancel", () -> answer(Choice.CANCEL));
    }

    public void focusConfirm() {
        confirmButton.requestFocusInWindow();
        JComponent root = SwingUtilities.getRootPane(this);
        if (root instanceof javax.swing.JRootPane rootPane) {
            rootPane.setDefaultButton(confirmButton);
        }
    }

    public void closed() {
        if (!answered) {
            answered = true;
            onChosen.accept(Choice.CANCEL);
        }
    }

    private void answer(Choice choice) {
        if (answered) {
            return;
        }
        answered = true;
        onChosen.accept(choice);
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
        }
    }

    private void refreshConfirmText() {
        confirmButton.setText(updateReferences.isSelected()
                ? text("refactor", "Refactor")
                : text("moveOnly", "Move"));
    }

    private static String headerText(JavaPathTransferPlan plan) {
        if (plan.files().size() == 1 && plan.folders().isEmpty()) {
            return text("header.single", "Move class {name}").replace("{name}", plan.files().getFirst().oldType());
        }
        if (plan.files().isEmpty() && plan.folders().size() == 1) {
            return text("header.package", "Move package {name}")
                    .replace("{name}", packageLabel(plan.folders().getFirst().oldPackage()));
        }
        return text("header.many", "Move {count} items")
                .replace("{count}", String.valueOf(plan.files().size() + plan.folders().size()));
    }

    private static JComponent detail(String name, String from, String to) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
        JLabel fromLabel = new JLabel(from);
        fromLabel.setForeground(muted(fromLabel.getForeground()));
        JLabel arrow = new JLabel("→");
        JLabel toLabel = new JLabel(to);
        row.add(nameLabel);
        row.add(fromLabel);
        row.add(arrow);
        row.add(toLabel);
        return row;
    }

    static String packageLabel(String packageName) {
        return packageName == null || packageName.isBlank() ? text("defaultPackage", "(default package)") : packageName;
    }

    private void bind(int key, String name, Runnable action) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, 0), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static Color muted(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), 160);
    }

    private static Color dangerColor() {
        Color color = UIManager.getColor("Component.error.focusedBorderColor");
        return color == null ? new Color(220, 53, 69) : color;
    }

    private static String text(String key, String fallback) {
        return I18n.getText(JavaMoveDialogPanel.class, key, fallback);
    }
}
