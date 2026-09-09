package dtm.ide.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InjectionTargetTest {

    @Test
    void keepsASingularTypeUntouched() {
        InjectionTarget target = InjectionTarget.of("Envio");

        assertEquals("Envio", target.type());
        assertFalse(target.expectsMany());
        assertFalse(target.optional());
    }

    @Test
    void unwrapsCollections() {
        assertEquals("Envio", InjectionTarget.of("List<Envio>").type());
        assertTrue(InjectionTarget.of("List<Envio>").expectsMany());
        assertTrue(InjectionTarget.of("Set<Envio>").expectsMany());
        assertTrue(InjectionTarget.of("Collection<Envio>").expectsMany());
    }

    @Test
    void unwrapsTheValueTypeOfAMap() {
        InjectionTarget target = InjectionTarget.of("Map<String, Envio>");

        assertEquals("Envio", target.type());
        assertTrue(target.expectsMany());
    }

    @Test
    void marksOptionalWrappersAsOptional() {
        assertTrue(InjectionTarget.of("Optional<Envio>").optional());
        assertTrue(InjectionTarget.of("ObjectProvider<Envio>").optional());
        assertTrue(InjectionTarget.of("Provider<Envio>").optional());
        assertEquals("Envio", InjectionTarget.of("Optional<Envio>").type());
        assertFalse(InjectionTarget.of("Optional<Envio>").expectsMany());
    }

    @Test
    void combinesOptionalWithCollection() {
        InjectionTarget target = InjectionTarget.of("Optional<List<Envio>>");

        assertEquals("Envio", target.type());
        assertTrue(target.optional());
        assertTrue(target.expectsMany());
    }

    @Test
    void keepsQualifiedNames() {
        assertEquals("com.example.Envio",
                InjectionTarget.of("java.util.List<com.example.Envio>").type());
    }

    @Test
    void doesNotUnwrapAGenericTypeThatIsNotAContainer() {
        InjectionTarget target = InjectionTarget.of("Repositorio<Envio>");

        assertEquals("Repositorio<Envio>", target.type());
        assertFalse(target.expectsMany());
        assertEquals("Repositorio", target.simpleName());
    }
}
