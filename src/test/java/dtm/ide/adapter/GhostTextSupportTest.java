package dtm.ide.adapter;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GhostTextSupportTest {

    @Test
    void expandsSnippetPlaceholdersForGhostText() {
        assertEquals("toString(value)", GhostTextSupport.sanitizeSnippetForGhostText(
                "toString(${1:value}$0)"));
        assertEquals("method()", GhostTextSupport.sanitizeSnippetForGhostText("method(${1})"));
    }

    @Test
    void derivesGhostTextFromInsertTextIgnoringCaseAsFallback() {
        assertEquals("String()", GhostTextSupport.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("toString", "toString(${1})")), "to"));
        assertEquals("stem", GhostTextSupport.ghostTextSuffix(
                List.of(new AutoCompleteItem("System")), "Sy"));
        assertEquals("stem", GhostTextSupport.ghostTextSuffix(
                List.of(new AutoCompleteItem("System")), "sy"));
    }

    @Test
    void ghostKeepsTheImportOfTheChosenItem() {
        TextEdit importEdit = TextEdit.insert(new Position(1, 0), "import java.time.LocalDate;\n");
        AutoCompleteItem localDate = new AutoCompleteItem("LocalDate", "LocalDate", "java.time",
                null, null, AutoCompleteItem.Kind.CLASS, List.of(importEdit));

        GhostTextSupport.GhostChoice choice = GhostTextSupport.ghostTextChoice(
                List.of(localDate), "LocalDa", false);

        assertEquals("te", choice.suffix());
        assertEquals(List.of(importEdit), choice.item().additionalTextEdits());
    }

    @Test
    void neverOffersTheDisplayLabelAsGhostText() {
        AutoCompleteItem annotation = new AutoCompleteItem("Data", "Data - lombok", "lombok",
                null, null, AutoCompleteItem.Kind.INTERFACE);

        assertNull(GhostTextSupport.ghostTextSuffix(List.of(annotation), "Data"));
    }

    @Test
    void suppliesLexicalGhostTextBeforeLanguageServerIsReady() {
        assertEquals("is", GhostTextSupport.lexicalGhostTextSuffix("class Nav {}", "th"));
        assertEquals("assName", GhostTextSupport.lexicalGhostTextSuffix(
                "String className;", "cl"));
    }

    @Test
    void completesMembersImmediatelyAfterDot() {
        assertEquals("toString()", GhostTextSupport.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("toString", "toString(${1})")), "", true));
    }

    @Test
    void keepsAndIndentsWholeCodeBlocks() {
        String suffix = GhostTextSupport.ghostTextSuffix(List.of(
                AutoCompleteItem.snippet("try", "try {\n    $0\n} catch (Exception e) {\n    \n}")),
                "try");

        assertEquals(" {\n            \n        } catch (Exception e) {\n            \n        }",
                GhostTextSupport.indentMultilineGhostText(suffix, "        try"));
    }

}
