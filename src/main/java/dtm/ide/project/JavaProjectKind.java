package dtm.ide.project;

import java.util.Locale;

public enum JavaProjectKind {

    MAVEN("maven"),

    MAVEN_MULTIMODULE("maven-multimodule"),

    GRADLE("gradle"),

    GRADLE_MULTIPROJECT("gradle-multiproject"),

    PLAIN_JAVA("plain-java"),

    JAVA_WORKSPACE("java-workspace");

    private final String key;

    JavaProjectKind(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean isMaven() {
        return this == MAVEN || this == MAVEN_MULTIMODULE;
    }

    public boolean isGradle() {
        return this == GRADLE || this == GRADLE_MULTIPROJECT;
    }

    public boolean isMultiModule() {
        return this == MAVEN_MULTIMODULE || this == GRADLE_MULTIPROJECT || this == JAVA_WORKSPACE;
    }

    public boolean hasBuildTool() {
        return isMaven() || isGradle();
    }

    public static JavaProjectKind fromKey(String key) {
        if (key == null || key.isBlank()) {
            return PLAIN_JAVA;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (JavaProjectKind kind : values()) {
            if (kind.key.equals(normalized)) {
                return kind;
            }
        }
        return PLAIN_JAVA;
    }
}
