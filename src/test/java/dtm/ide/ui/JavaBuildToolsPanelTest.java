package dtm.ide.ui;

import dtm.ide.build.BuildToolModel;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.tree.TreeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.tree.TreeNode;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        BuildToolModel.Node command = new BuildToolModel.Node(BuildToolModel.Kind.COMMAND,
                "clean", null, List.of("clean"), List.of());
        BuildToolModel.Node group = new BuildToolModel.Node(BuildToolModel.Kind.GROUP,
                "Lifecycle", null, List.of(), List.of(command));
        BuildToolModel.Node project = new BuildToolModel.Node(BuildToolModel.Kind.PROJECT,
                projectName, null, List.of(), List.of(group));
        return new BuildToolModel("Maven", List.of(project), List.of());
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
