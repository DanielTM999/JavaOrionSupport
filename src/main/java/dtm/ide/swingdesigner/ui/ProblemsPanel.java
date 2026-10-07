package dtm.ide.swingdesigner.ui;

import dtm.ide.swingdesigner.runtime.ViewWarning;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

final class ProblemsPanel extends JPanel {

    private final JPanel header = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel("Problemas");
    private final JLabel errorCount = new JLabel();
    private final JLabel infoCount = new JLabel();
    private final JLabel toggleHint = new JLabel();
    private final JPanel rows = new JPanel();
    private final JScrollPane scroll;
    private Function<String, Optional<Path>> sources = className -> Optional.empty();
    private BiConsumer<Path, Integer> opener = (path, line) -> { };
    private Consumer<Boolean> expansionListener = expanded -> { };
    private List<ViewWarning> warnings = List.of();
    private boolean expanded;

    ProblemsPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(UiTokens.surface());
        header.setOpaque(true);
        header.setBackground(UiTokens.surface());
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.scale(5), UiTokens.space(2), UiTokens.scale(5),
                        UiTokens.space(2))));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.setFont(UiTokens.fontBold());
        errorCount.setIcon(new WarningIcon(true));
        infoCount.setIcon(new WarningIcon(false));
        for (JLabel label : List.of(errorCount, infoCount, toggleHint)) {
            label.setFont(UiTokens.fontSmall());
            label.setForeground(UiTokens.muted());
            label.setIconTextGap(UiTokens.scale(4));
        }
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), 0));
        left.setOpaque(false);
        left.add(title);
        left.add(errorCount);
        left.add(infoCount);
        header.add(left, BorderLayout.WEST);
        header.add(toggleHint, BorderLayout.EAST);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                setExpanded(!expanded);
            }
        });

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(rows, BorderLayout.NORTH);
        scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        scroll.setVisible(false);
        refreshHeader();
    }

    void configure(Function<String, Optional<Path>> sources, BiConsumer<Path, Integer> opener,
                   Consumer<Boolean> expansionListener) {
        this.sources = sources == null ? className -> Optional.empty() : sources;
        this.opener = opener == null ? (path, line) -> { } : opener;
        this.expansionListener = expansionListener == null ? value -> { } : expansionListener;
    }

    boolean isExpanded() {
        return expanded;
    }

    int headerHeight() {
        return header.getPreferredSize().height;
    }

    long errors() {
        return warnings.stream().filter(ViewWarning::isError).count();
    }

    long infos() {
        return warnings.size() - errors();
    }

    boolean isEmpty() {
        return warnings.isEmpty();
    }

    void setExpanded(boolean value) {
        if (expanded == value) {
            return;
        }
        expanded = value;
        scroll.setVisible(value);
        refreshHeader();
        revalidate();
        repaint();
        expansionListener.accept(value);
    }

    void show(List<ViewWarning> items) {
        warnings = items == null ? List.of() : List.copyOf(items);
        rows.removeAll();
        warnings.stream()
                .sorted((left, right) -> Boolean.compare(right.isError(), left.isError()))
                .forEach(warning -> rows.add(row(warning)));
        refreshHeader();
        rows.revalidate();
        rows.repaint();
        javax.swing.SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
    }

    private void refreshHeader() {
        long errors = errors();
        long infos = infos();
        errorCount.setText(errors + (errors == 1 ? " erro" : " erros"));
        errorCount.setVisible(errors > 0);
        infoCount.setText(infos + (infos == 1 ? " aviso" : " avisos"));
        infoCount.setVisible(infos > 0);
        toggleHint.setText(warnings.isEmpty() ? "Nenhum problema" : expanded ? "Ocultar" : "Mostrar");
    }

    private JPanel row(ViewWarning warning) {
        Color tint = warning.isError() ? WarningIcon.AMBER : WarningIcon.BLUE;
        JPanel row = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.overlay(UiTokens.border(), 0.6F)),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 3, 0, 0, UiTokens.overlay(tint, 0.8F)),
                        BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2), UiTokens.space(1),
                                UiTokens.space(2)))));
        JLabel icon = new JLabel(new WarningIcon(warning.isError()));
        icon.setVerticalAlignment(JLabel.TOP);
        row.add(icon, BorderLayout.WEST);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(text(warning.message() == null ? "" : warning.message(), false));
        if (warning.hint() != null) {
            content.add(text(warning.hint(), true));
        }
        JTextArea stack = new JTextArea(warning.stack() == null ? "" : warning.stack());
        stack.setEditable(false);
        stack.setHighlighter(null);
        stack.setFont(UiTokens.fontMono());
        stack.setForeground(UiTokens.muted());
        stack.setOpaque(false);
        stack.setVisible(false);
        stack.setAlignmentX(LEFT_ALIGNMENT);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        location(warning).ifPresent(target -> {
            JButton go = link("Ir para " + target.frame().file() + ":" + target.frame().line());
            go.setToolTipText(target.frame().label());
            go.addActionListener(event -> opener.accept(target.path(), target.frame().line()));
            actions.add(go);
        });
        if (warning.stack() != null && !warning.stack().isBlank()) {
            gap(actions);
            JButton details = link("Detalhes");
            details.addActionListener(event -> {
                stack.setVisible(!stack.isVisible());
                details.setText(stack.isVisible() ? "Ocultar detalhes" : "Detalhes");
                revalidate();
                repaint();
            });
            actions.add(details);
        }
        gap(actions);
        JButton copy = link("Copiar");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(copyText(warning)), null));
        actions.add(copy);
        content.add(actions);
        content.add(stack);
        row.add(content, BorderLayout.CENTER);
        return row;
    }

    private static void gap(JPanel actions) {
        if (actions.getComponentCount() > 0) {
            actions.add(Box.createHorizontalStrut(UiTokens.space(3)));
        }
    }

    private static String copyText(ViewWarning warning) {
        StringBuilder text = new StringBuilder(warning.text());
        if (warning.hint() != null) {
            text.append(System.lineSeparator()).append(warning.hint());
        }
        if (warning.stack() != null) {
            text.append(System.lineSeparator()).append(warning.stack());
        }
        return text.toString();
    }

    private Optional<Target> location(ViewWarning warning) {
        for (ViewWarning.Frame frame : warning.frames()) {
            if (frame.line() <= 0 || frame.file() == null) {
                continue;
            }
            Optional<Path> path = sources.apply(frame.outerClassName());
            if (path.isPresent()) {
                return Optional.of(new Target(frame, path.get()));
            }
        }
        return Optional.empty();
    }

    private static JTextArea text(String value, boolean muted) {
        JTextArea area = new JTextArea(value);
        area.setEditable(false);
        area.setHighlighter(null);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(muted ? UiTokens.fontSmall() : UiTokens.font());
        area.setForeground(muted ? UiTokens.muted() : UiTokens.foreground());
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

    private static JButton link(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder(UiTokens.scale(2), 0, UiTokens.scale(2), 0));
        button.setForeground(linkColor());
        button.setFont(UiTokens.fontSmall());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static Color linkColor() {
        Color accent = UiTokens.accent();
        if (!UiTokens.isDarkTheme()) {
            return accent;
        }
        float mix = 0.45f;
        return new Color(Math.round(accent.getRed() + (255 - accent.getRed()) * mix),
                Math.round(accent.getGreen() + (255 - accent.getGreen()) * mix),
                Math.round(accent.getBlue() + (255 - accent.getBlue()) * mix));
    }

    private record Target(ViewWarning.Frame frame, Path path) {
    }
}
