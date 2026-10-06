package dtm.ide.run.form;

import dtm.ide.ui.PillButtons;
import dtm.stools.component.inputfields.osfilepicker.DeFilter;
import dtm.stools.component.inputfields.osfilepicker.OsFilePicker;
import dtm.stools.component.inputfields.textarea.TextAreaField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class RunFormUi {

    public static final int FIELD_HEIGHT = PillButtons.FIELD_HEIGHT;

    private RunFormUi() {
    }

    public static Page page() {
        return new Page();
    }

    public static FormSection section(String title, String subtitle) {
        return new FormSection(title, subtitle);
    }

    public static FormFieldCell field(String label, JComponent control) {
        return new FormFieldCell(label, sized(control));
    }

    public static FormFieldCell fieldWithButton(String label, JComponent control, JButton button) {
        JPanel row = new JPanel(new BorderLayout(UiTokens.space(1), 0));
        row.setOpaque(false);
        row.add(sized(control), BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        return new FormFieldCell(label, row);
    }

    public static MaskedTextField text(String placeholder) {
        MaskedTextField field = new MaskedTextField();
        field.setPlaceholder(placeholder == null ? "" : placeholder);
        field.setFont(UiTokens.font());
        return field;
    }

    public static JComboBox<String> combo(boolean editable) {
        JComboBox<String> combo = new JComboBox<>(new DefaultComboBoxModel<>());
        combo.setEditable(editable);
        combo.setFont(UiTokens.font());
        return combo;
    }

    public static TextAreaField textArea(String placeholder, int rows) {
        TextAreaField area = new TextAreaField();
        area.setPlaceholder(placeholder == null ? "" : placeholder);
        area.setRowRange(rows, Math.max(rows, 8));
        area.setAutoGrow(true);
        area.setShowCounter(false);
        area.getTextArea().setFont(UiTokens.fontMono());
        return area;
    }

    public static JCheckBox checkBox(String label) {
        JCheckBox box = new JCheckBox(label);
        box.setOpaque(false);
        box.setFont(UiTokens.font());
        box.setForeground(UiTokens.foreground());
        box.setFocusPainted(false);
        return box;
    }

    public static JButton browse(String tooltip, Runnable action) {
        JButton button = PillButtons.secondary("...", null);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(UiTokens.scale(38), UiTokens.scale(FIELD_HEIGHT)));
        button.addActionListener(event -> action.run());
        return button;
    }

    public static JButton action(String label, Icon icon, Runnable handler) {
        JButton button = PillButtons.secondary(label, icon);
        button.addActionListener(event -> handler.run());
        return button;
    }

    public static JComponent notice(String message) {
        JLabel label = new JLabel("<html><body style='width:100%'>" + escape(message) + "</body></html>");
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.warning());
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, UiTokens.scale(3), 0, 0, UiTokens.warning()),
                BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2),
                        UiTokens.space(1), UiTokens.space(1))));
        wrapper.add(label, BorderLayout.CENTER);
        return wrapper;
    }

    public static <T extends JComponent> T sized(T component) {
        if (component instanceof TextAreaField) {
            return component;
        }
        int height = UiTokens.scale(FIELD_HEIGHT);
        component.setPreferredSize(new Dimension(UiTokens.scale(180), height));
        component.setMinimumSize(new Dimension(UiTokens.scale(90), height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return component;
    }

    public static void fill(JComboBox<String> combo, List<String> values, String selected) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        values.forEach(model::addElement);
        combo.setModel(model);
        if (selected != null && !selected.isBlank()) {
            combo.setSelectedItem(selected);
        } else if (!values.isEmpty()) {
            combo.setSelectedIndex(0);
        } else {
            combo.setSelectedItem(null);
        }
    }

    public static String valueOf(JComboBox<String> combo) {
        Object selected = combo.isEditable() && combo.getEditor() != null
                ? combo.getEditor().getItem()
                : combo.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    public static void chooseFile(Component parent, String title, Path start,
                                  String extension, Consumer<Path> onChosen) {
        File selected;
        File initial = existing(start);
        if (extension == null || extension.isBlank()) {
            selected = OsFilePicker.openFile(title, initial);
        } else {
            DeFilter filter = DeFilter.of(extension.toUpperCase(java.util.Locale.ROOT), extension);
            selected = OsFilePicker.openFile(title, initial, filter);
        }
        if (selected != null) {
            onChosen.accept(selected.toPath());
        }
    }

    public static void chooseDirectory(Component parent, String title, Path start,
                                       Consumer<Path> onChosen) {
        File selected = OsFilePicker.openDirectory(title, existing(start));
        if (selected != null) {
            onChosen.accept(selected.toPath());
        }
    }

    private static File existing(Path start) {
        File file = start == null ? null : start.toFile();
        return file != null && file.exists() ? file : null;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    public static final class Page extends JPanel implements Scrollable {

        private Page() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(UiTokens.space(3), UiTokens.space(3),
                    UiTokens.space(3), UiTokens.space(3)));
        }

        public Page section(FormSection section) {
            section.setAlignmentX(LEFT_ALIGNMENT);
            if (getComponentCount() > 0) {
                add(Box.createVerticalStrut(UiTokens.space(2)));
            }
            add(section);
            return this;
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UiTokens.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL
                    ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
