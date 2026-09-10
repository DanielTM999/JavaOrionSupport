package dtm.ide.deps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomEditorTest {

    private static final String POM_WITH_DEPENDENCIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>

                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>1.0.0</version>

                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.2</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static final String POM_WITHOUT_DEPENDENCIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>1.0.0</version>
            </project>
            """;

    @Test
    void readsDeclaredDependencies() {
        List<DependencyCoordinate> dependencies = PomEditor.readDependencies(POM_WITH_DEPENDENCIES);

        assertEquals(2, dependencies.size());
        assertEquals("org.springframework.boot:spring-boot-starter-web", dependencies.getFirst().key());
        assertFalse(dependencies.getFirst().hasVersion(), "a versao vem do starter-parent");
        assertTrue(dependencies.get(1).isTestScope());
        assertEquals("5.10.2", dependencies.get(1).version());
    }

    @Test
    void ignoresDependencyManagementEntries() {
        String pom = """
                <project>
                    <artifactId>demo</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.fasterxml.jackson</groupId>
                                <artifactId>jackson-bom</artifactId>
                                <version>2.17.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        List<DependencyCoordinate> dependencies = PomEditor.readDependencies(pom);

        assertEquals(1, dependencies.size());
        assertEquals("org.slf4j:slf4j-api", dependencies.getFirst().key());
    }

    @Test
    void readsNothingFromAMalformedPom() {
        assertTrue(PomEditor.readDependencies("<project><dependencies>").isEmpty());
        assertTrue(PomEditor.readDependencies("").isEmpty());
    }

    @Test
    void detectsAnExistingDependencyIgnoringItsVersion() {
        assertTrue(PomEditor.contains(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", "")));
        assertTrue(PomEditor.contains(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", "9.9.9")));
        assertFalse(PomEditor.contains(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13")));
    }

    @Test
    void addsADependencyToAnExistingBlock() {
        String result = PomEditor.addDependency(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        List<DependencyCoordinate> dependencies = PomEditor.readDependencies(result);
        assertEquals(3, dependencies.size());
        assertEquals("org.slf4j:slf4j-api", dependencies.get(2).key());
        assertEquals("2.0.13", dependencies.get(2).version());
    }

    @Test
    void keepsTheIndentationOfTheFile() {
        String result = PomEditor.addDependency(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.contains("        <dependency>\n            <groupId>org.slf4j</groupId>"),
                "a entrada nova deve seguir a indentacao das existentes");
    }

    @Test
    void preservesEverythingElseInTheFile() {
        String result = PomEditor.addDependency(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(result.contains("<modelVersion>4.0.0</modelVersion>"));
        assertTrue(result.contains("spring-boot-starter-web"));
        assertTrue(result.trim().endsWith("</project>"));
    }

    @Test
    void createsTheDependenciesBlockWhenThereIsNone() {
        String result = PomEditor.addDependency(POM_WITHOUT_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));

        assertTrue(result.contains("<dependencies>"));
        assertEquals(1, PomEditor.readDependencies(result).size());
        assertTrue(result.trim().endsWith("</project>"));
    }

    @Test
    void writesScopeOnlyWhenItIsNotTheDefault() {
        String compileScope = PomEditor.addDependency(POM_WITHOUT_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13"));
        String testScope = PomEditor.addDependency(POM_WITHOUT_DEPENDENCIES,
                new DependencyCoordinate("org.mockito", "mockito-core", "5.11.0", "test"));

        assertFalse(compileScope.contains("<scope>"));
        assertTrue(testScope.contains("<scope>test</scope>"));
    }

    @Test
    void omitsVersionWhenTheCoordinateHasNone() {
        String result = PomEditor.addDependency(POM_WITHOUT_DEPENDENCIES,
                DependencyCoordinate.of("org.springframework.boot", "spring-boot-starter-web", ""));

        List<DependencyCoordinate> dependencies = PomEditor.readDependencies(result);
        assertEquals(1, dependencies.size());
        assertFalse(dependencies.getFirst().hasVersion(),
                "sem versao declarada a dependencia herda do parent");
    }

    @Test
    void addingAnExistingArtifactUpdatesItInsteadOfDuplicating() {
        String result = PomEditor.addDependency(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", "5.11.0"));

        List<DependencyCoordinate> dependencies = PomEditor.readDependencies(result);
        assertEquals(2, dependencies.size());
        assertEquals("5.11.0", dependencies.get(1).version());
    }

    @Test
    void rejectsAnIncompleteCoordinate() {
        assertEquals(POM_WITH_DEPENDENCIES,
                PomEditor.addDependency(POM_WITH_DEPENDENCIES,
                        DependencyCoordinate.of("", "sem-grupo", "1.0")));
        assertEquals(POM_WITH_DEPENDENCIES, PomEditor.addDependency(POM_WITH_DEPENDENCIES, null));
    }

    @Test
    void removesADependencyAndLeavesNoBlankHole() {
        String result = PomEditor.removeDependency(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", ""));

        assertEquals(1, PomEditor.readDependencies(result).size());
        assertFalse(result.contains("junit-jupiter"));
        assertFalse(result.contains("\n\n    </dependencies>"), "nao deve sobrar linha vazia");
    }

    @Test
    void removingSomethingAbsentLeavesTheFileUntouched() {
        assertEquals(POM_WITH_DEPENDENCIES, PomEditor.removeDependency(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "")));
    }

    @Test
    void updatesAnExistingVersion() {
        String result = PomEditor.setVersion(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", ""), "5.11.3");

        assertTrue(result.contains("<version>5.11.3</version>"));
        assertFalse(result.contains("<version>5.10.2</version>"));
        assertTrue(result.contains("<version>1.0.0</version>"), "a versao do projeto nao muda");
    }

    @Test
    void addsAVersionToADependencyThatInheritedOne() {
        String result = PomEditor.setVersion(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.springframework.boot", "spring-boot-starter-web", ""),
                "3.3.4");

        DependencyCoordinate updated = PomEditor.readDependencies(result).getFirst();
        assertEquals("3.3.4", updated.version());
        assertEquals(2, PomEditor.readDependencies(result).size());
    }

    @Test
    void ignoresAnEmptyNewVersion() {
        assertEquals(POM_WITH_DEPENDENCIES, PomEditor.setVersion(POM_WITH_DEPENDENCIES,
                DependencyCoordinate.of("org.junit.jupiter", "junit-jupiter", ""), ""));
    }

    @Test
    void addingRemovingAndAddingAgainConvergesToTheSameFile() {
        DependencyCoordinate slf4j = DependencyCoordinate.of("org.slf4j", "slf4j-api", "2.0.13");

        String once = PomEditor.addDependency(POM_WITH_DEPENDENCIES, slf4j);
        String removed = PomEditor.removeDependency(once, slf4j);
        String twice = PomEditor.addDependency(removed, slf4j);

        assertEquals(POM_WITH_DEPENDENCIES, removed, "remover deve desfazer exatamente o adicionar");
        assertEquals(once, twice, "adicionar duas vezes deve dar o mesmo arquivo");
    }

    @Test
    void updatesALocalVersionPropertyWithoutReplacingThePlaceholder() {
        String pom = """
                <project>
                    <properties>
                        <itext.html.version>6.2.0</itext.html.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.itextpdf</groupId>
                            <artifactId>html2pdf</artifactId>
                            <version>${itext.html.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;

        assertTrue(PomEditor.hasProperty(pom, "itext.html.version"));
        String updated = PomEditor.setProperty(pom, "itext.html.version", "6.2.1");

        assertTrue(updated.contains("<itext.html.version>6.2.1</itext.html.version>"));
        assertTrue(updated.contains("<version>${itext.html.version}</version>"));
    }

    @Test
    void readsAndUpdatesOnlyTheDependencyManagementVersion() {
        String pom = """
                <project>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.16</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        DependencyCoordinate slf4j = DependencyCoordinate.of("org.slf4j", "slf4j-api", "");

        assertEquals("2.0.16", PomEditor.managedVersion(pom, slf4j));
        String updated = PomEditor.setManagedVersion(pom, slf4j, "2.0.17");

        assertEquals("2.0.17", PomEditor.managedVersion(updated, slf4j));
        assertTrue(updated.contains("<version>1.0.0</version>"));
        assertFalse(PomEditor.readDependencies(updated).getFirst().hasVersion());
    }
}
