package dtm.ide;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void ghostKeepsTheImportOfTheChosenItem() {
        TextEdit importEdit = TextEdit.insert(new Position(1, 0), "import java.time.LocalDate;\n");
        AutoCompleteItem localDate = new AutoCompleteItem("LocalDate", "LocalDate", "java.time",
                null, null, AutoCompleteItem.Kind.CLASS, List.of(importEdit));

        JavaIdeAdapter.GhostChoice choice = JavaIdeAdapter.ghostTextChoice(
                List.of(localDate), "LocalDa", false);

        assertEquals("te", choice.suffix());
        assertEquals(List.of(importEdit), choice.item().additionalTextEdits());
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

    @Test
    void marksOnlyMethodItemsReportedAsUnused() {
        AutoCompleteItem unusedMethod = new AutoCompleteItem("orphan()", "orphan() : void", null, null, null,
                AutoCompleteItem.Kind.METHOD);
        AutoCompleteItem usedMethod = new AutoCompleteItem("total()", "total() : int", null, null, null,
                AutoCompleteItem.Kind.METHOD);
        AutoCompleteItem sameNameVariable = new AutoCompleteItem("orphan", "orphan", null, null, null,
                AutoCompleteItem.Kind.VARIABLE);
        List<Set<String>> asked = new java.util.ArrayList<>();

        List<AutoCompleteItem> marked = JavaIdeAdapter.markUnusedMethods(
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

        assertEquals(items, JavaIdeAdapter.markUnusedMethods(items, names -> {
            throw new AssertionError("lookup nao deveria rodar");
        }));
    }

    @Test
    void unusedMethodsBecomeFadeOnlyHintsOnTheirName() {
        String text = String.join("\n",
                "class Service {",
                "    private int orphan() { return 1; }",
                "    int used() { return 2; }",
                "}");

        List<dtm.stools.component.panels.editor.code.diagnostics.Diagnostic> hints =
                JavaIdeAdapter.unusedMethodDiagnostics(text, List.of(), names -> Set.of("orphan"));

        assertEquals(1, hints.size());
        var hint = hints.getFirst();
        assertEquals(1, hint.startLine());
        assertEquals(text.split("\n")[1].indexOf("orphan"), hint.startCol());
        assertEquals(hint.startCol() + "orphan".length(), hint.endCol());
        assertEquals("unused", hint.source());
        org.junit.jupiter.api.Assertions.assertTrue(hint.isFadeOnly());
    }

    @Test
    void doesNotDuplicateWhatTheServerAlreadyMarkedAsUnnecessary() {
        String text = "class Service {\n    private int orphan() { return 1; }\n}";
        int col = text.split("\n")[1].indexOf("orphan");
        var fromServer = new dtm.stools.component.panels.editor.code.diagnostics.Diagnostic(1, col, 1, col + 6,
                dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity.WARNING,
                "never used locally", "Java", null, true);

        assertEquals(List.of(), JavaIdeAdapter.unusedMethodDiagnostics(text, List.of(fromServer),
                names -> Set.of("orphan")));
    }

    @Test
    void unusedFieldHintFadesOnlyTheFieldName() {
        String text = "class Service {\n    private int orphan;\n}";
        var hints = JavaIdeAdapter.unusedFieldDiagnostics(text, List.of(), names -> Set.of("orphan"));

        assertEquals(1, hints.size());
        var hint = hints.getFirst();
        assertEquals(1, hint.startLine());
        assertEquals(text.split("\n")[1].indexOf("orphan"), hint.startCol());
        assertEquals("unused.field", hint.source());
        assertTrue(hint.isFadeOnly());
    }
}
