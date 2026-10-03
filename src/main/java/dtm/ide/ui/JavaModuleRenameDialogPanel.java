package dtm.ide.ui;

import dtm.ide.refactor.MavenModuleRename;
import dtm.stools.i18n.I18n;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class JavaModuleRenameDialogPanel extends JPanel {

    public record Result(MavenModuleRename.Scope scope, String name) {
    }

    private final MavenModuleRename.Target target;
    private final Consumer<Result> onChosen;
    private final Map<MavenModuleRename.Scope, JRadioButton> options = new EnumMap<>(MavenModuleRename.Scope.class);
    private final JTextField nameField = new JTextField();
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton confirmButton = new JButton();
    private MavenModuleRename.Scope scope;
    private boolean answered;

    public JavaModuleRenameDialogPanel(MavenModuleRename.Target target, Consumer<Result> onChosen) {
        super(new BorderLayout(0, 12));
        this.target = target;
        this.onChosen = onChosen;
        this.scope = MavenModuleRename.defaultScope(target);
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel header = new JLabel(text("header", "Folder {dir} is the Maven module {module}")
                .replace("{dir}", target.directoryName())
                .replace("{module}", target.artifactId()));
        header.setFont(header.getFont().deriveFont(Font.BOLD, header.getFont().getSize2D() + 1f));
        body.add(left(header));
        body.add(Box.createVerticalStrut(10));

        ButtonGroup group = new ButtonGroup();
        addOption(body, group, MavenModuleRename.Scope.BOTH, text("both", "Rename module and directory"));
        addOption(body, group, MavenModuleRename.Scope.DIRECTORY, text("directory", "Rename directory only"));
        addOption(body, group, MavenModuleRename.Scope.MODULE, text("module", "Rename module only (artifactId)"));
        options.get(scope).setSelected(true);

        body.add(Box.createVerticalStrut(12));
        body.add(left(new JLabel(text("name", "New name:"))));
        body.add(Box.createVerticalStrut(4));
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameField.getPreferredSize().height));
        nameField.setText(MavenModuleRename.initialName(target, scope));
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                revalidateName();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                revalidateName();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                revalidateName();
            }
        });
        nameField.addActionListener(event -> confirm());
        body.add(left(nameField));
        body.add(Box.createVerticalStrut(6));
        errorLabel.setForeground(dangerColor());
        body.add(left(errorLabel));

        JButton cancelButton = new JButton(text("cancel", "Cancel"));
        cancelButton.addActionListener(event -> answer(null));
        confirmButton.setText(text("rename", "Rename"));
        confirmButton.putClientProperty("JButton.buttonType", "default");
        confirmButton.addActionListener(event -> confirm());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(confirmButton);

        add(body, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        bind(KeyEvent.VK_ENTER, "javaModuleRename.confirm", this::confirm);
        bind(KeyEvent.VK_ESCAPE, "javaModuleRename.cancel", () -> answer(null));
        revalidateName();
    }

    public void focusInput() {
        nameField.requestFocusInWindow();
        nameField.selectAll();
        JComponent root = SwingUtilities.getRootPane(this);
        if (root instanceof javax.swing.JRootPane rootPane) {
            rootPane.setDefaultButton(confirmButton);
        }
    }

    public void closed() {
        if (!answered) {
            answered = true;
            onChosen.accept(null);
        }
    }

    private void addOption(JPanel body, ButtonGroup group, MavenModuleRename.Scope option, String label) {
        JRadioButton button = new JRadioButton(label);
        button.setOpaque(false);
        button.addActionListener(event -> select(option));
        group.add(button);
        options.put(option, button);
        body.add(left(button));
    }

    private void select(MavenModuleRename.Scope option) {
        if (option == scope) {
            return;
        }
        String previousInitial = MavenModuleRename.initialName(target, scope);
        scope = option;
        if (nameField.getText().trim().equals(previousInitial)) {
            nameField.setText(MavenModuleRename.initialName(target, scope));
        }
        nameField.requestFocusInWindow();
        nameField.selectAll();
        revalidateName();
    }

    private void revalidateName() {
        Optional<MavenModuleRename.Problem> problem = MavenModuleRename.validate(target, scope, nameField.getText());
        boolean silent = problem.isPresent() && problem.get() == MavenModuleRename.Problem.SAME;
        errorLabel.setText(problem.isEmpty() || silent ? " " : message(problem.get()));
        confirmButton.setEnabled(problem.isEmpty());
    }

    private void confirm() {
        if (MavenModuleRename.validate(target, scope, nameField.getText()).isPresent()) {
            revalidateName();
            return;
        }
        answer(new Result(scope, nameField.getText().trim()));
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

    private static String message(MavenModuleRename.Problem problem) {
        return switch (problem) {
            case EMPTY -> text("error.empty", "Enter the new name.");
            case SEPARATORS -> text("error.separators", "The name cannot contain path separators.");
            case INVALID -> text("error.invalid", "The name contains characters that are not allowed.");
            case SAME -> text("error.same", "The new name is the same as the current one.");
            case EXISTS -> text("error.exists", "A folder with that name already exists.");
            case ARTIFACT_IN_USE -> text("error.artifactInUse", "Another module of the project already uses that artifactId.");
        };
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
        return I18n.getText(JavaModuleRenameDialogPanel.class, key, fallback);
    }
}
