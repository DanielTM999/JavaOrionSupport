package dtm.ide.ui;

import dtm.ide.spring.jpa.JpaEntity;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.function.Consumer;

public final class RepositoryCreationPanel extends JPanel {

    public record Choice(String name, JpaEntity entity, String idType) {
    }

    private final JTextField name = new JTextField();
    private final JComboBox<JpaEntity> entity = new JComboBox<>();
    private final JTextField idType = new JTextField();
    private final JLabel error = new JLabel(" ");

    public RepositoryCreationPanel(List<JpaEntity> entities, Consumer<Choice> onCreate) {
        super(new BorderLayout());
        setBackground(UiTokens.background());
        setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));

        JPanel fields = new JPanel();
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(row("Nome da interface", name));
        fields.add(Box.createVerticalStrut(12));
        fields.add(row("Entidade (@Entity)", entity));
        fields.add(Box.createVerticalStrut(12));
        fields.add(row("Tipo do ID", idType));
        fields.add(Box.createVerticalStrut(8));
        error.setForeground(UiTokens.danger());
        fields.add(error);
        add(fields, BorderLayout.CENTER);

        for (JpaEntity candidate : entities) {
            entity.addItem(candidate);
        }
        entity.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.type());
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return label;
        });
        entity.addActionListener(event -> selectedEntityChanged());
        selectedEntityChanged();

        JButton cancel = PillButtons.ghost("Cancelar", null);
        cancel.addActionListener(event -> close());
        JButton create = PillButtons.primary("Criar", JavaIcons.create(JavaIcons.SMALL));
        create.addActionListener(event -> {
            String typeName = name.getText().trim();
            String id = idType.getText().trim();
            JpaEntity chosen = (JpaEntity) entity.getSelectedItem();
            if (chosen == null) {
                error.setText("Nenhuma entidade @Entity encontrada neste modulo.");
            } else if (!typeName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                error.setText("Informe um nome de interface Java valido.");
            } else if (!id.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                error.setText("Informe um tipo de ID Java valido.");
            } else {
                onCreate.accept(new Choice(typeName, chosen, id));
                close();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(create);
        add(buttons, BorderLayout.SOUTH);
        if (entities.isEmpty()) {
            error.setText("Nenhuma entidade @Entity encontrada neste modulo.");
        }
    }

    public void focusName() {
        name.requestFocusInWindow();
    }

    private void selectedEntityChanged() {
        JpaEntity selected = (JpaEntity) entity.getSelectedItem();
        if (selected == null) {
            return;
        }
        name.setText(selected.simpleName() + "Repository");
        idType.setText(selected.idField().map(field -> field.type()).orElse(""));
        error.setText(idType.getText().isBlank()
                ? "ID nao detectado: informe o tipo antes de criar." : " ");
    }

    private static JPanel row(String label, Component field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel caption = new JLabel(label);
        caption.setForeground(UiTokens.foreground());
        panel.add(caption, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private void close() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
        }
    }
}
