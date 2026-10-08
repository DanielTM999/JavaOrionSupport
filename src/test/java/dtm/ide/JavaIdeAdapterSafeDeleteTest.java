package dtm.ide;

import dtm.ide.adapter.SafeDeleteSupport;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaIdeAdapterSafeDeleteTest {

    @Test
    void onlyTypeDeclarationsAreSearchedWhenDeletingAFile() {
        DocumentSymbol method = DocumentSymbol.leaf("executar", SymbolKind.METHOD, Range.of(4, 4, 4, 12));
        DocumentSymbol field = DocumentSymbol.leaf("repo", SymbolKind.FIELD, Range.of(3, 4, 3, 8));
        DocumentSymbol constructor = DocumentSymbol.leaf("Servico", SymbolKind.CONSTRUCTOR, Range.of(5, 4, 5, 11));
        DocumentSymbol nestedEnum = new DocumentSymbol("Estado", null, SymbolKind.ENUM,
                Range.of(8, 4, 10, 5), Range.of(8, 16, 8, 22),
                List.of(DocumentSymbol.leaf("ATIVO", SymbolKind.ENUM_MEMBER, Range.of(9, 8, 9, 13))));
        DocumentSymbol type = new DocumentSymbol("Servico", null, SymbolKind.CLASS,
                Range.of(1, 0, 11, 1), Range.of(1, 13, 1, 20),
                List.of(field, method, constructor, nestedEnum));
        DocumentSymbol record = DocumentSymbol.leaf("Dados", SymbolKind.STRUCT, Range.of(13, 7, 13, 12));

        List<Range> ranges = SafeDeleteSupport.typeDeclarationRanges(List.of(type, record));

        assertEquals(List.of(Range.of(1, 13, 1, 20), Range.of(13, 7, 13, 12), Range.of(8, 16, 8, 22)), ranges);
        assertTrue(SafeDeleteSupport.typeDeclarationRanges(List.of(method)).isEmpty());
        assertTrue(SafeDeleteSupport.typeDeclarationRanges(null).isEmpty());
    }
}
