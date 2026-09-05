package dtm.ide;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void derivesGhostTextFromInsertTextOrLabelIgnoringCaseAsFallback() {
        assertEquals("String()", JavaIdeAdapter.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("toString", "toString(${1})")), "to"));
        assertEquals("stem", JavaIdeAdapter.ghostTextSuffix(
                List.of(new AutoCompleteItem("System")), "Sy"));
        assertEquals("stem", JavaIdeAdapter.ghostTextSuffix(
                List.of(new AutoCompleteItem("System")), "sy"));
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
    void filtersTheLastSemanticSnapshotWithoutWaitingForTheServer() {
        List<AutoCompleteItem> filtered = JavaIdeAdapter.filterCompletionSuggestions(
                List.of(new AutoCompleteItem("substring"), new AutoCompleteItem("strip"),
                        new AutoCompleteItem("length")), "st");

        assertEquals(List.of("strip"), filtered.stream().map(AutoCompleteItem::label).toList());
    }
}
