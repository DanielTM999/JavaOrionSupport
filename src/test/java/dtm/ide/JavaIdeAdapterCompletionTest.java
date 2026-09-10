package dtm.ide;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaIdeAdapterCompletionTest {

    @Test
    void ranksJdtItemsBeforeLocalSnippetsAndDeduplicatesIgnoringCase() {
        AutoCompleteItem jdt = new AutoCompleteItem("System");
        AutoCompleteItem duplicateSnippet = AutoCompleteItem.snippet("system", "System.out");
        AutoCompleteItem local = AutoCompleteItem.snippet("sout", "System.out.println($0);");

        List<AutoCompleteItem> merged = JavaIdeAdapter.mergeCompletionSuggestions(
                List.of(jdt), List.of(duplicateSnippet, local));

        assertEquals(List.of("System", "sout"), merged.stream()
                .map(AutoCompleteItem::label).toList());
    }

    @Test
    void expandsSnippetPlaceholdersForGhostText() {
        assertEquals("toString(value)", JavaIdeAdapter.sanitizeSnippetForGhostText(
                "toString(${1:value}$0)"));
        assertEquals("method()", JavaIdeAdapter.sanitizeSnippetForGhostText("method(${1})"));
    }

    @Test
    void derivesGhostTextFromInsertTextIgnoringCaseAsFallback() {
        assertEquals("String()", JavaIdeAdapter.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("toString", "toString(${1})")), "to"));
        assertEquals("stem", JavaIdeAdapter.ghostTextSuffix(
                List.of(new AutoCompleteItem("System")), "Sy"));
        assertEquals("stem", JavaIdeAdapter.ghostTextSuffix(
                List.of(new AutoCompleteItem("System")), "sy"));
    }

    @Test
    void neverOffersTheDisplayLabelAsGhostText() {
        AutoCompleteItem annotation = new AutoCompleteItem("Data", "Data - lombok", "lombok",
                null, null, AutoCompleteItem.Kind.INTERFACE);

        assertNull(JavaIdeAdapter.ghostTextSuffix(List.of(annotation), "Data"));
    }

    @Test
    void suppliesLexicalGhostTextBeforeLanguageServerIsReady() {
        assertEquals("is", JavaIdeAdapter.lexicalGhostTextSuffix("class Nav {}", "th"));
        assertEquals("assName", JavaIdeAdapter.lexicalGhostTextSuffix(
                "String className;", "cl"));
    }

    @Test
    void completesMembersImmediatelyAfterDot() {
        assertEquals("toString()", JavaIdeAdapter.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("toString", "toString(${1})")), "", true));
    }

    @Test
    void keepsAndIndentsWholeCodeBlocks() {
        String suffix = JavaIdeAdapter.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("try", "try {\n    $0\n} catch (Exception e) {\n    \n}")),
                "try");

        assertEquals(" {\n            \n        } catch (Exception e) {\n            \n        }",
                JavaIdeAdapter.indentMultilineGhostText(suffix, "        try"));
    }

    @Test
    void keepsOverloadedMethodsThatShareTheSameLabel() {
        AutoCompleteItem first = new AutoCompleteItem("substring(${1:beginIndex})",
                "substring", "String", null, null, AutoCompleteItem.Kind.METHOD);
        AutoCompleteItem second = new AutoCompleteItem("substring(${1:beginIndex}, ${2:endIndex})",
                "substring", "String", null, null, AutoCompleteItem.Kind.METHOD);

        List<AutoCompleteItem> merged = JavaIdeAdapter.mergeCompletionSuggestions(
                List.of(first, second), List.of());

        assertEquals(2, merged.size(), merged.toString());
    }

    @Test
    void dropsSemanticItemsRepeatedWithTheSameSignature() {
        AutoCompleteItem item = new AutoCompleteItem("length()", "length", "String",
                null, null, AutoCompleteItem.Kind.METHOD);

        List<AutoCompleteItem> merged = JavaIdeAdapter.mergeCompletionSuggestions(
                List.of(item, item), List.of());

        assertEquals(1, merged.size());
    }

    @Test
    void recognizesOnlyRealTriggerCharactersBeforeTheCaret() {
        Set<Character> triggers = Set.of('.', '@');

        assertEquals('.', JavaIdeAdapter.completionTriggerCharacter("value.", 6, triggers));
        assertEquals('@', JavaIdeAdapter.completionTriggerCharacter("    @", 5, triggers));
        assertNull(JavaIdeAdapter.completionTriggerCharacter("value", 5, triggers));
        assertNull(JavaIdeAdapter.completionTriggerCharacter("value ", 6, triggers));
        assertNull(JavaIdeAdapter.completionTriggerCharacter(null, 3, triggers));
        assertNull(JavaIdeAdapter.completionTriggerCharacter("value.", 0, triggers));
    }

    @Test
    void filtersTheLastSemanticSnapshotWithoutWaitingForTheServer() {
        List<AutoCompleteItem> filtered = JavaIdeAdapter.filterCompletionSuggestions(
                List.of(new AutoCompleteItem("substring"), new AutoCompleteItem("strip"),
                        new AutoCompleteItem("length")), "st");

        assertEquals(List.of("strip"), filtered.stream().map(AutoCompleteItem::label).toList());
    }
}
