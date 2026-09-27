package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;
import dtm.ide.lsp.JavaClassFileNavigation;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.feedback.tooltip.ModernTooltip;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.emptystate.EmptyStatePanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.component.panels.split.SplitPanel;
import dtm.stools.component.panels.toolbar.ToolBarPanel;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;

public final class JavaDebugPanel extends JPanel {

    public interface Host {
        void resume();

        void pause();

        void next();

        void stepIn();

        void stepOut();

        void stop();

        void hotReload();

        void openFile(Path file, int line);

        default void openLibrarySource(String uri, int line) {
        }

        JavaDebugSnapshot.Variable evaluate(String expression, int frameId) throws Exception;

        List<JavaDebugSnapshot.Variable> variables(int reference) throws Exception;

        List<JavaDebugSnapshot.Variable> variablesForFrame(int frameId) throws Exception;

        List<JavaDebugSnapshot.Scope> scopesForFrame(int frameId) throws Exception;

        void showEvaluate(int frameId);

        void selectThread(int threadId);
    }

    private final Host host;
    private final Executor executor;
    private String navigatedFrame;
    private boolean syncingSelection;
    private final DefaultListModel<JavaDebugSnapshot.ThreadInfo> threadModel = new DefaultListModel<>();
    private final DefaultListModel<JavaDebugSnapshot.StackFrame> frameModel = new DefaultListModel<>();
    private final JList<JavaDebugSnapshot.ThreadInfo> threads = new JList<>(threadModel);
    private final JList<JavaDebugSnapshot.StackFrame> frames = new JList<>(frameModel);
    private final JavaDebugValueTree variables;
    private final CopyOnWriteArrayList<String> watchExpressions = new CopyOnWriteArrayList<>();
    private final DefaultTableModel watchModel = new DefaultTableModel(
            new Object[]{"Expression", "Value", "Type"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable watches = new JTable(watchModel);
    private final JTextField watchField = new JTextField();
    private final JavaDebugConsolePanel console = new JavaDebugConsolePanel();
    private final BadgeLabel status = new BadgeLabel("Debugger idle", BadgeLabel.Tone.NEUTRAL)
            .setStyle(BadgeLabel.Style.SOFT).setShowDot(true).setSize(BadgeLabel.Size.SM);
    private final CardLayout watchCards = new CardLayout();
    private final JPanel watchBody = new JPanel(watchCards);
    private final EmptyStatePanel watchEmpty = new EmptyStatePanel(
            "No watch expressions",
            "Type an expression above and press Enter to track its value while paused.")
            .setDashedBorder(false);
    private final JButton resumeButton;
    private final JButton pauseButton;
    private final JButton nextButton;
    private final JButton stepInButton;
    private final JButton stepOutButton;
    private final JButton reloadButton;
    private final JButton evaluateButton;
    private final JButton stopButton;
    private volatile JavaDebugSnapshot snapshot = JavaDebugSnapshot.starting("Debugger idle");

    public JavaDebugPanel(Host host) {
        this(host, command -> Thread.startVirtualThread(command));
    }

    public JavaDebugPanel(Host host, Executor executor) {
        super(new BorderLayout());
        this.host = host;
        this.executor = executor == null
                ? command -> Thread.startVirtualThread(command) : executor;
        this.variables = new JavaDebugValueTree(this.executor);
        setBackground(JavaDebugTheme.panel());
        variables.bindChildrenProvider(host::variables);
        ToolBarPanel toolbar = new ToolBarPanel().setPaintSurface(true)
                .setItemGap(UiTokens.space(2));
        JLabel title = new JLabel("Debug");
        title.setForeground(JavaDebugTheme.text());
        title.setFont(JavaDebugTheme.ui().deriveFont(Font.BOLD, 12f));
        toolbar.addItem(title).addSeparator();
        resumeButton = addButton(toolbar, "Continue", "F5", host::resume, JavaDebugTheme.number());
        pauseButton = addButton(toolbar, "Pause", "F6", host::pause, JavaDebugTheme.accent());
        toolbar.addSeparator();
        nextButton = addButton(toolbar, "Step Over", "F10", host::next, JavaDebugTheme.accent());
        stepInButton = addButton(toolbar, "Step Into", "F11", host::stepIn, JavaDebugTheme.accent());
        stepOutButton = addButton(toolbar, "Step Out", "Shift+F11", host::stepOut, JavaDebugTheme.accent());
        toolbar.addSeparator();
        reloadButton = addButton(toolbar, "Hot Reload", "Ctrl+F5", host::hotReload, JavaDebugTheme.number());
        evaluateButton = addButton(toolbar, "Evaluate", "Alt+F8",
                () -> host.showEvaluate(selectedFrameId()), JavaDebugTheme.object());
        toolbar.addSeparator();
        stopButton = addButton(toolbar, "Stop", "Shift+F5", host::stop, JavaDebugTheme.nil());
        add(toolbar, BorderLayout.NORTH);
        add(tabs(), BorderLayout.CENTER);
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.space(2), UiTokens.space(1)));
        statusBar.setBackground(JavaDebugTheme.header());
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, JavaDebugTheme.border()));
        statusBar.add(status);
        add(statusBar, BorderLayout.SOUTH);
        installInteractions();
        updateControls(JavaDebugSnapshot.State.STARTING);
    }

    public void update(JavaDebugSnapshot value) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> update(value));
            return;
        }
        JavaDebugSnapshot previous = snapshot;
        snapshot = value;
        if (value.state() == JavaDebugSnapshot.State.STARTING) {
            console.clear();
        }
        console.append(value.state(), value.message());
        boolean threadsChanged = replace(threadModel, value.threads());
        boolean framesChanged = replace(frameModel, value.frames());
        syncingSelection = true;
        try {
            if (!value.threads().isEmpty()
                    && (threadsChanged || previous.threadId() != value.threadId())) {
                int selected = 0;
                for (int index = 0; index < value.threads().size(); index++) {
                    if (value.threads().get(index).id() == value.threadId()) {
                        selected = index;
                        break;
                    }
                }
                threads.setSelectedIndex(selected);
            }
            if (framesChanged && !value.frames().isEmpty()) {
                frames.setSelectedIndex(0);
            }
        } finally {
            syncingSelection = false;
        }
        if (!previous.scopes().equals(value.scopes())
                || !previous.variables().equals(value.variables())) {
            if (!value.scopes().isEmpty()) {
                variables.setScopes(value.scopes());
            } else if (!value.variables().isEmpty()) {
                variables.setScopes(List.of(new JavaDebugSnapshot.Scope("Locals", 0, value.variables())));
            } else {
                variables.clear();
            }
        }
        if (value.state() == JavaDebugSnapshot.State.TERMINATED
                || value.state() == JavaDebugSnapshot.State.ERROR) {
            clearWatchValues();
        } else if (framesChanged) {
            refreshWatches();
        }
        status.setText(value.message().isBlank() ? value.state().name() : value.message().trim());
        status.setTone(statusTone(value.state()));
        updateControls(value.state());
        if (value.state() != JavaDebugSnapshot.State.PAUSED || value.frames().isEmpty()) {
            if (value.state() != JavaDebugSnapshot.State.PAUSED) {
                navigatedFrame = null;
            }
            return;
        }
        JavaDebugSnapshot.StackFrame top = value.frames().getFirst();
        String key = value.threadId() + ":" + top.id() + ":" + top.line();
        if (!key.equals(navigatedFrame)) {
            navigatedFrame = key;
            openFrame(top);
        }
    }

    private void openFrame(JavaDebugSnapshot.StackFrame frame) {
        if (frame.source() != null) {
            host.openFile(frame.source(), frame.line());
        } else if (frame.hasLibrarySource()) {
            host.openLibrarySource(frame.sourceUri(), frame.line());
        }
    }

    public void addWatch(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        String normalized = expression.trim();
        if (!watchExpressions.addIfAbsent(normalized)) {
            return;
        }
        watchModel.addRow(new Object[]{normalized, "—", ""});
        watchCards.show(watchBody, "table");
        refreshWatches();
    }

    private JTabbedPane tabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.addTab("Variables", variables);
        tabs.addTab("Watches", watchPanel());
        tabs.addTab("Threads & Call Stack", callStackPanel());
        tabs.addTab("Debug Console", console);
        tabs.setPreferredSize(new Dimension(980, 310));
        return tabs;
    }

    private JPanel callStackPanel() {
        styleList(threads);
        styleList(frames);
        CardPanel threadCard = new CardPanel("Threads", "Choose the suspended execution thread")
                .setVariant(CardPanel.Variant.FILLED).setContent(scroll(threads));
        CardPanel frameCard = new CardPanel("Call Stack", "Double-click a frame to open its source")
                .setVariant(CardPanel.Variant.FILLED).setContent(scroll(frames));
        SplitPanel split = new SplitPanel(JSplitPane.HORIZONTAL_SPLIT, threadCard, frameCard)
                .setDividerThickness(UiTokens.space(2)).setCollapseOnDoubleClick(true);
        split.setResizeWeight(0.32);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(JavaDebugTheme.content());
        panel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.space(2), UiTokens.space(2), UiTokens.space(2), UiTokens.space(2)));
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel watchPanel() {
        styleWatches();
        watchField.setFont(JavaDebugTheme.mono().deriveFont(12f));
        watchField.setBackground(JavaDebugTheme.stripe());
        watchField.setForeground(JavaDebugTheme.text());
        watchField.setCaretColor(JavaDebugTheme.text());
        watchField.setToolTipText("Add watch expression and press Enter");
        watchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JavaDebugTheme.border()),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(watchField, BorderLayout.NORTH);
        watchBody.setOpaque(false);
        watchBody.add(watchEmpty, "empty");
        watchBody.add(scroll(watches), "table");
        watchCards.show(watchBody, "empty");
        panel.add(watchBody, BorderLayout.CENTER);
        return panel;
    }

    private void styleWatches() {
        UiSupport.modernReadOnlyTable(watches);
        watches.setBackground(JavaDebugTheme.content());
        watches.setForeground(JavaDebugTheme.text());
        watches.getTableHeader().setForeground(JavaDebugTheme.muted());
        watches.getColumnModel().getColumn(0).setPreferredWidth(220);
        watches.getColumnModel().getColumn(1).setPreferredWidth(360);
        watches.getColumnModel().getColumn(2).setPreferredWidth(180);
        for (int column = 0; column < 3; column++) {
            watches.getColumnModel().getColumn(column).setCellRenderer(watchRenderer(column));
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem remove = new JMenuItem("Remove watch");
        remove.addActionListener(event -> removeSelectedWatch());
        menu.add(remove);
        watches.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showWatchMenu(event, menu);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showWatchMenu(event, menu);
            }
        });
    }

    private TableCellRenderer watchRenderer(int column) {
        return UiSupport.styledColumn((cell, value, row, ignored) -> {
            cell.setFont(column == 0 ? JavaDebugTheme.ui().deriveFont(Font.BOLD, 12f)
                    : JavaDebugTheme.mono().deriveFont(12f));
            if (column == 0) {
                cell.setForeground(JavaDebugTheme.name());
            } else if (column == 1) {
                Object type = watchModel.getValueAt(row, 2);
                cell.setForeground(JavaDebugTheme.valueFor(
                        value == null ? "" : value.toString(),
                        type == null ? "" : type.toString()));
            } else {
                cell.setForeground(JavaDebugTheme.muted());
            }
        });
    }

    private void installInteractions() {
        watchField.addActionListener(event -> {
            addWatch(watchField.getText());
            watchField.setText("");
        });
        frames.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    JavaDebugSnapshot.StackFrame frame = frames.getSelectedValue();
                    if (frame != null) {
                        openFrame(frame);
                    }
                }
            }
        });
        frames.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !syncingSelection) {
                JavaDebugSnapshot.StackFrame frame = frames.getSelectedValue();
                if (frame != null) {
                    loadFrame(frame.id());
                }
            }
        });
        threads.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !syncingSelection) {
                JavaDebugSnapshot.ThreadInfo thread = threads.getSelectedValue();
                if (thread != null && thread.id() != snapshot.threadId()) {
                    host.selectThread(thread.id());
                }
            }
        });
        threads.setCellRenderer(new StackRowRenderer<>(true,
                thread -> cleanThreadName(thread.name()), thread -> "Thread #" + thread.id()));
        frames.setCellRenderer(new StackRowRenderer<>(false,
                JavaDebugSnapshot.StackFrame::name, JavaDebugPanel::frameLocation));
    }

    static String frameLocation(JavaDebugSnapshot.StackFrame frame) {
        if (frame.source() != null) {
            return frame.source().getFileName() + ":" + frame.line();
        }
        return frame.hasLibrarySource()
                ? JavaClassFileNavigation.sourceFileName(frame.sourceUri()) + ":" + frame.line()
                : "";
    }

    static final class StackRowRenderer<T> extends JPanel implements ListCellRenderer<T> {
        private final JLabel primary = new JLabel();
        private final JLabel secondary = new JLabel();
        private final Function<T, String> primaryText;
        private final Function<T, String> secondaryText;

        StackRowRenderer(boolean boldPrimary, Function<T, String> primaryText,
                         Function<T, String> secondaryText) {
            super(new BorderLayout(UiTokens.space(3), 0));
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            Font mono = JavaDebugTheme.mono().deriveFont(12f);
            primary.setFont(boldPrimary ? mono.deriveFont(Font.BOLD) : mono);
            primary.setForeground(JavaDebugTheme.text());
            secondary.setFont(mono);
            secondary.setForeground(JavaDebugTheme.muted());
            add(primary, BorderLayout.WEST);
            add(secondary, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends T> list, T value, int index,
                                                      boolean selected, boolean focus) {
            primary.setText(value == null ? "" : primaryText.apply(value));
            secondary.setText(value == null ? "" : secondaryText.apply(value));
            setBackground(selected ? JavaDebugTheme.selection()
                    : index % 2 == 0 ? JavaDebugTheme.content() : JavaDebugTheme.stripe());
            return this;
        }
    }

    private void loadFrame(int frameId) {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return host.scopesForFrame(frameId);
            } catch (Exception error) {
                return List.<JavaDebugSnapshot.Scope>of();
            }
        }, executor).thenAccept(values -> SwingUtilities.invokeLater(() -> {
            variables.setScopes(values);
            refreshWatches(frameId);
        }));
    }

    private void refreshWatches() {
        refreshWatches(selectedFrameId());
    }

    private void refreshWatches(int frameId) {
        if (snapshot.state() != JavaDebugSnapshot.State.PAUSED || frameId <= 0) {
            return;
        }
        List<String> expressions = List.copyOf(watchExpressions);
        for (int index = 0; index < expressions.size(); index++) {
            int row = index;
            String expression = expressions.get(index);
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return host.evaluate(expression, frameId);
                } catch (Exception error) {
                    return new JavaDebugSnapshot.Variable(expression,
                            "<" + safeMessage(error) + ">", "", 0);
                }
            }, executor).thenAccept(value -> SwingUtilities.invokeLater(() -> {
                if (row < watchModel.getRowCount()
                        && expression.equals(watchModel.getValueAt(row, 0))) {
                    watchModel.setValueAt(value == null ? "—" : value.value(), row, 1);
                    watchModel.setValueAt(value == null ? "" : value.type(), row, 2);
                }
            }));
        }
    }

    private void clearWatchValues() {
        for (int row = 0; row < watchModel.getRowCount(); row++) {
            watchModel.setValueAt("—", row, 1);
            watchModel.setValueAt("", row, 2);
        }
    }

    private void removeSelectedWatch() {
        int row = watches.getSelectedRow();
        if (row < 0 || row >= watchExpressions.size()) {
            return;
        }
        watchExpressions.remove(row);
        watchModel.removeRow(row);
        if (watchExpressions.isEmpty()) {
            watchCards.show(watchBody, "empty");
        }
    }

    private void showWatchMenu(MouseEvent event, JPopupMenu menu) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = watches.rowAtPoint(event.getPoint());
        if (row >= 0) {
            watches.setRowSelectionInterval(row, row);
            menu.show(watches, event.getX(), event.getY());
        }
    }

    private void updateControls(JavaDebugSnapshot.State state) {
        boolean active = state != JavaDebugSnapshot.State.TERMINATED
                && state != JavaDebugSnapshot.State.ERROR;
        boolean paused = state == JavaDebugSnapshot.State.PAUSED;
        resumeButton.setEnabled(paused);
        pauseButton.setEnabled(state == JavaDebugSnapshot.State.RUNNING);
        nextButton.setEnabled(paused);
        stepInButton.setEnabled(paused);
        stepOutButton.setEnabled(paused);
        evaluateButton.setEnabled(paused);
        reloadButton.setEnabled(active && state != JavaDebugSnapshot.State.STARTING);
        stopButton.setEnabled(active);
    }

    private JButton addButton(ToolBarPanel toolbar, String text, String shortcut, Runnable action,
                              Color foreground) {
        JButton button = new JButton(text);
        button.setForeground(foreground);
        button.setFont(JavaDebugTheme.ui().deriveFont(Font.BOLD, 12f));
        button.setFocusable(false);
        button.setPreferredSize(new Dimension(Math.max(76, text.length() * 8 + 24), 28));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JavaDebugTheme.border()),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBackground(JavaDebugTheme.header());
        button.addActionListener(event -> action.run());
        ModernTooltip.install(button, text + "  ·  " + shortcut)
                .setPlacement(ModernTooltip.Placement.BOTTOM);
        toolbar.addItem(button);
        return button;
    }

    private static void styleList(JList<?> list) {
        list.setBackground(JavaDebugTheme.content());
        list.setForeground(JavaDebugTheme.text());
        list.setSelectionBackground(JavaDebugTheme.selection());
        list.setSelectionForeground(JavaDebugTheme.text());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(24);
        list.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
    }

    private static ScrollPanel scroll(JComponent component) {
        return new ScrollPanel(component).setScrollBarThickness(8)
                .setPaintTrack(false).setUnitIncrement(24);
    }

    private static BadgeLabel.Tone statusTone(JavaDebugSnapshot.State state) {
        return switch (state) {
            case STARTING -> BadgeLabel.Tone.INFO;
            case RUNNING -> BadgeLabel.Tone.SUCCESS;
            case PAUSED -> BadgeLabel.Tone.WARNING;
            case TERMINATED -> BadgeLabel.Tone.NEUTRAL;
            case ERROR -> BadgeLabel.Tone.DANGER;
        };
    }

    private int selectedFrameId() {
        JavaDebugSnapshot.StackFrame selected = frames.getSelectedValue();
        return selected != null ? selected.id()
                : snapshot.frames().isEmpty() ? 0 : snapshot.frames().getFirst().id();
    }

    private static String cleanThreadName(String name) {
        if (name == null || name.isBlank()) {
            return "Thread";
        }
        String value = name.replaceFirst("^Thread\\s*\\[", "").replaceFirst("]$", "");
        return value.isBlank() ? "Thread" : value;
    }

    private static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null
                ? "evaluation failed" : error.getMessage();
    }

    static <T> boolean replace(DefaultListModel<T> model, List<T> values) {
        List<T> next = values == null ? List.of() : values;
        if (model.size() == next.size() && Collections.list(model.elements()).equals(next)) {
            return false;
        }
        model.clear();
        model.addAll(next);
        return true;
    }
}
