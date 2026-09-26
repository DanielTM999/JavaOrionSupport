package dtm.ide.ui;

import dtm.ide.build.BuildCommand;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class BuildPromptPanel extends JPanel {

    public record Choice(List<String> goals, boolean debug) {

        public Choice {
            goals = goals == null ? List.of() : List.copyOf(goals);
        }
    }

    private static final int WIDTH = 560;
    private static final int TITLE_BAR = 44;
    private static final int MAX_CHIPS = 5;
    private static final int MAX_CHIP_LENGTH = 30;

    private static String text(String key, String fallback) {
        return I18n.getText(JavaBuildToolsPanel.class, key, fallback);
    }

    private final MaskedTextField field = new MaskedTextField();
    private final Consumer<String> primaryAction;
    private final Consumer<String> secondaryAction;
    private final JButton primary;
    private final JButton secondary;
    private boolean answered;

    private BuildPromptPanel(String label, String initial, String placeholder, JComponent context,
                             List<String> chips, String chipsLabel, String keyHint,
                             String primaryLabel, Icon primaryIcon, Consumer<String> primaryAction,
                             String secondaryLabel, Icon secondaryIcon,
                             Consumer<String> secondaryAction) {
        super(new BorderLayout());
        this.primaryAction = primaryAction;
        this.secondaryAction = secondaryAction;
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(4), UiTokens.space(4), UiTokens.space(3), UiTokens.space(4)));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel caption = new JLabel(label);
        caption.setFont(UiTokens.fontBold());
        caption.setForeground(UiTokens.foreground());
        caption.setLabelFor(field);
        content.add(left(caption));
        content.add(Box.createVerticalStrut(UiTokens.space(2)));

        field.setText(initial == null ? "" : initial);
        field.setPlaceholder(placeholder);
        field.setFont(UiTokens.fontMono());
        field.putClientProperty("JComponent.roundRect", false);
        Dimension fieldSize = new Dimension(UiTokens.scale(WIDTH), UiTokens.scale(PillButtons.FIELD_HEIGHT + 4));
        field.setPreferredSize(fieldSize);
        content.add(left(field));

        if (context != null) {
            content.add(Box.createVerticalStrut(UiTokens.space(2)));
            content.add(left(context));
        }

        List<String> visibleChips = chips == null ? List.of()
                : chips.stream().limit(MAX_CHIPS).toList();
        if (!visibleChips.isEmpty()) {
            content.add(Box.createVerticalStrut(UiTokens.space(3)));
            JLabel chipsCaption = new JLabel(chipsLabel);
            chipsCaption.setFont(UiTokens.fontSmall());
            chipsCaption.setForeground(UiTokens.muted());
            content.add(left(chipsCaption));
            content.add(Box.createVerticalStrut(UiTokens.space(1)));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            for (String chip : visibleChips) {
                row.add(chip(chip));
                row.add(Box.createHorizontalStrut(UiTokens.space(1)));
            }
            content.add(left(row));
        }
        content.add(Box.createVerticalGlue());
        add(content, BorderLayout.CENTER);

        primary = PillButtons.primary(primaryLabel, primaryIcon);
        primary.addActionListener(event -> answer(false));
        secondary = secondaryAction == null ? null : PillButtons.secondary(secondaryLabel, secondaryIcon);
        if (secondary != null) {
            secondary.addActionListener(event -> answer(true));
        }
        JButton cancel = PillButtons.ghost(text("dialog.cancel", "Cancelar"), null);
        cancel.addActionListener(event -> cancel());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.space(2), 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        if (secondary != null) {
            buttons.add(secondary);
        }
        buttons.add(primary);

        JLabel hint = new JLabel(keyHint);
        hint.setFont(UiTokens.fontSmall());
        hint.setForeground(UiTokens.muted());

        JPanel footer = new JPanel(new BorderLayout(UiTokens.space(3), 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.space(3), 0, 0, 0)));
        footer.add(hint, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(4), 0, 0, 0));
        south.add(footer, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refreshButtons();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refreshButtons();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                refreshButtons();
            }
        });
        refreshButtons();

        bind(KeyEvent.VK_ENTER, 0, "buildPrompt.primary", () -> answer(false));
        bind(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, "buildPrompt.secondary", () -> {
            if (secondaryAction != null) {
                answer(true);
            }
        });
        bind(KeyEvent.VK_ESCAPE, 0, "buildPrompt.cancel", this::cancel);
    }

    public static BuildPromptPanel runGoal(String initial, String moduleName, List<String> recent,
                                           List<String> suggestions, boolean debugAvailable,
                                           Consumer<Choice> onChosen) {
        Consumer<Choice> target = onChosen == null ? choice -> {
        } : onChosen;
        Set<String> chips = new LinkedHashSet<>();
        if (recent != null) {
            chips.addAll(recent);
        }
        if (suggestions != null) {
            chips.addAll(suggestions);
        }
        boolean hasRecent = recent != null && !recent.isEmpty();
        return new BuildPromptPanel(
                text("dialog.runGoal.label", "Comando"),
                initial,
                text("dialog.runGoal.placeholder", "clean install -U -DskipTests"),
                contextLine(JavaIcons.module(JavaIcons.SMALL),
                        text("dialog.runGoal.module", "Executa em:") + " "
                                + (moduleName == null || moduleName.isBlank()
                                ? text("dialog.runGoal.rootModule", "projeto inteiro") : moduleName)),
                new ArrayList<>(chips),
                hasRecent ? text("dialog.runGoal.recent", "Recentes e sugestoes")
                        : text("dialog.runGoal.suggestions", "Sugestoes"),
                debugAvailable
                        ? text("dialog.runGoal.keys", "Enter executa  -  Shift+Enter depura  -  Esc cancela")
                        : text("dialog.runGoal.keysNoDebug", "Enter executa  -  Esc cancela"),
                text("dialog.runGoal.run", "Executar"), JavaIcons.run(JavaIcons.SMALL),
                line -> target.accept(new Choice(BuildCommand.parseArguments(line), false)),
                text("dialog.runGoal.debug", "Depurar"), JavaIcons.debug(JavaIcons.SMALL),
                debugAvailable
                        ? line -> target.accept(new Choice(BuildCommand.parseArguments(line), true))
                        : null);
    }

    public static BuildPromptPanel saveConfiguration(String suggestedName, List<String> goals,
                                                     Consumer<String> onSaved) {
        Consumer<String> target = onSaved == null ? name -> {
        } : onSaved;
        JLabel command = new JLabel(BuildCommand.joinArguments(goals));
        command.setFont(UiTokens.fontMono());
        command.setForeground(UiTokens.foreground());
        JPanel context = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        context.setOpaque(false);
        JLabel prefix = new JLabel(text("dialog.saveConfiguration.goals", "Goals:"),
                JavaIcons.goal(JavaIcons.SMALL), JLabel.LEFT);
        prefix.setFont(UiTokens.fontSmall());
        prefix.setForeground(UiTokens.muted());
        context.add(prefix, BorderLayout.WEST);
        context.add(command, BorderLayout.CENTER);
        return new BuildPromptPanel(
                text("dialog.saveConfiguration.label", "Nome da configuracao"),
                suggestedName,
                text("dialog.saveConfiguration.placeholder", "Ex.: Build sem testes"),
                context,
                List.of(),
                "",
                text("dialog.saveConfiguration.keys", "Enter salva  -  Esc cancela"),
                text("dialog.saveConfiguration.save", "Salvar"), JavaIcons.create(JavaIcons.SMALL),
                line -> target.accept(line.trim()),
                null, null, null);
    }

    public Dimension popupSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Math.max(preferred.width, UiTokens.scale(WIDTH + 40)),
                preferred.height + UiTokens.scale(TITLE_BAR));
    }

    public void focusField() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    public void closed() {
        answered = true;
    }

    String fieldText() {
        return field.getText();
    }

    void setFieldText(String value) {
        field.setText(value);
    }

    boolean hasSecondaryAction() {
        return secondary != null;
    }

    void answer(boolean useSecondary) {
        if (answered) {
            return;
        }
        Consumer<String> action = useSecondary ? secondaryAction : primaryAction;
        String value = field.getText() == null ? "" : field.getText().trim();
        if (action == null || value.isEmpty()) {
            field.requestFocusInWindow();
            return;
        }
        answered = true;
        action.accept(value);
        close();
    }

    void cancel() {
        answered = true;
        close();
    }

    private void refreshButtons() {
        boolean filled = field.getText() != null && !field.getText().isBlank();
        primary.setEnabled(filled);
        if (secondary != null) {
            secondary.setEnabled(filled);
        }
    }

    private JButton chip(String command) {
        String label = command.length() > MAX_CHIP_LENGTH
                ? command.substring(0, MAX_CHIP_LENGTH - 1) + "..." : command;
        JButton chip = PillButtons.ghost(label, null);
        chip.setFont(UiTokens.fontSmall());
        chip.setToolTipText(command);
        chip.addActionListener(event -> {
            field.setText(command);
            focusField();
        });
        return chip;
    }

    private static JComponent contextLine(Icon icon, String value) {
        JLabel label = new JLabel(value, icon, JLabel.LEFT);
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.muted());
        label.setIconTextGap(UiTokens.space(1));
        return label;
    }

    private static Component left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                component.getPreferredSize().height));
        return component;
    }

    private void close() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
        }
    }

    private void bind(int key, int modifiers, String name, Runnable action) {
        AbstractAction wrapped = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        };
        KeyStroke stroke = KeyStroke.getKeyStroke(key, modifiers);
        field.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name);
        field.getActionMap().put(name, wrapped);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name);
        getActionMap().put(name, wrapped);
    }
}
