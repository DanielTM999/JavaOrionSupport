package dtm.ide.ui;

import dtm.ide.build.BuildToolModel;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.tree.TreePopupContext;
import dtm.stools.component.tree.TreeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeNode;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaBuildToolsPanelTest {

    @BeforeEach
    void requireGraphicsEnvironment() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "TreeView requires a graphics environment");
    }

    @Test
    void clearingTheFilterRestoresAllBuildOptions() throws Exception {
        BuildToolModel model = model("demo");
        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(hostReturning(model)));
        TreeView<?> tree = find(panel, TreeView.class);
        MaskedTextField filter = find(panel, MaskedTextField.class);
        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 3);

        onEdtRun(() -> filter.setText("clean"));
        await(() -> rootChildren(tree) == 1);
        onEdtRun(() -> filter.setText(""));
        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 3);

        onEdtRun(() -> filter.setText("missing-task"));
        await(() -> rootChildren(tree) == 0);
        onEdtRun(() -> filter.setText(""));

        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 3);
        assertEquals("demo", firstRootChild(tree).toString());
    }

    @Test
    void refreshingKeepsTheTaskTreeVisible() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        JavaBuildToolsPanel.Host host = new JavaBuildToolsPanel.Host() {
            @Override
            public BuildToolModel load() {
                return model(loads.incrementAndGet() == 1 ? "first" : "second");
            }

            @Override
            public void execute(BuildToolModel.Node command) {
            }

            @Override
            public void cancel() {
            }
        };
        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(host));
        TreeView<?> tree = find(panel, TreeView.class);
        await(() -> rootChildren(tree) == 1 && "first".equals(String.valueOf(firstRootChild(tree))));
        int rowsBefore = visibleRows(tree);
        assertTrue(rowsBefore >= 3, "the first load should render the task tree");

        onEdtRun(panel::reload);
        await(() -> rootChildren(tree) == 1 && "second".equals(String.valueOf(firstRootChild(tree))));

        assertEquals(rowsBefore, visibleRows(tree), "refreshing must not blank the task tree");
    }

    @Test
    void filteringKeepsMatchingTasksVisible() throws Exception {
        BuildToolModel model = model("demo");
        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(hostReturning(model)));
        TreeView<?> tree = find(panel, TreeView.class);
        MaskedTextField filter = find(panel, MaskedTextField.class);
        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 3);

        onEdtRun(() -> filter.setText("clean"));
        flushEdt();
        assertTrue(visibleRows(tree) >= 3, "a matching filter must not blank the task tree");

        onEdtRun(() -> filter.setText(""));
        flushEdt();
        assertTrue(visibleRows(tree) >= 3, "clearing the filter must not blank the task tree");
    }

    @Test
    void anOlderRefreshCannotClearTheNewestResult() throws Exception {
        BuildToolModel model = model("newest");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch oldRefreshStarted = new CountDownLatch(1);
        CountDownLatch releaseOldRefresh = new CountDownLatch(1);
        CountDownLatch oldRefreshReturned = new CountDownLatch(1);
        CountDownLatch newestRefreshReturned = new CountDownLatch(1);
        JavaBuildToolsPanel.Host host = new JavaBuildToolsPanel.Host() {
            @Override
            public BuildToolModel load() {
                int load = loads.incrementAndGet();
                if (load == 2) {
                    oldRefreshStarted.countDown();
                    awaitLatch(releaseOldRefresh);
                    oldRefreshReturned.countDown();
                    return new BuildToolModel("Maven", List.of(), List.of());
                }
                if (load == 3) {
                    newestRefreshReturned.countDown();
                }
                return model;
            }

            @Override
            public void execute(BuildToolModel.Node command) {
            }

            @Override
            public void cancel() {
            }
        };

        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(host));
        TreeView<?> tree = find(panel, TreeView.class);
        await(() -> rootChildren(tree) == 1);

        onEdtRun(panel::reload);
        assertTrue(oldRefreshStarted.await(2, TimeUnit.SECONDS));
        onEdtRun(panel::reload);
        assertTrue(newestRefreshReturned.await(2, TimeUnit.SECONDS));
        await(() -> rootChildren(tree) == 1);

        releaseOldRefresh.countDown();
        assertTrue(oldRefreshReturned.await(2, TimeUnit.SECONDS));
        flushEdt();
        assertEquals(1, rootChildren(tree));
        assertEquals("newest", firstRootChild(tree).toString());
    }

    @Test
    void executingSeveralSelectedGoalsRunsThemTogetherInTreeOrder() throws Exception {
        AtomicReference<List<String>> executed = new AtomicReference<>();
        BuildToolModel model = modelWithCommands("demo", "clean", "compile", "install");
        JavaBuildToolsPanel.Host host = new JavaBuildToolsPanel.Host() {
            @Override
            public BuildToolModel load() {
                return model;
            }

            @Override
            public void execute(BuildToolModel.Node command) {
            }

            @Override
            public void executeGoals(BuildToolModel.Node context, List<String> goals) {
                executed.set(goals);
            }

            @Override
            public void cancel() {
            }
        };
        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(host));
        TreeView<BuildToolModel.Node> tree = find(panel, TreeView.class);
        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 5);

        onEdtRun(() -> {
            dtm.stools.component.tree.TreeNode<BuildToolModel.Node> project =
                    tree.getRootNode().getChildrenList().getFirst();
            dtm.stools.component.tree.TreeNode<BuildToolModel.Node> lifecycle =
                    project.getChildrenList().getFirst();
            tree.selectNodes(List.of(lifecycle.getChildrenList().get(2),
                    lifecycle.getChildrenList().getFirst(), lifecycle.getChildrenList().get(1)));
            findButton(panel, "Executar").doClick();
        });

        assertEquals(List.of("clean", "compile", "install"), executed.get());
    }

    @Test
    void theContextMenuRunsTheWholeSelectionJustLikeThePlayButton() throws Exception {
        AtomicReference<String> executed = new AtomicReference<>();
        BuildToolModel model = modelWithCommands("demo", "clean", "compile", "install");
        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(recordingHost(model, executed)));
        TreeView<BuildToolModel.Node> tree = find(panel, TreeView.class);
        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 5);

        onEdtRun(() -> {
            List<dtm.stools.component.tree.TreeNode<BuildToolModel.Node>> goals = goalNodes(tree);
            tree.selectNodes(goals);
            clickExecute(tree, goals.get(1));
        });

        await(() -> executed.get() != null);
        assertEquals("executeGoals:clean compile install", executed.get());
    }

    @Test
    void theContextMenuMovesTheSelectionToTheNodeUnderTheCursor() throws Exception {
        AtomicReference<String> executed = new AtomicReference<>();
        BuildToolModel model = modelWithCommands("demo", "clean", "compile", "install");
        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(recordingHost(model, executed)));
        TreeView<BuildToolModel.Node> tree = find(panel, TreeView.class);
        await(() -> rootChildren(tree) == 1 && visibleRows(tree) >= 5);

        onEdtRun(() -> {
            List<dtm.stools.component.tree.TreeNode<BuildToolModel.Node>> goals = goalNodes(tree);
            tree.selectNodes(List.of(goals.getFirst()));
            clickExecute(tree, goals.get(2));
        });

        await(() -> executed.get() != null);
        assertEquals("execute:install", executed.get());
    }

    private static List<dtm.stools.component.tree.TreeNode<BuildToolModel.Node>> goalNodes(
            TreeView<BuildToolModel.Node> tree) {
        return tree.getRootNode().getChildrenList().getFirst()
                .getChildrenList().getFirst().getChildrenList();
    }

    private static void clickExecute(TreeView<BuildToolModel.Node> tree,
                                     dtm.stools.component.tree.TreeNode<BuildToolModel.Node> target) {
        JPopupMenu popup = tree.getPopupMenuProvider()
                .apply(new TreePopupContext<>(tree, target, List.of(target), null));
        assertTrue(popup != null, "o menu de contexto deveria existir para um alvo executavel");
        for (Component component : popup.getComponents()) {
            if (component instanceof JMenuItem item && "Executar".equals(item.getText())) {
                item.doClick();
                return;
            }
        }
        throw new AssertionError("o menu de contexto deveria ter o item Executar");
    }

    private static JavaBuildToolsPanel.Host recordingHost(BuildToolModel model,
                                                          AtomicReference<String> executed) {
        return new JavaBuildToolsPanel.Host() {
            @Override
            public BuildToolModel load() {
                return model;
            }

            @Override
            public void execute(BuildToolModel.Node command) {
                executed.set("execute:" + String.join(" ", command.command()));
            }

            @Override
            public void executeGoals(BuildToolModel.Node context, List<String> goals) {
                executed.set("executeGoals:" + String.join(" ", goals));
            }

            @Override
            public void cancel() {
            }
        };
    }

    @Test
    void reloadUsesOnlyProfilesSavedForTheCurrentProject() throws Exception {
        AtomicReference<Set<String>> stored = new AtomicReference<>(Set.of("stale", "dev"));
        BuildToolModel.Node dev = new BuildToolModel.Node(BuildToolModel.Kind.PROFILE,
                "dev", null, List.of(), List.of());
        BuildToolModel model = new BuildToolModel("Maven", model("demo").projects(), List.of(dev));
        JavaBuildToolsPanel.Host host = new JavaBuildToolsPanel.Host() {
            @Override
            public BuildToolModel load() {
                return model;
            }

            @Override
            public void execute(BuildToolModel.Node command) {
            }

            @Override
            public void profilesChanged(Set<String> profiles) {
                stored.set(profiles);
            }

            @Override
            public Set<String> activeProfiles() {
                return stored.get();
            }

            @Override
            public void cancel() {
            }
        };

        JavaBuildToolsPanel panel = onEdt(() -> new JavaBuildToolsPanel(host));
        TreeView<?> tree = find(panel, TreeView.class);
        await(() -> rootChildren(tree) == 2);

        assertEquals(Set.of("dev"), stored.get());
    }

    private static JavaBuildToolsPanel.Host hostReturning(BuildToolModel model) {
        return new JavaBuildToolsPanel.Host() {
            @Override
            public BuildToolModel load() {
                return model;
            }

            @Override
            public void execute(BuildToolModel.Node command) {
            }

            @Override
            public void cancel() {
            }
        };
    }

    private static BuildToolModel model(String projectName) {
        return modelWithCommands(projectName, "clean");
    }

    private static BuildToolModel modelWithCommands(String projectName, String... names) {
        List<BuildToolModel.Node> commands = java.util.Arrays.stream(names)
                .map(name -> new BuildToolModel.Node(BuildToolModel.Kind.COMMAND,
                        name, null, List.of(name), List.of()))
                .toList();
        BuildToolModel.Node group = new BuildToolModel.Node(BuildToolModel.Kind.GROUP,
                "Lifecycle", null, List.of(), commands);
        BuildToolModel.Node project = new BuildToolModel.Node(BuildToolModel.Kind.PROJECT,
                projectName, null, List.of(), List.of(group));
        return new BuildToolModel("Maven", List.of(project), List.of());
    }

    private static JButton findButton(Container root, String tooltip) {
        if (root instanceof JButton button && tooltip.equals(button.getToolTipText())) {
            return button;
        }
        for (Component component : root.getComponents()) {
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("Button not found: " + tooltip);
    }

    private static JButton findButtonOrNull(Container root, String tooltip) {
        if (root instanceof JButton button && tooltip.equals(button.getToolTipText())) {
            return button;
        }
        for (Component component : root.getComponents()) {
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int rootChildren(TreeView<?> tree) {
        return onEdtUnchecked(() -> ((TreeNode) tree.getModel().getRoot()).getChildCount());
    }

    private static Object firstRootChild(TreeView<?> tree) {
        return onEdtUnchecked(() -> ((TreeNode) tree.getModel().getRoot()).getChildAt(0));
    }

    private static int visibleRows(TreeView<?> tree) {
        return onEdtUnchecked(tree::getRowCount);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            flushEdt();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> null);
    }

    private static void onEdtRun(Runnable action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T found = findOrNull(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError(type.getSimpleName() + " not found");
    }

    private static <T extends Component> T findOrNull(Container root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T found = findOrNull(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(ThrowingSupplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = action.get();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private static <T> T onEdtUnchecked(ThrowingSupplier<T> action) {
        try {
            return onEdt(action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
