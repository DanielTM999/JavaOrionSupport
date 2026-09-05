package dtm.ide.sdk;

import java.util.Locale;

public enum JdkVendor {

    TEMURIN("Eclipse Temurin", "temurin"),
    ORACLE("Oracle", "oracle"),
    OPENJDK("OpenJDK", "openjdk"),
    CORRETTO("Amazon Corretto", "corretto"),
    ZULU("Azul Zulu", "zulu"),
    MICROSOFT("Microsoft OpenJDK", "microsoft"),
    LIBERICA("BellSoft Liberica", "liberica"),
    GRAALVM("GraalVM", "graalvm"),
    SEMERU("IBM Semeru", "semeru"),
    UNKNOWN("JDK", "unknown");

    private final String displayName;
    private final String key;

    JdkVendor(String displayName, String key) {
        this.displayName = displayName;
        this.key = key;
    }

    public String displayName() {
        return displayName;
    }

    public String key() {
        return key;
    }

    public static JdkVendor fromText(String... hints) {
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) {
                continue;
            }
            String value = hint.toLowerCase(Locale.ROOT);
            if (value.contains("temurin") || value.contains("adoptium") || value.contains("adoptopenjdk")) {
                return TEMURIN;
            }
            if (value.contains("graalvm")) {
                return GRAALVM;
            }
            if (value.contains("corretto") || value.contains("amazon")) {
                return CORRETTO;
            }
            if (value.contains("zulu") || value.contains("azul")) {
                return ZULU;
            }
            if (value.contains("microsoft")) {
                return MICROSOFT;
            }
            if (value.contains("liberica") || value.contains("bellsoft")) {
                return LIBERICA;
            }
            if (value.contains("semeru") || value.contains("ibm")) {
                return SEMERU;
            }
            if (value.contains("oracle")) {
                return ORACLE;
            }
            if (value.contains("openjdk")) {
                return OPENJDK;
            }
        }
        return UNKNOWN;
    }
}
