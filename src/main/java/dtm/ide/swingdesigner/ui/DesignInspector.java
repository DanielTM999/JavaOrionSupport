package dtm.ide.swingdesigner.ui;

import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;

final class DesignInspector extends JPanel {

    static final String PROPERTIES = "Propriedades";
    static final String LAYOUT = "Layout";
    static final String EVENTS = "Eventos";

    private final ViewerInspector properties;
    private final LayoutPanel layout;
    private final EventsPanel events;
    private final JPanel cards = new JPanel(new CardLayout());
    private final ButtonGroup group = new ButtonGroup();
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.scale(2), 0));
    private String active = PROPERTIES;
    private Runnable tabListener = () -> { };

    DesignInspector(ViewerInspector properties, LayoutPanel layout, EventsPanel events) {
        super(new BorderLayout());
        this.properties = properties;
        this.layout = layout;
        this.events = events;
        setOpaque(false);
        tabs.setOpaque(false);
        tabs.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(1), UiTokens.space(1),
                        UiTokens.space(1))));
        cards.setOpaque(false);
        addTab(PROPERTIES, properties);
        addTab(LAYOUT, layout);
        addTab(EVENTS, events);
        add(tabs, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
    }

    String active() {
        return active;
    }

    void onTabChanged(Runnable listener) {
        tabListener = listener == null ? () -> { } : listener;
    }

    void showTab(String name) {
        active = name;
        ((CardLayout) cards.getLayout()).show(cards, name);
        for (var element = group.getElements(); element.hasMoreElements(); ) {
            var button = element.nextElement();
            button.setSelected(button.getText().equals(name));
        }
    }

    ViewerInspector properties() {
        return properties;
    }

    LayoutPanel layoutPanel() {
        return layout;
    }

    EventsPanel eventsPanel() {
        return events;
    }

    private void addTab(String name, JPanel content) {
        JToggleButton button = new JToggleButton(name, name.equals(active));
        button.setFocusable(false);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setFont(UiTokens.fontSmall());
        button.addActionListener(event -> {
            showTab(name);
            tabListener.run();
        });
        group.add(button);
        tabs.add(button);
        cards.add(content, name);
    }
}
