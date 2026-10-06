package dtm.ide.run.form;

import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

public final class FormFieldCell extends JPanel {

    private final JLabel label = new JLabel();
    private final JLabel message = new JLabel(" ");
    private final JComponent control;
    private String helperText = "";

    FormFieldCell(String labelText, JComponent control) {
        super(new BorderLayout(0, UiTokens.space(1)));
        this.control = control;
        setOpaque(false);

        label.setText(labelText);
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.muted());
        label.setLabelFor(control);

        message.setFont(UiTokens.fontSmall());
        message.setForeground(UiTokens.muted());

        add(label, BorderLayout.NORTH);
        add(control, BorderLayout.CENTER);
        add(message, BorderLayout.SOUTH);
    }

    public JComponent control() {
        return control;
    }

    public FormFieldCell helper(String text) {
        this.helperText = text == null ? "" : text;
        if (message.getForeground() != UiTokens.danger()) {
            showHelper();
        }
        return this;
    }

    public FormFieldCell tooltip(String text) {
        setToolTipText(text);
        control.setToolTipText(text);
        label.setToolTipText(text);
        return this;
    }

    public void setError(String text) {
        if (text == null || text.isBlank()) {
            clearError();
            return;
        }
        message.setForeground(UiTokens.danger());
        message.setText(text);
        control.setBorder(BorderFactory.createLineBorder(UiTokens.danger(), 1, true));
        revalidate();
        repaint();
    }

    public void clearError() {
        control.setBorder(null);
        showHelper();
        revalidate();
        repaint();
    }

    private void showHelper() {
        message.setForeground(UiTokens.muted());
        message.setText(helperText.isBlank() ? " " : helperText);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        preferred.width = UiTokens.scale(200);
        return preferred;
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension minimum = super.getMinimumSize();
        minimum.width = UiTokens.scale(180);
        return minimum;
    }
}
