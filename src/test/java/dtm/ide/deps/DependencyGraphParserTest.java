package dtm.ide.deps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphParserTest {

    @Test
    void parsesMavenPathsAndConflicts() {
        List<ResolvedDependency> dependencies = DependencyGraphParser.parseMaven(List.of(
                "[INFO] +- org.springframework:spring-core:jar:6.2.1:compile",
                "[INFO] |  \\- commons-logging:commons-logging:jar:1.2:compile",
                "[INFO] \\- org.slf4j:slf4j-api:jar:2.0.16:compile",
                "[INFO]    \\- org.slf4j:slf4j-api:jar:1.7.36:compile - omitted for conflict with 2.0.16"));

        assertEquals(4, dependencies.size());
        assertEquals(List.of("org.springframework:spring-core",
                "commons-logging:commons-logging"), dependencies.get(1).path());
        assertTrue(dependencies.get(2).conflict());
        assertTrue(dependencies.get(3).conflict());
        assertEquals("2.0.16", dependencies.get(3).coordinate().version());
        assertEquals("1.7.36", dependencies.get(3).requestedVersion());
    }

    @Test
    void parsesGradleSelectedVersionsAndTransitivePaths() {
        List<ResolvedDependency> dependencies = DependencyGraphParser.parseGradle(List.of(
                "+--- org.springframework:spring-core:6.2.1",
                "|    \\--- commons-logging:commons-logging:1.2",
                "\\--- org.slf4j:slf4j-api:1.7.36 -> 2.0.16"));

        assertEquals(3, dependencies.size());
        assertTrue(dependencies.get(1).transitive());
        assertEquals(List.of("org.springframework:spring-core",
                "commons-logging:commons-logging"), dependencies.get(1).path());
        assertTrue(dependencies.get(2).conflict());
        assertEquals("2.0.16", dependencies.get(2).coordinate().version());
    }

    @Test
    void ignoresNoiseAndEmptyInput() {
        assertTrue(DependencyGraphParser.parseMaven(List.of("[INFO] BUILD SUCCESS")).isEmpty());
        assertTrue(DependencyGraphParser.parseGradle(null).isEmpty());
        assertFalse(DependencyGraphParser.parseMaven(List.of(
                "[INFO] \\- g:a:jar:1.0:compile")).isEmpty());
    }
}
