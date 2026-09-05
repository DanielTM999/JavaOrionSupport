package dtm.ide.ui;

import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;

public final class JavaDeleteDialogPanel extends JPanel {

    private static final int MAX_LISTED_PATHS = 12;

    private static String text(String key, String defaultValue) {
        return I18n.getText(JavaDeleteDialogPanel.class, key, defaultValue);
    }

    private final JCheckBox safeDelete;

    public JavaDeleteDialogPanel(List<Path> paths, Path projectRoot, boolean safeDeleteEnabled,
                                 boolean safeDeleteAvailable) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        setOpaque(false);

        add(align(new JLabel(headline(paths))));
        add(Box.createVerticalStrut(8));

        JComponent details = details(paths, projectRoot);
        if (details != null) {
            add(align(details));
            add(Box.createVerticalStrut(10));
        }

        safeDelete = new JCheckBox(text("safeDelete", "Safe delete (with usage search)"));
        safeDelete.setSelected(safeDeleteAvailable && safeDeleteEnabled);
        safeDelete.setEnabled(safeDeleteAvailable);
        safeDelete.setOpaque(false);
        safeDelete.setToolTipText(text("safeDelete.tooltip",
                "Searches for usages before deleting and warns when the code is still referenced."));
        add(align(safeDelete));
    }

    public boolean isSafeDeleteSelected() {
        return safeDelete.isSelected() && safeDelete.isEnabled();
    }

    private static String headline(List<Path> paths) {
        if (paths.size() == 1) {
            Path path = paths.getFirst();
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            return text("question.single", "Delete") + " \"" + name + "\"?";
        }
        return text("question.manyPrefix", "Delete ") + paths.size()
                + text("question.manySuffix", " selected items?");
    }

    private static JComponent details(List<Path> paths, Path projectRoot) {
        if (paths.size() == 1) {
            JLabel label = new JLabel(display(paths.getFirst(), projectRoot));
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
            label.setEnabled(false);
            return label;
        }

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);

        int shown = Math.min(paths.size(), MAX_LISTED_PATHS);
        for (int index = 0; index < shown; index++) {
            JLabel label = new JLabel(display(paths.get(index), projectRoot));
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
            label.setEnabled(false);
            list.add(align(label));
        }
        if (paths.size() > shown) {
            JLabel more = new JLabel("… " + (paths.size() - shown)
                    + text("more", " more"));
            more.setFont(more.getFont().deriveFont(Font.ITALIC, 11f));
            more.setEnabled(false);
            list.add(align(more));
        }

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(380, Math.min(160, shown * 20 + 8)));
        return scroll;
    }

    private static String display(Path path, Path projectRoot) {
        Path normalized = path.toAbsolutePath().normalize();
        if (projectRoot != null && normalized.startsWith(projectRoot)) {
            Path relative = projectRoot.relativize(normalized);
            return relative.toString().isBlank() ? normalized.toString() : relative.toString();
        }
        return normalized.toString();
    }

    private static <T extends JComponent> T align(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
