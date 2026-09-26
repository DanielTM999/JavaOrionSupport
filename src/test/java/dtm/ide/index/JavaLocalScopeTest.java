package dtm.ide.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLocalScopeTest {

    @Test
    void typeNamesDoNotBecomeUsagesOfEquallyNamedLocals() {
        String source = "class A { void m(Object value) { Object String=null; use((String)value); } }";
        var symbol = JavaLocalScope.at(source, 0, source.indexOf("String=null"));
        assertNotNull(symbol);
        assertTrue(symbol.usages().isEmpty());
    }

    @Test
    void unicodeColumnsAndCrLfArePreserved() {
        String source = "class A {\r\n void m(int valor) { String s=\"\ud83d\ude00\"; use(valor + valor); }\r\n}";
        var scope = JavaLocalScope.at(source, 1, source.lines().toList().get(1).indexOf("valor"));
        assertNotNull(scope);
        assertEquals(2, scope.usages().size());
        assertEquals(source.lines().toList().get(1).lastIndexOf("valor"), scope.usages().getLast().start().col());
    }

    @Test
    void qualifiedFieldNeverResolvesToAnEquallyNamedParameter() {
        String source = "class A { int x; void m(int x) { this.x = x; } }";
        assertNull(JavaLocalScope.at(source, 0, source.indexOf("this.x") + 5));
        var parameter = JavaLocalScope.at(source, 0, source.lastIndexOf("x;"));
        assertNotNull(parameter);
        assertEquals(source.indexOf("int x)") + 4, parameter.declaration().start().col());
        assertEquals(1, parameter.usages().size());
    }

    @Test
    void siblingBlocksBindTheirOwnVariables() {
        String source = "class A { void m() { { int x=1; use(x); } { int x=2; use(x); } } }";
        var second = JavaLocalScope.at(source, 0, source.lastIndexOf("use(x)") + 4);
        assertNotNull(second);
        assertEquals(source.lastIndexOf("int x") + 4, second.declaration().start().col());
        assertEquals(1, second.usages().size());
    }

    @Test
    void loopCatchResourceAndLambdaVariablesHaveSeparateScopes() {
        String source = """
                class A {
                    void m() {
                        for (int x=0; x<2; x++) { use(x); }
                        for (String x : values) { use(x); }
                        try (var x = open()) { use(x); } catch (Exception x) { use(x); }
                        values.forEach(x -> use(x));
                        use(x);
                    }
                }
                """;
        assertEquals(3, at(source, 2, "x=0").usages().size());
        assertEquals(1, at(source, 3, "x :").usages().size());
        assertEquals(1, at(source, 4, "x =").usages().size());
        assertEquals(1, at(source, 5, "x ->").usages().size());
        assertNull(at(source, 6, "x"));
    }

    @Test
    void capturesOuterParameterWithoutIncludingAnInnerClassField() {
        String source = "class A { void m(int x) { run(() -> use(x)); class B { int x; void n() { use(x); } } } }";
        var scope = JavaLocalScope.at(source, 0, source.indexOf("int x)") + 4);
        assertNotNull(scope);
        assertEquals(1, scope.usages().size());
        assertNull(JavaLocalScope.at(source, 0, source.lastIndexOf("use(x)") + 4));
    }

    @Test
    void doesNotBindAUseBeforeTheLocalDeclaration() {
        String source = "class A { int x; void m() { use(x); int x=1; use(x); } }";
        assertNull(JavaLocalScope.at(source, 0, source.indexOf("use(x)") + 4));
        assertNotNull(JavaLocalScope.at(source, 0, source.lastIndexOf("use(x)") + 4));
    }

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

    @Test
    void aSwitchLocalDoesNotHideAFieldAfterTheSwitch() {
        String source = """
                class Demo {
                    int value;
                    void run(int n) {
                        switch (n) {
                            case 1: int value = n; use(value); break;
                            default: break;
                        }
                        use(value);
                    }
                }
                """;
        assertEquals(List.of(4), lines(at(source, 4, "value")));
        assertNull(at(source, 7, "value"));
    }

    @Test
    void cacheSurvivesNavigationAcrossManyFiles() {
        for (int i = 0; i < 24; i++) {
            String source = "class F" + i + " { void m() { int value" + i + " = " + i + "; use(value" + i + "); } }";
            assertNotNull(JavaLocalScope.at(source, 0, source.indexOf("value" + i + " =")));
        }
        String first = "class F0 { void m() { int value0 = 0; use(value0); } }";
        long before = JavaLocalScope.PARSES.get();
        assertNotNull(JavaLocalScope.at(first, 0, first.indexOf("value0 =")));
        assertEquals(before, JavaLocalScope.PARSES.get());
    }

    @Test
    void preloadFeedsTheCacheUsedByNavigation() {
        String source = "class Preloaded { void m() { int amount = 3; use(amount); } }";
        JavaLocalScope.preload(source);
        long before = JavaLocalScope.PARSES.get();
        assertNotNull(JavaLocalScope.at(source, 0, source.indexOf("amount =")));
        assertEquals(before, JavaLocalScope.PARSES.get());
    }

    @Test
    void warmUpDoesNotBreakSubsequentParses() {
        JavaLocalScope.warmUp();
        String source = "class AfterWarmUp { void m() { int total = 7; use(total); } }";
        assertNotNull(JavaLocalScope.at(source, 0, source.indexOf("total =")));
    }

    private static JavaLocalScope.Scope at(String source, int line, String token) {
        String text = source.lines().toList().get(line);
        return JavaLocalScope.at(source, line, text.indexOf(token));
    }

    private static JavaLocalScope.Scope scopeAt(int line, String token) {
        String text = SOURCE.lines().toList().get(line);
        return JavaLocalScope.at(SOURCE, line, text.indexOf(token));
    }

    @Test
    void visibleAtListsWhatAConditionCanUseOnThatLine() {
        String source = """
                class Order {
                    private int total;
                    void apply(String user, int count) {
                        int before = count;
                        if (count > 1) {
                            int inner = 2;
                        }
                        int after = 3;
                        use(before);
                    }
                    void other(int unrelated) {
                    }
                }
                """;

        List<JavaLocalScope.Visible> visible = JavaLocalScope.visibleAt(source, 8);

        List<String> names = visible.stream().map(JavaLocalScope.Visible::name).toList();
        assertTrue(names.containsAll(List.of("before", "user", "count", "total", "after")), names.toString());
        assertFalse(names.contains("inner"), names.toString());
        assertFalse(names.contains("unrelated"), names.toString());
        assertEquals(JavaLocalScope.Kind.PARAMETER, kindOf(visible, "user"));
        assertEquals(JavaLocalScope.Kind.LOCAL, kindOf(visible, "before"));
        assertEquals(JavaLocalScope.Kind.FIELD, kindOf(visible, "total"));
        assertTrue(names.indexOf("before") < names.indexOf("total"), "locais antes dos campos");
    }

    @Test
    void aLocalIsNotVisibleOnItsOwnDeclarationLineNorBefore() {
        String source = """
                class A {
                    void m(int value) {
                        use(value);
                        int later = value;
                    }
                }
                """;

        List<String> onUse = JavaLocalScope.visibleAt(source, 2).stream().map(JavaLocalScope.Visible::name).toList();
        List<String> onDeclaration = JavaLocalScope.visibleAt(source, 3).stream().map(JavaLocalScope.Visible::name).toList();

        assertFalse(onUse.contains("later"), onUse.toString());
        assertFalse(onDeclaration.contains("later"), onDeclaration.toString());
        assertTrue(onDeclaration.contains("value"), onDeclaration.toString());
    }

    @Test
    void brokenSourceHasNothingVisible() {
        assertTrue(JavaLocalScope.visibleAt("class A { void m( { }", 0).isEmpty());
        assertTrue(JavaLocalScope.visibleAt(null, 0).isEmpty());
    }

    private static JavaLocalScope.Kind kindOf(List<JavaLocalScope.Visible> visible, String name) {
        return visible.stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow().kind();
    }

    private static List<Integer> lines(JavaLocalScope.Scope scope) {
        return scope.usages().stream().map(range -> range.start().line()).toList();
    }
}
