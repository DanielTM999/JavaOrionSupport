package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot.Variable;
import org.junit.jupiter.api.Test;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JavaDebugValueTreeTest {
    @Test void aLateResponseCannotUpdateNodesFromThePreviousFrame() throws Exception {
        Queue<Runnable> pending = new ArrayDeque<>();
        JavaDebugValueTree panel = new JavaDebugValueTree(pending::add);
        panel.bindChildrenProvider(reference -> List.of(new Variable("oldChild", "1", "int", 0)));
        SwingUtilities.invokeAndWait(() -> panel.setValue(new Variable("old", "{}", "Object", 1)));
        JTree tree = tree(panel);
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var old = (DefaultMutableTreeNode) root.getFirstChild();
        assertEquals(1, pending.size());
        SwingUtilities.invokeAndWait(() -> panel.setValue(new Variable("new", "2", "int", 0)));
        pending.remove().run();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals("new", root.getFirstChild().toString());
        assertEquals("", old.getFirstChild().toString(), "the detached old node must keep its placeholder");
    }

    @Test void failedLoadingCanBeRetriedByExpandingAgain() throws Exception {
        Queue<Runnable> pending = new ArrayDeque<>();
        AtomicInteger attempts = new AtomicInteger();
        JavaDebugValueTree panel = new JavaDebugValueTree(pending::add);
        panel.bindChildrenProvider(reference -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("disconnected");
            return List.of(new Variable("child", "1", "int", 0));
        });
        SwingUtilities.invokeAndWait(() -> panel.setValue(new Variable("value", "{}", "Object", 1)));
        pending.remove().run();
        SwingUtilities.invokeAndWait(() -> { });
        JTree tree = tree(panel);
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var value = (DefaultMutableTreeNode) root.getFirstChild();
        TreePath path = new TreePath(value.getPath());
        SwingUtilities.invokeAndWait(() -> { tree.collapsePath(path); tree.expandPath(path); });
        assertEquals(1, pending.size());
        pending.remove().run();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals("child", value.getFirstChild().toString());
        assertEquals(2, attempts.get());
    }

    private static JTree tree(JavaDebugValueTree panel) throws Exception {
        var field = JavaDebugValueTree.class.getDeclaredField("tree");
        field.setAccessible(true);
        return (JTree) field.get(panel);
    }
}
