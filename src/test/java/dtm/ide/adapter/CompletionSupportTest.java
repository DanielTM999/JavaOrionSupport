package dtm.ide.adapter;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompletionSupportTest {

    @Test
    void ranksJdtItemsBeforeLocalSnippetsAndDeduplicatesIgnoringCase() {
        AutoCompleteItem jdt = new AutoCompleteItem("System");
        AutoCompleteItem duplicateSnippet = AutoCompleteItem.snippet("system", "System.out");
        AutoCompleteItem local = AutoCompleteItem.snippet("sout", "System.out.println($0);");

        List<AutoCompleteItem> merged = CompletionSupport.mergeCompletionSuggestions(
                List.of(jdt), List.of(duplicateSnippet, local));

        assertEquals(List.of("System", "sout"), merged.stream()
                .map(AutoCompleteItem::label).toList());
    }

    @Test
    void keepsOverloadedMethodsThatShareTheSameLabel() {
        AutoCompleteItem first = new AutoCompleteItem("substring(${1:beginIndex})",
                "substring", "String", null, null, AutoCompleteItem.Kind.METHOD);
        AutoCompleteItem second = new AutoCompleteItem("substring(${1:beginIndex}, ${2:endIndex})",
                "substring", "String", null, null, AutoCompleteItem.Kind.METHOD);

        List<AutoCompleteItem> merged = CompletionSupport.mergeCompletionSuggestions(
                List.of(first, second), List.of());

        assertEquals(2, merged.size(), merged.toString());
    }

    @Test
    void dropsSemanticItemsRepeatedWithTheSameSignature() {
        AutoCompleteItem item = new AutoCompleteItem("length()", "length", "String",
                null, null, AutoCompleteItem.Kind.METHOD);

        List<AutoCompleteItem> merged = CompletionSupport.mergeCompletionSuggestions(
                List.of(item, item), List.of());

        assertEquals(1, merged.size());
    }

    @Test
    void recognizesOnlyRealTriggerCharactersBeforeTheCaret() {
        Set<Character> triggers = Set.of('.', '@');

        assertEquals('.', CompletionSupport.completionTriggerCharacter("value.", 6, triggers));
        assertEquals('@', CompletionSupport.completionTriggerCharacter("    @", 5, triggers));
        assertNull(CompletionSupport.completionTriggerCharacter("value", 5, triggers));
        assertNull(CompletionSupport.completionTriggerCharacter("value ", 6, triggers));
        assertNull(CompletionSupport.completionTriggerCharacter(null, 3, triggers));
        assertNull(CompletionSupport.completionTriggerCharacter("value.", 0, triggers));
    }

    @Test
    void filtersTheLastSemanticSnapshotWithoutWaitingForTheServer() {
        List<AutoCompleteItem> filtered = CompletionSupport.filterCompletionSuggestions(
                List.of(new AutoCompleteItem("substring"), new AutoCompleteItem("strip"),
                        new AutoCompleteItem("length")), "st");

        assertEquals(List.of("strip"), filtered.stream().map(AutoCompleteItem::label).toList());
    }

    @Test
    void marksOnlyMethodItemsReportedAsUnused() {
        AutoCompleteItem unusedMethod = new AutoCompleteItem("orphan()", "orphan() : void", null, null, null,
                AutoCompleteItem.Kind.METHOD);
        AutoCompleteItem usedMethod = new AutoCompleteItem("total()", "total() : int", null, null, null,
                AutoCompleteItem.Kind.METHOD);
        AutoCompleteItem sameNameVariable = new AutoCompleteItem("orphan", "orphan", null, null, null,
                AutoCompleteItem.Kind.VARIABLE);
        List<Set<String>> asked = new java.util.ArrayList<>();

        List<AutoCompleteItem> marked = CompletionSupport.markUnusedMethods(
                List.of(unusedMethod, usedMethod, sameNameVariable), names -> {
                    asked.add(names);
                    return Set.of("orphan");
                });

        assertEquals(List.of(Set.of("orphan", "total")), asked);
        assertEquals(List.of(true, false, false), marked.stream().map(AutoCompleteItem::unused).toList());
    }

    @Test
    void skipsTheLookupWhenThereAreNoMethods() {
        List<AutoCompleteItem> items = List.of(AutoCompleteItem.snippet("sout", "System.out.println($0);"));

        assertEquals(items, CompletionSupport.markUnusedMethods(items, names -> {
            throw new AssertionError("lookup nao deveria rodar");
        }));
    }

}
