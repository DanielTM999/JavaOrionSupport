package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CompletionRankingTest {

    @Test
    void emptyPrefixKeepsTheSourceOrder() {
        List<AutoCompleteItem> items = List.of(item("zeta"), item("alpha"));

        assertSame(items, CompletionRanking.rank(items, ""));
    }

    @Test
    void ranksExactThenCaseSensitivePrefixThenIgnoringCaseThenHumpsThenContains() {
        List<AutoCompleteItem> items = List.of(
                item("unrelated"),
                item("cachedAutoMapper"),
                item("aMapRef"),
                item("autoMapperReference"),
                item("AutoMapper"),
                item("autoMapper() : AutoMapper"));

        assertEquals(List.of("autoMapper() : AutoMapper", "autoMapperReference", "AutoMapper",
                        "cachedAutoMapper", "unrelated", "aMapRef"),
                labels(CompletionRanking.rank(items, "autoMapper")));
        assertEquals(List.of("aMapRef", "autoMapperReference", "unrelated", "cachedAutoMapper",
                        "AutoMapper", "autoMapper() : AutoMapper"),
                labels(CompletionRanking.rank(items, "aMR")));
    }

    @Test
    void camelHumpsBeatPlainSubstringMatches() {
        List<AutoCompleteItem> items = List.of(
                item("recoveryCvr"),
                item("consultaVeicularRepository"));

        assertEquals(List.of("consultaVeicularRepository", "recoveryCvr"),
                labels(CompletionRanking.rank(items, "cvr")));
    }

    @Test
    void keepsTheSourceOrderInsideTheSameTier() {
        List<AutoCompleteItem> items = List.of(
                item("getB"),
                item("other"),
                item("getA"),
                item("getC"));

        assertEquals(List.of("getB", "getA", "getC", "other"),
                labels(CompletionRanking.rank(items, "get")));
    }

    @Test
    void exactSnippetGoesToTheTopEvenWhenItCameLast() {
        List<AutoCompleteItem> items = List.of(
                item("southRegion"),
                item("source"),
                AutoCompleteItem.snippet("sout", "System.out.println($0);"));

        assertEquals("sout", CompletionRanking.rank(items, "sout").getFirst().label());
    }

    @Test
    void nameStopsAtTheParameterListOrTheFirstSpace() {
        assertEquals("getAutoMapper", CompletionRanking.name(item("getAutoMapper() : AutoMapper")));
        assertEquals("AutoMapper", CompletionRanking.name(item("AutoMapper - com.acme")));
    }

    private static AutoCompleteItem item(String label) {
        return new AutoCompleteItem(label, label, null, null, null, AutoCompleteItem.Kind.METHOD);
    }

    private static List<String> labels(List<AutoCompleteItem> items) {
        return items.stream().map(AutoCompleteItem::label).toList();
    }
}
