package dtm.ide.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLocalScopeTest {

    private static final String SOURCE = """
            package demo;

            public final class PedidoService {
                private String motivo;

                public void falharDeProposito(String motivo) {
                    throw new IllegalStateException("motivo " + motivo);
                }

                public String outro(String motivo) {
                    return motivo;
                }
            }
            """;

    @Test
    void scopesAParameterToItsOwnMethod() {
        JavaLocalScope.Scope scope = scopeAt(5, "motivo");

        assertNotNull(scope);
        assertEquals("motivo", scope.name());
        assertTrue(scope.onDeclaration());
        assertEquals(5, scope.declaration().start().line());
        assertEquals(5, scope.startLine());
        assertEquals(7, scope.endLine());
        assertEquals(List.of(6), lines(scope));
    }

    @Test
    void resolvesTheDeclarationWhenTheCaretSitsOnAUsage() {
        JavaLocalScope.Scope scope = scopeAt(10, "motivo");

        assertNotNull(scope);
        assertFalse(scope.onDeclaration());
        assertEquals(9, scope.declaration().start().line());
        assertEquals(List.of(10), lines(scope));
    }

    @Test
    void ignoresFieldsTypesAndMethodNames() {
        assertNull(scopeAt(3, "motivo"));
        assertNull(scopeAt(5, "falharDeProposito"));
        assertNull(scopeAt(6, "IllegalStateException"));
    }

    @Test
    void declaresNoScopeForLocalVariablesOutsideAnyMethod() {
        assertNull(JavaLocalScope.at("class Demo { int total = 1; }", 0, 17));
        assertNull(JavaLocalScope.at(null, 0, 0));
    }

    @Test
    void skipsOccurrencesInsideStringsAndComments() {
        String source = """
                class Demo {
                    int total(int amount) {
                        // amount ignored
                        String label = "amount";
                        return amount;
                    }
                }
                """;

        JavaLocalScope.Scope scope = JavaLocalScope.at(source, 1,
                source.lines().toList().get(1).indexOf("amount") + 1);

        assertNotNull(scope);
        assertEquals(List.of(4), lines(scope));
    }

    @Test
    void keepsLocalsOfOneMethodOutOfAnotherMethodWithTheSameNames() {
        String source = """
                package demo;

                public final class PedidoService {
                    public BigDecimal calcularTotal(List<ItemPedido> itens, BigDecimal desconto) {
                        BigDecimal subtotal = BigDecimal.ZERO;
                        for (ItemPedido item : itens) {
                            subtotal = subtotal.add(item.subtotal());
                        }
                        return subtotal.subtract(desconto);
                    }

                    public void falharDeProposito(String motivo) {
                        throw new IllegalStateException("Falha controlada: " + motivo);
                    }
                }
                """;

        JavaLocalScope.Scope parameter = at(source, 11, "motivo");
        assertNotNull(parameter);
        assertTrue(parameter.onDeclaration());
        assertEquals(11, parameter.startLine());
        assertEquals(13, parameter.endLine());
        assertEquals(List.of(12), lines(parameter));

        JavaLocalScope.Scope usage = at(source, 12, "motivo");
        assertNotNull(usage);
        assertFalse(usage.onDeclaration());
        assertEquals(11, usage.declaration().start().line());

        JavaLocalScope.Scope local = at(source, 4, "subtotal");
        assertNotNull(local);
        assertEquals(3, local.startLine());
        assertEquals(9, local.endLine());
        assertEquals(List.of(6, 6, 8), lines(local));

        assertNull(at(source, 3, "calcularTotal"));
        assertNull(at(source, 12, "IllegalStateException"));
    }

    private static JavaLocalScope.Scope at(String source, int line, String token) {
        String text = source.lines().toList().get(line);
        return JavaLocalScope.at(source, line, text.indexOf(token) + 1);
    }

    private static JavaLocalScope.Scope scopeAt(int line, String token) {
        String text = SOURCE.lines().toList().get(line);
        return JavaLocalScope.at(SOURCE, line, text.indexOf(token) + 1);
    }

    private static List<Integer> lines(JavaLocalScope.Scope scope) {
        return scope.usages().stream().map(range -> range.start().line()).toList();
    }
}
