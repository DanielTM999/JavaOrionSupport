package dtm.ide.test;

import java.util.List;

/**
 * Traducao de um escopo de teste para os seletores nativos do Surefire e do Gradle Test.
 *
 * <p>As duas ferramentas usam sintaxes diferentes: o Surefire recebe um unico
 * {@code -Dtest=...} com padroes separados por virgula, enquanto o Gradle recebe um par
 * {@code --tests &lt;padrao&gt;} por selecao.</p>
 */
public final class TestSelectors {

    private TestSelectors() {
    }

    /** Argumentos do Maven Surefire para o escopo informado. */
    public static List<String> maven(TestScope scope, String target) {
        String pattern = mavenPattern(scope, target);
        if (pattern == null) {
            return List.of();
        }
        return List.of("-Dtest=" + pattern, "-DfailIfNoTests=false");
    }

    /** Argumentos do Gradle Test para o escopo informado. */
    public static List<String> gradle(TestScope scope, String target) {
        String pattern = gradlePattern(scope, target);
        return pattern == null ? List.of() : List.of("--tests", pattern);
    }

    /** Argumentos para o build system do projeto. */
    public static List<String> forBuildTool(boolean gradle, TestScope scope, String target) {
        return gradle ? gradle(scope, target) : maven(scope, target);
    }

    static String mavenPattern(TestScope scope, String target) {
        String value = clean(target);
        if (scope == null || scope == TestScope.ALL) {
            return null;
        }
        if (value.isEmpty()) {
            return null;
        }
        return switch (scope) {
            case PACKAGE -> value.replace('.', '/') + "/**";
            case CLASS -> value;
            case METHOD -> methodSelector(value, "#");
            case PATTERN -> value;
            case ALL -> null;
        };
    }

    static String gradlePattern(TestScope scope, String target) {
        String value = clean(target);
        if (scope == null || scope == TestScope.ALL) {
            return null;
        }
        if (value.isEmpty()) {
            return null;
        }
        return switch (scope) {
            case PACKAGE -> value + ".*";
            case CLASS -> value;
            case METHOD -> methodSelector(value, ".");
            case PATTERN -> value;
            case ALL -> null;
        };
    }

    /**
     * Normaliza {@code com.foo.Bar#metodo} e {@code com.foo.Bar.metodo} para o separador
     * esperado pela ferramenta de destino.
     */
    private static String methodSelector(String value, String separator) {
        int hash = value.indexOf('#');
        if (hash > 0) {
            return value.substring(0, hash) + separator + value.substring(hash + 1);
        }
        return value;
    }

    /** Converte o alvo de uma configuracao em um {@link JavaTest}, quando aplicavel. */
    public static List<JavaTest> asTests(TestScope scope, String target) {
        String value = clean(target);
        if (value.isEmpty()) {
            return List.of();
        }
        if (scope == TestScope.CLASS) {
            return List.of(new JavaTest(value, "", "", null, 1, false));
        }
        if (scope == TestScope.METHOD) {
            int separator = Math.max(value.indexOf('#'), value.lastIndexOf('.'));
            if (separator <= 0 || separator == value.length() - 1) {
                return List.of();
            }
            return List.of(new JavaTest(value.substring(0, separator),
                    value.substring(separator + 1), "", null, 1, false));
        }
        return List.of();
    }

    private static String clean(String target) {
        return target == null ? "" : target.trim();
    }
}
