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
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class JavaCopyDialogPanel extends JPanel {

    public record Result(boolean adjustSources, Map<Path, String> newTypeNames) {
    }

    private static final int MAX_LISTED = 6;

    private final JavaPathTransferPlan plan;
    private final Consumer<Result> onChosen;
    private final Map<Path, String> defaultNames;
    private final JTextField nameField = new JTextField(24);
    private final JLabel errorLabel = new JLabel(" ");
    private final JCheckBox adjustSources = new JCheckBox();
    private final JButton confirmButton = new JButton();
    private boolean answered;

    public JavaCopyDialogPanel(JavaPathTransferPlan plan, Map<Path, String> defaultNames, Consumer<Result> onChosen) {
        super(new BorderLayout(0, 12));
        this.plan = plan;
        this.onChosen = onChosen;
        this.defaultNames = new LinkedHashMap<>(defaultNames);
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel header = new JLabel(headerText());
        header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() + 1f));
        body.add(left(header));
        body.add(Box.createVerticalStrut(10));

        if (isSingleFile()) {
            JavaPathTransferPlan.FileTransfer file = plan.files().getFirst();
            JPanel nameRow = new JPanel(new BorderLayout(8, 0));
            nameRow.setOpaque(false);
            nameRow.add(new JLabel(text("newName", "New name:")), BorderLayout.WEST);
            nameField.setText(defaultNames.getOrDefault(file.source(), file.oldType()));
            nameField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    validateInput();
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    validateInput();
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    validateInput();
                }
            });
            nameRow.add(nameField, BorderLayout.CENTER);
            body.add(left(nameRow));
            body.add(Box.createVerticalStrut(6));
            body.add(left(new JLabel(text("targetPackage", "Target package: {name}")
                    .replace("{name}", JavaMoveDialogPanel.packageLabel(file.newPackage())))));
        } else {
            int listed = 0;
            for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
                if (listed++ >= MAX_LISTED) {
                    break;
                }
                body.add(left(new JLabel(file.oldType() + "  →  "
                        + JavaMoveDialogPanel.packageLabel(file.newPackage()) + "."
                        + defaultNames.getOrDefault(file.source(), file.oldType()))));
            }
            for (JavaPathTransferPlan.FolderTransfer folder : plan.folders()) {
                if (listed++ >= MAX_LISTED) {
                    break;
                }
                body.add(left(new JLabel(JavaMoveDialogPanel.packageLabel(folder.oldPackage()) + "  →  "
                        + JavaMoveDialogPanel.packageLabel(folder.newPackage()))));
            }
            int total = plan.files().size() + plan.folders().size();
            if (total > MAX_LISTED) {
                body.add(left(new JLabel(I18n.getText(JavaMoveDialogPanel.class, "more", "... and {count} more")
                        .replace("{count}", String.valueOf(total - MAX_LISTED)))));
            }
        }

        body.add(Box.createVerticalStrut(10));
        adjustSources.setText(text("adjustSources", "Adjust package and class name"));
        adjustSources.setOpaque(false);
        adjustSources.setSelected(!plan.hasConflicts());
        adjustSources.setEnabled(!plan.hasConflicts());
        adjustSources.addActionListener(event -> validateInput());
        body.add(left(adjustSources));
        body.add(Box.createVerticalStrut(6));
        errorLabel.setForeground(dangerColor());
        body.add(left(errorLabel));

        JButton cancelButton = new JButton(text("cancel", "Cancel"));
        cancelButton.addActionListener(event -> answer(null));
        confirmButton.setText(text("copy", "Copy"));
        confirmButton.putClientProperty("JButton.buttonType", "default");
        confirmButton.addActionListener(event -> confirm());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(confirmButton);

        add(body, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        bind(KeyEvent.VK_ENTER, "javaCopy.confirm", this::confirm);
        bind(KeyEvent.VK_ESCAPE, "javaCopy.cancel", () -> answer(null));
        if (plan.hasConflicts()) {
            errorLabel.setText(text("conflict",
                    "Invalid name in the target ({names}); the items will be copied without adjustments.")
                    .replace("{names}", String.join(", ", plan.conflicts())));
        }
        validateInput();
    }

    public void focusInput() {
        if (isSingleFile()) {
            nameField.requestFocusInWindow();
            nameField.selectAll();
        } else {
            confirmButton.requestFocusInWindow();
        }
    }

    public void closed() {
        if (!answered) {
            answered = true;
            onChosen.accept(null);
        }
    }

    String validationMessage(String typeName) {
        if (!JavaPathTransferPlan.isValidTypeName(typeName)) {
            return text("invalidName", "\"{name}\" is not a valid class name.").replace("{name}", typeName);
        }
        JavaPathTransferPlan.FileTransfer file = plan.files().getFirst();
        Path target = file.target().getParent().resolve(typeName + ".java");
        if (Files.exists(target)) {
            return text("exists", "{name}.java already exists in the target package.").replace("{name}", typeName);
        }
        return null;
    }

    private void validateInput() {
        if (!isSingleFile() || !adjustSources.isSelected()) {
            if (!plan.hasConflicts()) {
                errorLabel.setText(" ");
            }
            confirmButton.setEnabled(true);
            return;
        }
        String message = validationMessage(nameField.getText().strip());
        errorLabel.setText(message == null ? " " : message);
        confirmButton.setEnabled(message == null);
    }

    private void confirm() {
        if (!confirmButton.isEnabled()) {
            return;
        }
        Map<Path, String> names = new LinkedHashMap<>(defaultNames);
        if (isSingleFile()) {
            names.put(plan.files().getFirst().source(), nameField.getText().strip());
        }
        answer(new Result(adjustSources.isSelected(), names));
    }

    private void answer(Result result) {
        if (answered) {
            return;
        }
        answered = true;
        onChosen.accept(result);
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
        }
    }

    private boolean isSingleFile() {
        return plan.files().size() == 1 && plan.folders().isEmpty();
    }

    private String headerText() {
        if (isSingleFile()) {
            return text("header.single", "Copy class {name}").replace("{name}", plan.files().getFirst().oldType());
        }
        return text("header.many", "Copy {count} items")
                .replace("{count}", String.valueOf(plan.files().size() + plan.folders().size()));
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

    private static Color dangerColor() {
        Color color = UIManager.getColor("Component.error.focusedBorderColor");
        return color == null ? new Color(220, 53, 69) : color;
    }

    private static String text(String key, String fallback) {
        return I18n.getText(JavaCopyDialogPanel.class, key, fallback);
    }
}
