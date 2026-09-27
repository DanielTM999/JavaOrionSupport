package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDebugPanelTest {

    @Test
    void pausingOnADeepStackDoesNotBlockTheEventDispatchThread() throws Exception {
        CountingHost host = new CountingHost();
        AtomicReference<JavaDebugPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new JavaDebugPanel(host, Runnable::run)));
        JavaDebugSnapshot paused = paused(100);

        long started = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> panel.get().update(paused));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_000, "update levou " + elapsedMillis + " ms");
        assertEquals(1, host.opened.get());
    }

    @Test
    void republishingTheSamePauseDoesNotQueryTheDebuggerOrNavigateAgain() throws Exception {
        CountingHost host = new CountingHost();
        AtomicReference<JavaDebugPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new JavaDebugPanel(host, Runnable::run)));
        JavaDebugSnapshot paused = paused(10);

        SwingUtilities.invokeAndWait(() -> panel.get().update(paused));
        SwingUtilities.invokeAndWait(() -> panel.get().update(new JavaDebugSnapshot(
                paused.state(), "output line", paused.threadId(), paused.threads(),
                paused.frames(), paused.variables(), paused.scopes())));

        assertEquals(0, host.scopeLoads.get());
        assertEquals(0, host.threadSelections.get());
        assertEquals(1, host.opened.get());
    }

    @Test
    void replaceSkipsIdenticalContentAndSwapsChangedContentAtOnce() {
        DefaultListModel<String> model = new DefaultListModel<>();
        AtomicInteger additions = new AtomicInteger();
        model.addListDataListener(new javax.swing.event.ListDataListener() {
            @Override
            public void intervalAdded(javax.swing.event.ListDataEvent event) {
                additions.incrementAndGet();
            }

            @Override
            public void intervalRemoved(javax.swing.event.ListDataEvent event) {
            }

            @Override
            public void contentsChanged(javax.swing.event.ListDataEvent event) {
            }
        });

        assertTrue(JavaDebugPanel.replace(model, List.of("a", "b", "c")));
        assertFalse(JavaDebugPanel.replace(model, List.of("a", "b", "c")));
        assertTrue(JavaDebugPanel.replace(model, List.of("a", "b")));

        assertEquals(2, additions.get());
        assertEquals(2, model.size());
    }

    @Test
    void stackRowsReuseOnePlainTextComponent() {
        JavaDebugPanel.StackRowRenderer<JavaDebugSnapshot.StackFrame> renderer =
                new JavaDebugPanel.StackRowRenderer<>(false, JavaDebugSnapshot.StackFrame::name,
                        JavaDebugPanel::frameLocation);
        JList<JavaDebugSnapshot.StackFrame> list = new JList<>();
        JavaDebugSnapshot.StackFrame frame = new JavaDebugSnapshot.StackFrame(1, "map",
                null, "jdt://contents/modelmapper.jar/org.modelmapper/ModelMapper.class?=x", 400);

        Component first = renderer.getListCellRendererComponent(list, frame, 0, false, false);
        Component second = renderer.getListCellRendererComponent(list, frame, 1, true, false);

        assertSame(first, second);
        for (Component child : ((JPanel) first).getComponents()) {
            assertFalse(((JLabel) child).getText().startsWith("<html>"));
        }
        assertEquals("ModelMapper.java:400", JavaDebugPanel.frameLocation(frame));
    }

    private static JavaDebugSnapshot paused(int depth) {
        List<JavaDebugSnapshot.StackFrame> frames = new ArrayList<>();
        for (int index = 0; index < depth; index++) {
            frames.add(new JavaDebugSnapshot.StackFrame(index + 1, "frame" + index,
                    Path.of("Demo" + index + ".java"), index + 1));
        }
        List<JavaDebugSnapshot.ThreadInfo> threads = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            threads.add(new JavaDebugSnapshot.ThreadInfo(index + 1, "Thread [worker-" + index + "]"));
        }
        JavaDebugSnapshot.Scope locals = new JavaDebugSnapshot.Scope("Locals", 5, List.of(
                new JavaDebugSnapshot.Variable("value", "42", "int", 0)));
        return new JavaDebugSnapshot(JavaDebugSnapshot.State.PAUSED, "Paused", 1, threads, frames,
                locals.variables(), List.of(locals));
    }

    private static final class CountingHost implements JavaDebugPanel.Host {
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger scopeLoads = new AtomicInteger();
        private final AtomicInteger threadSelections = new AtomicInteger();

        @Override
        public void resume() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void next() {
        }

        @Override
        public void stepIn() {
        }

        @Override
        public void stepOut() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void hotReload() {
        }

        @Override
        public void openFile(Path file, int line) {
            opened.incrementAndGet();
        }

        @Override
        public JavaDebugSnapshot.Variable evaluate(String expression, int frameId) {
            return null;
        }

        @Override
        public List<JavaDebugSnapshot.Variable> variables(int reference) {
            return List.of();
        }

        @Override
        public List<JavaDebugSnapshot.Variable> variablesForFrame(int frameId) {
            return List.of();
        }

        @Override
        public List<JavaDebugSnapshot.Scope> scopesForFrame(int frameId) {
            scopeLoads.incrementAndGet();
            return List.of();
        }

        @Override
        public void showEvaluate(int frameId) {
        }

        @Override
        public void selectThread(int threadId) {
            threadSelections.incrementAndGet();
        }
    }
}
