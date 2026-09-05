package dtm.ide.build;

import dtm.ide.project.JavaProjectConventions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolModelTest {

    @TempDir
    Path root;

    @Test
    void loadsMavenLifecycleDependenciesProfilesAndPlugins() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>sample</groupId><artifactId>demo</artifactId><version>1</version>
                  <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId></dependency></dependencies>
                  <profiles><profile><id>development</id></profile></profiles>
                  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId></plugin></plugins></build>
                </project>
                """);

        BuildToolModel model = BuildToolModel.load(JavaProjectConventions.describe(root));
        var project = model.projects().getFirst();

        assertEquals("Maven", model.tool());
        assertTrue(project.children().stream().flatMap(node -> node.children().stream())
                .anyMatch(node -> node.name().equals("compile") && node.executable()));
        assertTrue(project.children().stream().flatMap(node -> node.children().stream())
                .anyMatch(node -> node.name().equals("org.junit.jupiter:junit-jupiter")));
        assertTrue(project.children().stream().flatMap(node -> node.children().stream())
                .anyMatch(node -> node.name().equals("surefire")
                        && node.detail().equals("org.apache.maven.plugins:maven-surefire-plugin")));
    }

    @Test
    void profilesLiveAtTheTopAndNotInsideEachProject() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>sample</groupId><artifactId>demo</artifactId><version>1</version>
                  <profiles>
                    <profile><id>development</id></profile>
                    <profile><id>windows</id></profile>
                  </profiles>
                </project>
                """);

        BuildToolModel model = BuildToolModel.load(JavaProjectConventions.describe(root));

        assertEquals(List.of("development", "windows"),
                model.profiles().stream().map(BuildToolModel.Node::name).toList());
        assertTrue(model.projects().getFirst().children().stream()
                .flatMap(node -> node.children().stream())
                .noneMatch(node -> node.kind() == BuildToolModel.Kind.PROFILE));
    }

    @Test
    void dependenciesCarryTheirScopeAsDetail() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>sample</groupId><artifactId>demo</artifactId><version>1</version>
                  <dependencies><dependency>
                    <groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                    <version>1.18.42</version><scope>provided</scope>
                  </dependency></dependencies>
                </project>
                """);

        BuildToolModel model = BuildToolModel.load(JavaProjectConventions.describe(root));
        var dependency = model.projects().getFirst().children().stream()
                .flatMap(node -> node.children().stream())
                .filter(node -> node.kind() == BuildToolModel.Kind.DEPENDENCY)
                .findFirst()
                .orElseThrow();

        assertEquals("org.projectlombok:lombok:1.18.42", dependency.name());
        assertEquals("(provided)", dependency.detail());
    }

    @Test
    void repositoriesAreListed() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>sample</groupId><artifactId>demo</artifactId><version>1</version>
                  <repositories><repository>
                    <id>central</id><url>https://repo1.maven.org/maven2</url>
                  </repository></repositories>
                </project>
                """);

        BuildToolModel model = BuildToolModel.load(JavaProjectConventions.describe(root));
        var repository = model.projects().getFirst().children().stream()
                .flatMap(node -> node.children().stream())
                .filter(node -> node.kind() == BuildToolModel.Kind.REPOSITORY)
                .findFirst()
                .orElseThrow();

        assertEquals("central", repository.name());
        assertEquals("https://repo1.maven.org/maven2", repository.detail());
    }

    @Test
    void loadsGradleRegisteredTasksAndDependencies() throws IOException {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'demo'");
        Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'java' }
                dependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2' }
                tasks.register('integrationTest') { }
                task generateSources { }
                """);

        BuildToolModel model = BuildToolModel.load(JavaProjectConventions.describe(root));
        var entries = model.projects().getFirst().children().stream()
                .flatMap(node -> node.children().stream()).toList();

        assertEquals("Gradle", model.tool());
        assertTrue(entries.stream().anyMatch(node -> node.name().equals("integrationTest")));
        assertTrue(entries.stream().anyMatch(node -> node.name().equals("generateSources")));
        assertTrue(entries.stream().anyMatch(node -> node.name().contains("junit-jupiter:5.10.2")));
    }
}
