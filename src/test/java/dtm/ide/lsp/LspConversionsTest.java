package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.inlay.InlayHint;
import dtm.stools.component.panels.editor.code.inlay.InlayHintKind;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspConversionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsRanges() {
        var range = LspConversions.range(json("""
                {"start":{"line":3,"character":8},"end":{"line":3,"character":14}}
                """));

        assertEquals(3, range.start().line());
        assertEquals(8, range.start().col());
        assertEquals(14, range.end().col());
    }

    @Test
    void missingRangeCollapsesToOrigin() {
        assertTrue(LspConversions.range(null).isEmpty());
    }

    @Test
    void readsPlainLocations() {
        Location location = LspConversions.location(json("""
                {"uri":"file:///p/A.java","range":{"start":{"line":1,"character":0},
                 "end":{"line":1,"character":5}}}
                """));

        assertNotNull(location);
        assertEquals("file:///p/A.java", location.uri());
        assertEquals(1, location.range().start().line());
    }

    @Test
    void readsLocationLinks() {
        Location location = LspConversions.location(json("""
                {"targetUri":"file:///p/B.java",
                 "targetSelectionRange":{"start":{"line":9,"character":4},
                                         "end":{"line":9,"character":10}}}
                """));

        assertNotNull(location);
        assertEquals("file:///p/B.java", location.uri());
        assertEquals(9, location.range().start().line());
    }

    @Test
    void acceptsASingleLocationOrAList() {
        assertEquals(1, LspConversions.locations(json("""
                {"uri":"file:///p/A.java","range":{"start":{"line":0,"character":0},
                 "end":{"line":0,"character":1}}}
                """)).size());

        assertEquals(2, LspConversions.locations(json("""
                [{"uri":"file:///p/A.java","range":{"start":{"line":0,"character":0},
                  "end":{"line":0,"character":1}}},
                 {"uri":"file:///p/B.java","range":{"start":{"line":2,"character":0},
                  "end":{"line":2,"character":1}}}]
                """)).size());
    }

    @Test
    void convertsDiagnosticsWithSeverity() {
        Diagnostic diagnostic = LspConversions.diagnostic(json("""
                {"range":{"start":{"line":4,"character":2},"end":{"line":4,"character":9}},
                 "severity":2,"message":"variavel nao usada","source":"Java"}
                """));

        assertNotNull(diagnostic);
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity());
        assertEquals("variavel nao usada", diagnostic.message());
        assertEquals(4, diagnostic.startLine());
        assertEquals(9, diagnostic.endCol());
    }

    @Test
    void diagnosticWithoutMessageIsDropped() {
        assertNull(LspConversions.diagnostic(json("""
                {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}
                """)));
    }

    @Test
    void defaultsToErrorWhenSeverityIsAbsent() {
        Diagnostic diagnostic = LspConversions.diagnostic(json("""
                {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},
                 "message":"falhou"}
                """));

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
    }

    @Test
    void mapsEverySeverityLevel() {
        assertEquals(DiagnosticSeverity.ERROR, LspConversions.severity(1));
        assertEquals(DiagnosticSeverity.WARNING, LspConversions.severity(2));
        assertEquals(DiagnosticSeverity.INFO, LspConversions.severity(3));
        assertEquals(DiagnosticSeverity.HINT, LspConversions.severity(4));
    }

    @Test
    void prefersTextEditOverInsertTextAndLabel() {
        AutoCompleteItem item = LspConversions.completionItem(json("""
                {"label":"toString()","insertText":"toString","kind":2,
                 "textEdit":{"range":{"start":{"line":0,"character":0},
                                      "end":{"line":0,"character":3}},
                             "newText":"toString()"}}
                """));

        assertNotNull(item);
        assertEquals("toString()", item.insertText());
        assertEquals("toString()", item.label());
        assertEquals(AutoCompleteItem.Kind.METHOD, item.kind());
    }

    @Test
    void fallsBackFromInsertTextToLabel() {
        assertEquals("valor", LspConversions.completionItem(json("""
                {"label":"valor","kind":6}
                """)).insertText());
    }

    @Test
    void semanticKindWinsOverSnippetWireFormat() {
        AutoCompleteItem item = LspConversions.completionItem(json("""
                {"label":"trim()","insertText":"trim(${1:value})","insertTextFormat":2,"kind":2}
                """));

        assertEquals(AutoCompleteItem.Kind.METHOD, item.kind());
        assertEquals("trim(value)", item.insertText());
    }

    @Test
    void realSnippetKeepsSnippetKind() {
        AutoCompleteItem item = LspConversions.completionItem(json("""
                {"label":"for","insertText":"for ($1) {\\n}","insertTextFormat":2,"kind":15}
                """));

        assertEquals(AutoCompleteItem.Kind.SNIPPET, item.kind());
    }

    @Test
    void buildsPopupFooterFromSignatureAndDocumentation() {
        AutoCompleteItem documented = LspConversions.completionItem(json("""
                {"label":"trim()","kind":2,"detail":"String.trim() : String",
                 "documentation":{"kind":"markdown","value":"Remove espacos das extremidades."}}
                """));
        AutoCompleteItem signatureOnly = LspConversions.completionItem(json("""
                {"label":"name","kind":5,"detail":"HtmlElement.name : String"}
                """));

        assertEquals("String.trim() : String\nRemove espacos das extremidades.",
                documented.description());
        assertEquals("HtmlElement.name : String", signatureOnly.description());
        assertNull(documented.detail());
        assertNull(signatureOnly.detail());
    }

    @Test
    void carriesAdditionalTextEditsForAutoImport() {
        AutoCompleteItem item = LspConversions.completionItem(json("""
                {"label":"List","kind":7,
                 "additionalTextEdits":[{"range":{"start":{"line":1,"character":0},
                                                  "end":{"line":1,"character":0}},
                                         "newText":"import java.util.List;\\n"}]}
                """));

        assertTrue(item.hasAdditionalTextEdits());
        assertEquals("import java.util.List;\n", item.additionalTextEdits().getFirst().newText());
    }

    @Test
    void completionWithoutLabelIsDropped() {
        assertNull(LspConversions.completionItem(json("{\"kind\":2}")));
    }

    @Test
    void readsMarkupContentHover() {
        HoverInfo hover = LspConversions.hover(json("""
                {"contents":{"kind":"markdown","value":"**String** trim()"}}
                """));

        assertNotNull(hover);
        assertEquals("**String** trim()", hover.content());
    }

    @Test
    void joinsHoverContentArrays() {
        HoverInfo hover = LspConversions.hover(json("""
                {"contents":["primeira","segunda"]}
                """));

        assertEquals("primeira\n\nsegunda", hover.content());
    }

    @Test
    void emptyHoverBecomesNull() {
        assertNull(LspConversions.hover(json("{\"contents\":\"\"}")));
        assertNull(LspConversions.hover(null));
    }

    @Test
    void resolvesParameterLabelsGivenAsOffsets() {
        SignatureHelp help = LspConversions.signatureHelp(json("""
                {"signatures":[{"label":"substring(int beginIndex, int endIndex)",
                                "parameters":[{"label":[10,24]},{"label":[26,38]}]}],
                 "activeSignature":0,"activeParameter":1}
                """));

        assertNotNull(help);
        assertEquals("int beginIndex", help.signatures().getFirst().parameters().getFirst().label());
        assertEquals(1, help.activeParameter());
    }

    @Test
    void signatureHelpWithoutSignaturesIsNull() {
        assertNull(LspConversions.signatureHelp(json("{\"signatures\":[]}")));
    }

    @Test
    void readsNestedDocumentSymbols() {
        List<DocumentSymbol> symbols = LspConversions.documentSymbols(json("""
                [{"name":"Demo","kind":5,
                  "range":{"start":{"line":0,"character":0},"end":{"line":9,"character":1}},
                  "selectionRange":{"start":{"line":0,"character":13},
                                    "end":{"line":0,"character":17}},
                  "children":[{"name":"run","kind":6,
                    "range":{"start":{"line":2,"character":4},"end":{"line":4,"character":5}},
                    "selectionRange":{"start":{"line":2,"character":16},
                                      "end":{"line":2,"character":19}}}]}]
                """));

        assertEquals(1, symbols.size());
        assertEquals(SymbolKind.CLASS, symbols.getFirst().kind());
        assertEquals(1, symbols.getFirst().children().size());
        assertEquals(SymbolKind.METHOD, symbols.getFirst().children().getFirst().kind());
    }

    @Test
    void readsFlatSymbolInformation() {
        List<DocumentSymbol> symbols = LspConversions.documentSymbols(json("""
                [{"name":"campo","kind":8,
                  "location":{"uri":"file:///p/A.java",
                              "range":{"start":{"line":3,"character":4},
                                       "end":{"line":3,"character":20}}}}]
                """));

        assertEquals(1, symbols.size());
        assertEquals(SymbolKind.FIELD, symbols.getFirst().kind());
        assertEquals(3, symbols.getFirst().range().start().line());
    }

    @Test
    void unknownSymbolKindFallsBackToOther() {
        assertEquals(SymbolKind.OTHER, LspConversions.symbolKind(99));
    }

    @Test
    void readsResolvedCodeLensLocations() {
        JdtLsService.JavaCodeLens lens = LspConversions.codeLens(json("""
                {"range":{"start":{"line":4,"character":2},"end":{"line":4,"character":8}},
                 "command":{"title":"2 referencias","command":"java.show.references",
                 "arguments":["file:///p/A.java",{"line":4,"character":2},[
                   {"uri":"file:///p/B.java","range":{"start":{"line":8,"character":1},
                                                         "end":{"line":8,"character":5}}},
                   {"uri":"file:///p/C.java","range":{"start":{"line":2,"character":0},
                                                         "end":{"line":2,"character":3}}}
                 ]]}}
                """));

        assertNotNull(lens);
        assertEquals(4, lens.range().start().line());
        assertEquals("java.show.references", lens.command());
        assertEquals(2, lens.locations().size());
    }

    @Test
    void unresolvedCodeLensIsNotRenderedYet() {
        assertNull(LspConversions.codeLens(json("""
                {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},
                 "data":{"uri":"file:///p/A.java"}}
                """)));
    }

    @Test
    void readsInlayHintsWithCompositeLabels() {
        InlayHint hint = LspConversions.inlayHint(json("""
                {"position":{"line":7,"character":22},
                 "label":[{"value":"nome"},{"value":":"}],"kind":2}
                """));

        assertNotNull(hint);
        assertEquals(7, hint.line());
        assertEquals("nome:", hint.text());
        assertEquals(InlayHintKind.PARAMETER, hint.kind());
        assertTrue(hint.paddingLeft());
        assertTrue(hint.pushText());
        assertTrue(hint.mouseTransparent());
        assertTrue(hint.hideOnCaretOrSelection());
    }

    @Test
    void recognizesParameterHintWhenJdtOmitsKind() {
        InlayHint hint = LspConversions.inlayHint(json("""
                {"position":{"line":12,"character":14},"label":"name:"}
                """));

        assertNotNull(hint);
        assertEquals(InlayHintKind.PARAMETER, hint.kind());
        assertTrue(hint.pushText());
    }

    @Test
    void inlayHintWithoutLabelIsDropped() {
        assertNull(LspConversions.inlayHint(json("""
                {"position":{"line":1,"character":1},"label":[]}
                """)));
    }

    @Test
    void codeActionWithSingleFileEditCarriesItsEdits() {
        CodeAction action = LspConversions.codeAction(json("""
                {"title":"Importar java.util.List","kind":"quickfix","isPreferred":true,
                 "edit":{"changes":{"file:///p/A.java":[
                    {"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":0}},
                     "newText":"import java.util.List;\\n"}]}}}
                """), "java/applyCodeAction");

        assertNotNull(action);
        assertEquals(CodeAction.CodeActionKind.QUICK_FIX, action.kind());
        assertTrue(action.preferred());
        assertEquals(1, action.edits().size());
        assertNull(action.command());
    }

    @Test
    void multiFileCodeActionIsDelegatedToTheServer() {
        CodeAction action = LspConversions.codeAction(json("""
                {"title":"Renomear em todo o projeto","kind":"refactor",
                 "edit":{"changes":{
                    "file:///p/A.java":[{"range":{"start":{"line":0,"character":0},
                                                  "end":{"line":0,"character":1}},"newText":"X"}],
                    "file:///p/B.java":[{"range":{"start":{"line":0,"character":0},
                                                  "end":{"line":0,"character":1}},"newText":"X"}]}}}
                """), "java/applyCodeAction");

        assertNotNull(action);
        assertTrue(action.edits().isEmpty());
        assertNotNull(action.command());
        assertEquals("java/applyCodeAction", action.command().id());
    }

    @Test
    void mapsCodeActionKinds() {
        assertEquals(CodeAction.CodeActionKind.QUICK_FIX,
                LspConversions.codeActionKind("quickfix.import"));
        assertEquals(CodeAction.CodeActionKind.SOURCE_ORGANIZE_IMPORTS,
                LspConversions.codeActionKind("source.organizeImports"));
        assertEquals(CodeAction.CodeActionKind.REFACTOR_EXTRACT,
                LspConversions.codeActionKind("refactor.extract.method"));
        assertEquals(CodeAction.CodeActionKind.OTHER,
                LspConversions.codeActionKind(""));
    }

    @Test
    void readsDocumentChangesFormOfWorkspaceEdit() {
        List<TextEdit> edits = LspConversions.singleDocumentEdits(json("""
                {"documentChanges":[{"textDocument":{"uri":"file:///p/A.java","version":2},
                  "edits":[{"range":{"start":{"line":0,"character":0},
                                     "end":{"line":0,"character":3}},"newText":"var"}]}]}
                """));

        assertEquals(1, edits.size());
        assertEquals("var", edits.getFirst().newText());
    }

    @Test
    void convertsBetweenPathAndUri() {
        Path path = Path.of("src", "main", "java", "App.java").toAbsolutePath();

        String uri = LspConversions.toUri(path);

        assertTrue(uri.startsWith("file:"));
        assertEquals(path.normalize(), LspConversions.toPath(uri));
    }

    @Test
    void nonFileUrisHaveNoLocalPath() {
        assertNull(LspConversions.toPath("jdt://contents/java.base/java.lang/String.class"));
        assertNull(LspConversions.toPath(""));
        assertNull(LspConversions.toPath(null));
    }

    @Test
    void malformedUriDoesNotThrow() {
        assertNull(LspConversions.toPath("nao e uma uri"));
    }

    @Test
    void documentationAcceptsEveryShape() {
        assertEquals("texto", LspConversions.documentation(json("\"texto\"")));
        assertEquals("markup", LspConversions.documentation(json("{\"value\":\"markup\"}")));
        assertNull(LspConversions.documentation(json("{}")));
        assertFalse(LspConversions.documentation(json("[\"a\",\"b\"]")).isBlank());
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(raw, e);
        }
    }
}
