package dtm.ide.coverage;

import dtm.ide.index.JavaLexicalSource;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageDisplayTest {

    private static FileCoverage coverage(Map<Integer, LineStatus> lines,
                                         int coveredBranches, int totalBranches) {
        return new FileCoverage(Path.of("Foo.java"), lines, coveredBranches, totalBranches);
    }

    private static Map<Integer, LineStatus> lines(Object... pairs) {
        Map<Integer, LineStatus> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((Integer) pairs[index], (LineStatus) pairs[index + 1]);
        }
        return map;
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind, int line) {
        Range range = new Range(new Position(line, 0), new Position(line, 10));
        return DocumentSymbol.leaf(name, kind, range);
    }

    @Test
    void percentageIsRoundedToWholeNumbers() {
        assertEquals("0%", CoverageDisplay.percent(0d));
        assertEquals("50%", CoverageDisplay.percent(50d));
        assertEquals("67%", CoverageDisplay.percent(66.6d));
        assertEquals("100%", CoverageDisplay.percent(100d));
    }

    @Test
    void summaryCarriesThePercentageAndTheRawCount() {
        FileCoverage file = coverage(lines(
                1, LineStatus.COVERED, 2, LineStatus.COVERED, 3, LineStatus.UNCOVERED), 0, 0);

        assertEquals("67% (2/3)", CoverageDisplay.summary(file));
    }

    @Test
    void summaryIsBlankWhenThereIsNothingToShow() {
        assertEquals("", CoverageDisplay.summary(null));
        assertEquals("", CoverageDisplay.summary(coverage(Map.of(), 0, 0)));
    }

    @Test
    void branchSummaryOnlyAppearsWhenTheFileHasBranches() {
        assertEquals("", CoverageDisplay.branchSummary(
                coverage(lines(1, LineStatus.COVERED), 0, 0)));
        assertEquals("", CoverageDisplay.branchSummary(null));
        assertEquals("50% (1/2)", CoverageDisplay.branchSummary(
                coverage(lines(1, LineStatus.PARTIAL), 1, 2)));
    }

    @Test
    void lensGoesOnTheFirstTypeDeclaration() {
        List<DocumentSymbol> outline = List.of(
                symbol("Foo", SymbolKind.CLASS, 4),
                symbol("Bar", SymbolKind.CLASS, 40));

        assertEquals(4, CoverageDisplay.lensLineOf(outline));
    }

    @Test
    void lensSkipsNonTypeSymbolsThatComeFirst() {
        List<DocumentSymbol> outline = List.of(
                symbol("field", SymbolKind.FIELD, 1),
                symbol("method", SymbolKind.METHOD, 2),
                symbol("Foo", SymbolKind.INTERFACE, 7));

        assertEquals(7, CoverageDisplay.lensLineOf(outline));
    }

    @Test
    void everyJavaTypeKindCountsAsATypeDeclaration() {
        for (SymbolKind kind : List.of(SymbolKind.CLASS, SymbolKind.INTERFACE,
                SymbolKind.ENUM, SymbolKind.STRUCT)) {
            assertTrue(JavaLexicalSource.isType(kind), "esperado tipo: " + kind);
            assertEquals(3, CoverageDisplay.lensLineOf(List.of(symbol("X", kind, 3))));
        }
    }

    @Test
    void fallsBackToTheTopOfTheFileWhenThereIsNoType() {
        assertEquals(0, CoverageDisplay.lensLineOf(null));
        assertEquals(0, CoverageDisplay.lensLineOf(List.of()));
        assertEquals(0, CoverageDisplay.lensLineOf(List.of(symbol("f", SymbolKind.FIELD, 9))));
    }

    @Test
    void lineNumbersAreNeverNegative() {
        assertEquals(0, CoverageDisplay.lensLineOf(List.of(symbol("Foo", SymbolKind.CLASS, -5))));
    }

    @Test
    void realOutlineOfASourceFilePutsTheLensOnTheClassLine() {
        String source = """
                package com.app;

                import java.util.List;

                public class Calc {
                    public int value() {
                        return 1;
                    }
                }
                """;

        int line = CoverageDisplay.lensLineOf(JavaLexicalSource.outline(source));

        assertEquals("public class Calc {", source.split("\n")[line].trim());
    }
}
