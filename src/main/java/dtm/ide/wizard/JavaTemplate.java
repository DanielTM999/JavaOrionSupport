package dtm.ide.wizard;

import java.util.List;

public enum JavaTemplate {

    MAVEN_APPLICATION("Aplicacao Maven",
            "Projeto Maven com classe principal e wrapper."),
    MAVEN_LIBRARY("Biblioteca Maven",
            "Projeto Maven sem classe principal, pronto para publicar."),
    GRADLE_APPLICATION("Aplicacao Gradle",
            "Projeto Gradle com Kotlin DSL e classe principal."),
    MAVEN_MULTIMODULE("Multi-modulo Maven",
            "Pom agregador com modulos filhos."),
    PLAIN_JAVA("Java simples",
            "Pasta com fontes e nenhum build system.");

    private final String displayName;
    private final String description;

    JavaTemplate(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public boolean isMaven() {
        return this == MAVEN_APPLICATION || this == MAVEN_LIBRARY || this == MAVEN_MULTIMODULE;
    }

    public boolean isGradle() {
        return this == GRADLE_APPLICATION;
    }

    public boolean hasMainClass() {
        return this == MAVEN_APPLICATION || this == GRADLE_APPLICATION || this == PLAIN_JAVA;
    }

    public boolean needsCoordinates() {
        return isMaven() || isGradle();
    }

    public static List<JavaTemplate> available() {
        return List.of(values());
    }
}
