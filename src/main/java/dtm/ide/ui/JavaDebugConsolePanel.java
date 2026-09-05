package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;
import dtm.stools.component.feedback.alert.AlertPanel;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.configs.UiTokens;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class JavaDebugConsolePanel extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final List<Entry> entries = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Time", "Event", "Message"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final BadgeLabel session = new BadgeLabel("Idle", BadgeLabel.Tone.NEUTRAL)
            .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);
    private final AlertPanel error = new AlertPanel(AlertPanel.Severity.ERROR,
            "Debug session failed", "").setClosable(true).setShowIcon(true);
    private final EmptyStatePanel empty = new EmptyStatePanel(
            "No debug events", "Run or debug the application to see session events here.")
            .setDashedBorder(false);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);

    public JavaDebugConsolePanel() {
        super(new BorderLayout(0, UiTokens.space(2)));
        setBackground(UiTokens.background());
        ToolBarPanel toolbar = new ToolBarPanel().setPaintSurface(true).setItemGap(UiTokens.space(2));
        JLabel title = new JLabel("Debug Console");
        title.setFont(UiTokens.fontBold());
        toolbar.addItem(title).addItem(session).addSpacer()
                .addAction("Copy All", null, this::copyAll)
                .addAction("Clear", null, this::clear);
        error.setVisible(false);
        JPanel top = new JPanel(new BorderLayout(0, UiTokens.space(2)));
        top.setOpaque(false);
        top.add(toolbar, BorderLayout.NORTH);
        top.add(error, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        styleTable();
        ScrollPanel scroll = new ScrollPanel(table).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(22);
        body.setOpaque(false);
        body.add(empty, "empty");
        body.add(scroll, "events");
        add(body, BorderLayout.CENTER);
        cards.show(body, "empty");
    }

    public void append(JavaDebugSnapshot.State state, String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> append(state, message));
            return;
        }
        if (message == null || message.isBlank()) {
            setState(state);
            return;
        }
        String normalized = message.strip();
        if (!entries.isEmpty()) {
            Entry last = entries.getLast();
            if (last.state() == state && last.message().equals(normalized)) {
                setState(state);
                return;
            }
        }
        Entry entry = new Entry(LocalTime.now().format(TIME), state, normalized);
        entries.add(entry);
        model.addRow(new Object[]{entry.time(), entry.state(), entry.message()});
        if (entries.size() > 1000) {
            entries.removeFirst();
            model.removeRow(0);
        }
        cards.show(body, "events");
        int last = model.getRowCount() - 1;
        if (last >= 0) {
            table.scrollRectToVisible(table.getCellRect(last, 0, true));
        }
        if (state == JavaDebugSnapshot.State.ERROR) {
            error.setMessage(message).restore();
            error.setVisible(true);
        }
        setState(state);
    }

    public void setState(JavaDebugSnapshot.State state) {
        JavaDebugSnapshot.State actual = state == null ? JavaDebugSnapshot.State.TERMINATED : state;
        session.setText(switch (actual) {
            case STARTING -> "Starting";
            case RUNNING -> "Running";
            case PAUSED -> "Paused";
            case TERMINATED -> "Finished";
            case ERROR -> "Error";
        });
        session.setTone(switch (actual) {
            case STARTING -> BadgeLabel.Tone.INFO;
            case RUNNING -> BadgeLabel.Tone.SUCCESS;
            case PAUSED -> BadgeLabel.Tone.WARNING;
            case TERMINATED -> BadgeLabel.Tone.NEUTRAL;
            case ERROR -> BadgeLabel.Tone.DANGER;
        });
    }

    public void clear() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::clear);
            return;
        }
        entries.clear();
        model.setRowCount(0);
        error.dismiss();
        error.setVisible(false);
        cards.show(body, "empty");
    }

    private void styleTable() {
        UiSupport.modernReadOnlyTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(88);
        table.getColumnModel().getColumn(0).setMaxWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setMaxWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(700);
        table.getColumnModel().getColumn(0).setCellRenderer(textRenderer(true));
        table.getColumnModel().getColumn(1).setCellRenderer(UiSupport.badgeColumn(
                JavaDebugConsolePanel::stateLabel, JavaDebugConsolePanel::stateTone));
        table.getColumnModel().getColumn(2).setCellRenderer(textRenderer(false));
    }

    private static TableCellRenderer textRenderer(boolean muted) {
        return UiSupport.styledColumn((cell, value, row, column) -> {
            cell.setFont(UiTokens.fontMono());
            cell.setForeground(muted ? UiTokens.muted() : UiTokens.foreground());
        });
    }

    private static String stateLabel(Object value) {
        return switch (state(value)) {
            case STARTING -> "Starting";
            case RUNNING -> "Running";
            case PAUSED -> "Paused";
            case TERMINATED -> "Finished";
            case ERROR -> "Error";
        };
    }

    private static BadgeLabel.Tone stateTone(Object value) {
        return switch (state(value)) {
            case STARTING -> BadgeLabel.Tone.INFO;
            case RUNNING -> BadgeLabel.Tone.SUCCESS;
            case PAUSED -> BadgeLabel.Tone.WARNING;
            case TERMINATED -> BadgeLabel.Tone.NEUTRAL;
            case ERROR -> BadgeLabel.Tone.DANGER;
        };
    }

    private static JavaDebugSnapshot.State state(Object value) {
        return value instanceof JavaDebugSnapshot.State actual
                ? actual : JavaDebugSnapshot.State.TERMINATED;
    }

    private void copyAll() {
        if (entries.isEmpty()) {
            return;
        }
        String content = entries.stream().map(entry -> entry.time() + "  "
                + entry.state().name() + "  " + entry.message())
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(content), null);
        } catch (RuntimeException ignored) {
        }
    }

    private record Entry(String time, JavaDebugSnapshot.State state, String message) {
    }
}
