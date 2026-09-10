package dtm.ide.ui;

import dtm.ide.deps.DependencyVersionChoice;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;

final class DependencyVersionChoiceRenderer extends JPanel
        implements ListCellRenderer<DependencyVersionChoice> {

    private final JLabel version = new JLabel();
    private final BadgeLabel local = new BadgeLabel(
            I18n.getText(DependencyManagerPanel.class, "badge.local", "local"),
            BadgeLabel.Tone.SUCCESS);

    DependencyVersionChoiceRenderer() {
        super(new BorderLayout(UiTokens.space(2), 0));
        setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2),
                UiTokens.space(1), UiTokens.space(2)));
        local.setStyle(BadgeLabel.Style.SOFT);
        local.setSize(BadgeLabel.Size.SM);
        local.setShowDot(true);
        add(version, BorderLayout.CENTER);
        add(local, BorderLayout.EAST);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends DependencyVersionChoice> list,
                                                   DependencyVersionChoice value, int index,
                                                   boolean selected, boolean cellHasFocus) {
        version.setText(value == null ? "" : value.version());
        local.setVisible(value != null && value.local());
        setOpaque(true);
        setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        version.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
        return this;
    }
}
