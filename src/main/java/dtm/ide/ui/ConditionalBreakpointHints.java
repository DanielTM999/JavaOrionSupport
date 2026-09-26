package dtm.ide.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;

public final class ConditionalBreakpointHints {

    static final int MAX_VISIBLE = 8;

    private ConditionalBreakpointHints() {
    }

    public static JComponent chips(String label, List<String> names, Consumer<String> onPick) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel caption = new JLabel(label);
        caption.setFont(UiTokens.fontSmall());
        caption.setForeground(UiTokens.muted());
        row.add(caption);
        row.add(Box.createHorizontalStrut(UiTokens.space(2)));
        List<String> values = names == null ? List.of() : names;
        for (String name : values.stream().limit(MAX_VISIBLE).toList()) {
            row.add(chip(name, onPick));
            row.add(Box.createHorizontalStrut(UiTokens.space(1)));
        }
        String overflow = overflow(values);
        if (!overflow.isEmpty()) {
            JLabel more = new JLabel(overflow);
            more.setFont(UiTokens.fontSmall());
            more.setForeground(UiTokens.muted());
            more.setToolTipText(String.join(", ", values.subList(MAX_VISIBLE, values.size())));
            row.add(more);
        }
        return row;
    }

    static String overflow(List<String> names) {
        return names == null || names.size() <= MAX_VISIBLE ? "" : "+" + (names.size() - MAX_VISIBLE);
    }

    private static JButton chip(String name, Consumer<String> onPick) {
        JButton chip = PillButtons.ghost(name, null);
        chip.setFont(UiTokens.fontMono().deriveFont(UiTokens.fontSmall().getSize2D()));
        chip.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2),
                UiTokens.space(1), UiTokens.space(2)));
        chip.setFocusable(false);
        chip.setToolTipText(name);
        if (onPick == null) {
            chip.setEnabled(false);
        } else {
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.addActionListener(event -> onPick.accept(name));
        }
        return chip;
    }
}
