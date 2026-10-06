package dtm.ide.run.form;

import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FormSection extends JPanel {

    private final JPanel body = new JPanel(new FormFlowLayout());
    private final Map<String, FormFieldCell> cells = new LinkedHashMap<>();

    FormSection(String title, String subtitle) {
        super(new BorderLayout(0, UiTokens.space(2)));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.space(3), UiTokens.space(3),
                UiTokens.space(3), UiTokens.space(3)));
        body.setOpaque(false);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(UiTokens.fontBold());
        titleLabel.setForeground(UiTokens.foreground());
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        header.add(titleLabel);

        if (subtitle != null && !subtitle.isBlank()) {
            header.add(Box.createVerticalStrut(UiTokens.space(1)));
            JLabel subtitleLabel = new JLabel(subtitle);
            subtitleLabel.setFont(UiTokens.fontSmall());
            subtitleLabel.setForeground(UiTokens.muted());
            subtitleLabel.setAlignmentX(LEFT_ALIGNMENT);
            header.add(subtitleLabel);
        }

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    public FormSection add(String name, FormFieldCell cell) {
        return place(name, cell, 1);
    }

    public FormSection addWide(String name, FormFieldCell cell) {
        return place(name, cell, 2);
    }

    private FormSection place(String name, FormFieldCell cell, int span) {
        FormFlowLayout.setSpan(cell, span);
        cells.put(name, cell);
        body.add(cell);
        return this;
    }

    public FormSection addComponent(JComponent component) {
        FormFlowLayout.setSpan(component, 2);
        body.add(component);
        return this;
    }

    public FormFieldCell cell(String name) {
        return cells.get(name);
    }

    public Map<String, FormFieldCell> cells() {
        return Map.copyOf(cells);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = UiTokens.radius(UiTokens.Radius.MD);
            g2.setColor(UiTokens.surface());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.setColor(UiTokens.border());
            g2.setStroke(new java.awt.BasicStroke(UiTokens.stroke()));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }
}
