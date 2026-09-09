package dtm.ide.spring.jpa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaDerivedQueryTest {

    @Test
    void readsASingleCondition() {
        JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse("findByNome");

        assertTrue(parsed.derived());
        assertEquals(JpaQueryMethod.Subject.FIND, parsed.subject());
        assertEquals(List.of("Nome"), parsed.conditions());
    }

    @Test
    void splitsConditionsOnAndOr() {
        JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse("findByNomeAndIdadeOrEmail");

        assertEquals(List.of("Nome", "Idade", "Email"), parsed.conditions());
    }

    @Test
    void readsCountAndExistsAndDeleteSubjects() {
        assertEquals(JpaQueryMethod.Subject.COUNT,
                JpaDerivedQuery.parse("countByAtivo").subject());
        assertEquals(JpaQueryMethod.Subject.EXISTS,
                JpaDerivedQuery.parse("existsByEmail").subject());
        assertEquals(JpaQueryMethod.Subject.DELETE,
                JpaDerivedQuery.parse("deleteByEmail").subject());
        assertEquals(JpaQueryMethod.Subject.DELETE,
                JpaDerivedQuery.parse("removeByEmail").subject());
    }

    @Test
    void skipsLimiterKeywordsBeforeBy() {
        JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse("findTop10DistinctByNome");

        assertTrue(parsed.derived());
        assertEquals(List.of("Nome"), parsed.conditions());
    }

    @Test
    void separatesOrderByFromConditions() {
        JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse("findByNomeOrderByIdadeDesc");

        assertEquals(List.of("Nome"), parsed.conditions());
        assertEquals(List.of("Idade"), JpaDerivedQuery.orderProperties(parsed.orderBy()));
    }

    @Test
    void readsFindAllAsDerivedWithoutConditions() {
        JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse("findAll");

        assertTrue(parsed.derived());
        assertTrue(parsed.conditions().isEmpty());
    }

    @Test
    void ignoresMethodsThatAreNotQueries() {
        assertFalse(JpaDerivedQuery.parse("save").derived());
        assertFalse(JpaDerivedQuery.parse("flush").derived());
        assertFalse(JpaDerivedQuery.parse("getEntityManager").derived());
    }

    @Test
    void stripsPredicateKeywordsFromCondition() {
        assertEquals("Idade", JpaDerivedQuery.stripKeywords("IdadeGreaterThanEqual"));
        assertEquals("Nome", JpaDerivedQuery.stripKeywords("NomeContainingIgnoreCase"));
        assertEquals("Criacao", JpaDerivedQuery.stripKeywords("CriacaoBetween"));
        assertEquals("Ativo", JpaDerivedQuery.stripKeywords("AtivoIsTrue"));
    }

    @Test
    void keepsPropertyWhenNothingToStrip() {
        assertEquals("Nome", JpaDerivedQuery.stripKeywords("Nome"));
        assertEquals(List.of("Nome", "Nome"), List.of(
                JpaDerivedQuery.propertyCandidates("Nome").getFirst(),
                JpaDerivedQuery.propertyCandidates("Nome").getLast()));
    }

    @Test
    void offersStrippedAndRawCandidates() {
        List<String> candidates = JpaDerivedQuery.propertyCandidates("NomeContaining");

        assertEquals(List.of("Nome", "NomeContaining"), candidates);
    }

    @Test
    void doesNotSplitPropertiesThatMerelyContainAnd() {
        JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse("findByAndroidId");

        assertEquals(List.of("AndroidId"), parsed.conditions());
    }
}
