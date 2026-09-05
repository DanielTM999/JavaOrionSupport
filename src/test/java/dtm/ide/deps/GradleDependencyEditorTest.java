package dtm.ide.deps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleDependencyEditorTest {

    private static final String GROOVY = """
            plugins {
                id 'java'
                id 'org.springframework.boot' version '3.3.4'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
            }
            """;

    private static final String KOTLIN = """
            plugins {
                id("java")
            }

            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
            }
            """;

    @Test
    void readsGroovyDependencies() {
        List<DependencyCoordinate> dependencies = GradleDependencyEditor.readDependencies(GROOVY);

        assertEquals(2, dependencies.size());
        assertEquals("org.springframework.boot:spring-boot-starter-web", dependencies.getFirst().key());
        assertFalse(dependencies.getFirst().hasVersion());
        assertEquals("5.10.2", dependencies.get(1).version());
        assertTrue(dependencies.get(1).isTestScope());
    }

    @Test
    void readsKotlinDependencies() {
        List<DependencyCoordinate> dependencies = GradleDependencyEditor.readDependencies(KOTLIN);

        assertEquals(2, dependencies.size());
        assertEquals("org.junit.jupiter:junit-jupiter", dependencies.get(1).key());
    }

    @Test
    void doesNotConfusePluginsWithDependencies() {
        List<DependencyCoordinate> dependencies = GradleDependencyEditor.readDependencies(GROOVY);

        assertFalse(dependencies.stream()
                .anyMatch(dependency -> dependency.artifactId().contains("springframework.boot")),
                "a linha do bloco plugins nao pode virar dependencia");
    }

    @Test
    void ignoresCommentedOutDependencies() {
        String script = """
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.13'
                    // implementation 'com.google.guava:guava:33.0.0-jre'
                    /* implementation 'org.apache.commons:commons-lang3:3.14.0' */
                }
                """;

        List<DependencyCoordinate> dependencies = GradleDependencyEditor.readDependencies(script);

        assertEquals(1, dependencies.size());
        assertEquals("org.slf4j:slf4j-api", dependencies.getFirst().key());
    }

    @Test
    void handlesNestedBlocksInsideDependencies() {
        String script = """
                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
                    implementation 'org.slf4j:slf4j-api:2.0.13'
                    testImplementation('org.mockito:mockito-core:5.11.0') {
                        exclude group: 'net.bytebuddy'
                    }
                }
                """;

        List<DependencyCoordinate> dependencies = GradleDependencyEditor.readDependencies(script);

        assertTrue(dependencies.stream().anyMatch(d -> d.key().equals("org.slf4j:slf4j-api")));
        assertTrue(dependencies.stream().anyMatch(d -> d.key().equals("org.mockito:mockito-core")));
    }

    @Test
    void readsNothingWhenThereIsNoDependenciesBlock() {
        assertTrue(GradleDependencyEditor.readDependencies("plugins { id 'java' }").isEmpty());
        assertTrue(GradleDependencyEditor.readDependencies("").isEmpty());
    }

    @Test
    void addsUsingTheGroovySyntaxOfTheFile() {
        String result = GradleDependencyEditor.addDependency(GROOVY,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.contains("implementation 'org.slf4j:slf4j-api:2.0.13'"));
        assertFalse(result.contains("implementation(\"org.slf4j"));
        assertEquals(3, GradleDependencyEditor.readDependencies(result).size());
    }

    @Test
    void addsUsingTheKotlinSyntaxOfTheFile() {
        String result = GradleDependencyEditor.addDependency(KOTLIN,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.contains("implementation(\"org.slf4j:slf4j-api:2.0.13\")"));
        assertEquals(3, GradleDependencyEditor.readDependencies(result).size());
    }

    @Test
    void mapsMavenScopesToGradleConfigurations() {
        String result = GradleDependencyEditor.addDependency(GROOVY,
                new DependencyCoordinate("org.mockito", "mockito-core", "5.11.0", "test"));

        assertTrue(result.contains("testImplementation 'org.mockito:mockito-core:5.11.0'"));
    }

    @Test
    void acceptsAGradleConfigurationDirectly() {
        String result = GradleDependencyEditor.addDependency(GROOVY,
                new DependencyCoordinate("org.projectlombok", "lombok", "1.18.42", "annotationProcessor"));

        assertTrue(result.contains("annotationProcessor 'org.projectlombok:lombok:1.18.42'"));
    }

    @Test
    void createsTheBlockWhenTheScriptHasNone() {
        String result = GradleDependencyEditor.addDependency("plugins {\n    id 'java'\n}\n",
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.contains("dependencies {"));
        assertEquals(1, GradleDependencyEditor.readDependencies(result).size());
    }

    @Test
    void preservesTheRestOfTheScript() {
        String result = GradleDependencyEditor.addDependency(GROOVY,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.contains("id 'org.springframework.boot' version '3.3.4'"));
        assertTrue(result.contains("mavenCentral()"));
    }

    @Test
    void addingAnExistingArtifactReplacesTheLine() {
        String result = GradleDependencyEditor.addDependency(GROOVY,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", "5.11.0"));

        List<DependencyCoordinate> dependencies = GradleDependencyEditor.readDependencies(result);
        assertEquals(2, dependencies.size());
        assertEquals("5.11.0", dependencies.get(1).version());
        assertTrue(dependencies.get(1).isTestScope(), "a configuracao original deve ser mantida");
    }

    @Test
    void removesADependencyLine() {
        String result = GradleDependencyEditor.removeDependency(GROOVY,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", ""));

        assertEquals(1, GradleDependencyEditor.readDependencies(result).size());
        assertFalse(result.contains("junit-jupiter"));
    }

    @Test
    void removingSomethingAbsentLeavesTheScriptUntouched() {
        assertEquals(GROOVY, GradleDependencyEditor.removeDependency(GROOVY,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "")));
    }

    @Test
    void updatesAVersionKeepingTheConfiguration() {
        String result = GradleDependencyEditor.setVersion(GROOVY,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", ""), "5.11.3");

        assertTrue(result.contains("testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'"));
        assertFalse(result.contains("5.10.2"));
    }

    @Test
    void addingAndRemovingRoundTrips() {
        DependencyCoordinate slf4j = DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13");

        String once = GradleDependencyEditor.addDependency(GROOVY, slf4j);
        String removed = GradleDependencyEditor.removeDependency(once, slf4j);

        assertEquals(GROOVY, removed);
    }
}
