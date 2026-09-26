package dtm.ide.debug;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionCompletionProviderTest {

    private static final Path FILE = Path.of("Demo.java").toAbsolutePath().normalize();
    private static final String SOURCE = """
            class Demo {
                private int total;
                void run(String user, int count) {
                    int index = 0;
                    use(index);
                }
            }
            """;

    @Test
    void withoutADebugSessionOnlyTheStaticScopeIsOffered() {
        ConditionCompletionProvider provider = provider(() -> null);

        List<String> labels = labels(provider.suggestions("co", 2, "co"));

        assertEquals(List.of("count"), labels);
        assertTrue(labels(provider.suggestions("", 0, "")).containsAll(
                List.of("index", "user", "count", "total", "this", "true", "false", "null")));
    }

    @Test
    void aMemberAccessSkipsTheStaticScope() {
        ConditionCompletionProvider provider = provider(() -> null);

        assertTrue(provider.suggestions("user.", 5, "").isEmpty());
    }

    @Test
    void aSessionPausedInTheSameFileAddsDebuggerMembers() {
        FakeSource debugger = new FakeSource(FILE,
                List.of(new JavaDebugSession.Completion("getName", "getName()", "method")));
        ConditionCompletionProvider provider = provider(() -> debugger);

        List<AutoCompleteItem> items = provider.suggestions("user.get", 8, "get");

        assertEquals(List.of("getName"), labels(items));
        assertEquals("getName()", items.getFirst().insertText());
        assertEquals(AutoCompleteItem.Kind.METHOD, items.getFirst().kind());
        assertEquals(List.of("user.get@9"), debugger.requests);
    }

    @Test
    void aSessionPausedInAnotherFileIsIgnored() {
        FakeSource debugger = new FakeSource(Path.of("Other.java").toAbsolutePath(),
                List.of(new JavaDebugSession.Completion("getName", "getName()", "method")));
        ConditionCompletionProvider provider = provider(() -> debugger);

        assertFalse(labels(provider.suggestions("user.get", 8, "get")).contains("getName"));
        assertTrue(debugger.requests.isEmpty());
    }

    private static ConditionCompletionProvider provider(
            java.util.function.Supplier<DebuggerCompletionSource> session) {
        return new ConditionCompletionProvider(FILE, 4, () -> SOURCE, session);
    }

    private static List<String> labels(List<AutoCompleteItem> items) {
        return items.stream().map(AutoCompleteItem::label).toList();
    }

    private static final class FakeSource implements DebuggerCompletionSource {
        private final Path paused;
        private final List<JavaDebugSession.Completion> completions;
        private final List<String> requests = new ArrayList<>();

        private FakeSource(Path paused, List<JavaDebugSession.Completion> completions) {
            this.paused = paused;
            this.completions = completions;
        }

        @Override
        public boolean isCompletionsSupported() {
            return true;
        }

        @Override
        public Path pausedSource() {
            return paused;
        }

        @Override
        public List<JavaDebugSession.Completion> completions(String text, int column) {
            requests.add(text + "@" + column);
            return completions;
        }
    }
}
