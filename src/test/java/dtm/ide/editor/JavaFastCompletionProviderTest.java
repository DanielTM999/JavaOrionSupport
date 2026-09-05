package dtm.ide.editor;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFastCompletionProviderTest {

    private final JavaFastCompletionProvider completion = new JavaFastCompletionProvider();

    @Test
    void completesCurrentDocumentSymbolsWithoutALanguageServer() {
        String source = "class Demo { CustomerService customerService; void calculateTotal() {} cus";

        List<String> labels = labels(completion.suggestions(context(source, "cus")));

        assertTrue(labels.contains("customerService"), labels.toString());
    }

    @Test
    void completesCommonMembersFromTheLocallyInferredType() {
        String source = "class Demo { void run() { String name = \"x\"; name.st";

        List<String> labels = labels(completion.suggestions(context(source, "st")));

        assertTrue(JavaFastCompletionProvider.isMemberAccess(context(source, "st")));
        assertTrue(labels.contains("startsWith"));
        assertTrue(labels.contains("strip"));
        assertFalse(labels.contains("class"));
    }

    @Test
    void refreshMakesSymbolsFromAnotherSourceImmediatelyAvailable() {
        completion.refreshFile(Path.of("OrderService.java"),
                "class OrderService { void recalculateOrder() {} }");

        List<String> labels = labels(completion.suggestions(context("class Use { Ord", "Ord")));

        assertTrue(labels.contains("OrderService"));
    }

    @Test
    void ignoresIdentifiersWrittenOnlyInsideComments() {
        String source = "class Demo { // PhantomService\n realValue";

        List<String> labels = labels(completion.suggestions(context(source, "Pha")));

        assertFalse(labels.contains("PhantomService"));
    }

    private static IdeCompletionContext context(String source, String prefix) {
        int offset = source.length();
        int lineStart = source.lastIndexOf('\n') + 1;
        return new IdeCompletionContext(
                source, Path.of("Demo.java"), offset,
                (int) source.chars().filter(value -> value == '\n').count(),
                offset - lineStart, source.substring(lineStart), prefix,
                offset - prefix.length(), IdeCompletionTriggerKind.TYPING);
    }

    private static List<String> labels(List<AutoCompleteItem> items) {
        return items.stream().map(AutoCompleteItem::label).toList();
    }
}
