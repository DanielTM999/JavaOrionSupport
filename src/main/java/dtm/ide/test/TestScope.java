package dtm.ide.test;

import java.util.Arrays;
import java.util.Locale;

/** Escopo de selecao de testes de uma configuracao {@code java.test}. */
public enum TestScope {

    /** Todos os testes do modulo. */
    ALL,
    /** Todos os testes de um pacote. */
    PACKAGE,
    /** Uma classe de teste. */
    CLASS,
    /** Um metodo de uma classe de teste. */
    METHOD,
    /** Um padrao livre repassado ao build tool. */
    PATTERN;

    /** {@code true} quando o escopo exige um alvo preenchido. */
    public boolean requiresTarget() {
        return this != ALL;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TestScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scope -> scope.name().equals(value))
                .findFirst()
                .orElse(ALL);
    }
}
