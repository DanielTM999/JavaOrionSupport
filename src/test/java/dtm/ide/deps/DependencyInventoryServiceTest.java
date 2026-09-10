package dtm.ide.deps;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyInventoryServiceTest {

    @TempDir
    Path root;

    @Test
    void resolvesEffectiveVersionsAndFindsTheirEditableOrigins() throws Exception {
        Path moduleRoot = Files.createDirectories(root.resolve("reports"));
        Path parentPom = root.resolve("pom.xml");
        Path modulePom = moduleRoot.resolve("pom.xml");
        Files.writeString(parentPom, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <properties>
                        <itext.html.version>6.2.0</itext.html.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.16</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Files.writeString(modulePom, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>reports</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.itextpdf</groupId>
                            <artifactId>html2pdf</artifactId>
                            <version>${itext.html.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        JavaModule module = module(moduleRoot);
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root,
                JavaProjectKind.MAVEN_MULTIMODULE, List.of(module), false, false, 21, null);
        List<DependencyCoordinate> declared = PomEditor.readDependencies(
                Files.readString(modulePom));
        List<ResolvedDependency> graph = List.of(
                resolved("com.itextpdf", "html2pdf", "6.2.0"),
                resolved("org.slf4j", "slf4j-api", "2.0.16"),
                resolved("org.springframework.boot", "spring-boot-starter", "4.0.1"));

        DependencyInventorySnapshot result = new DependencyInventoryService(descriptor, null)
                .resolve(module, declared, new DependencyGraphService.Snapshot(graph, false));

        ManagedDependency property = result.dependency("com.itextpdf:html2pdf");
        assertEquals("6.2.0", property.resolvedVersion());
        assertEquals(DependencyVersionOrigin.LOCAL_PROPERTY, property.versionOrigin());
        assertEquals("itext.html.version", property.propertyName());
        assertEquals(parentPom.toAbsolutePath(), property.sourceFile());
        assertTrue(property.versionEditable());

        ManagedDependency management = result.dependency("org.slf4j:slf4j-api");
        assertEquals("2.0.16", management.resolvedVersion());
        assertEquals(DependencyVersionOrigin.LOCAL_MANAGEMENT, management.versionOrigin());
        assertEquals(parentPom.toAbsolutePath(), management.sourceFile());

        ManagedDependency external = result.dependency(
                "org.springframework.boot:spring-boot-starter");
        assertEquals("4.0.1", external.resolvedVersion());
        assertEquals(DependencyVersionOrigin.EXTERNAL_MANAGEMENT, external.versionOrigin());
        assertFalse(external.versionEditable());

        DependencyService editor = new DependencyService(descriptor);
        assertTrue(editor.updateVersion(module, property, "6.2.1"));
        assertTrue(editor.updateVersion(module, management, "2.0.17"));
        assertFalse(editor.updateVersion(module, external, "4.0.2"));
        assertTrue(Files.readString(parentPom)
                .contains("<itext.html.version>6.2.1</itext.html.version>"));
        assertEquals("2.0.17", PomEditor.managedVersion(Files.readString(parentPom),
                DependencyCoordinate.of("org.slf4j", "slf4j-api", "")));
        assertTrue(Files.readString(modulePom)
                .contains("<version>${itext.html.version}</version>"));
    }

    @Test
    void neverUsesAnUnresolvedPlaceholderAsTheEffectiveVersion() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <artifactId>demo</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.itextpdf</groupId>
                            <artifactId>html2pdf</artifactId>
                            <version>${missing.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        JavaModule module = module(root);
        JavaProjectDescriptor descriptor = new JavaProjectDescriptor(root,
                JavaProjectKind.MAVEN, List.of(module), false, false, 21, null);

        DependencyInventorySnapshot result = new DependencyInventoryService(descriptor, null)
                .resolve(module, PomEditor.readDependencies(Files.readString(pom)),
                        new DependencyGraphService.Snapshot(List.of(), true));

        ManagedDependency dependency = result.declared().getFirst();
        assertEquals("", dependency.resolvedVersion());
        assertFalse(dependency.versionResolved());
        assertFalse(dependency.versionEditable());
    }

    private static ResolvedDependency resolved(String group, String artifact, String version) {
        DependencyCoordinate coordinate = DependencyCoordinate.of(group, artifact, version);
        return new ResolvedDependency(coordinate, 0, List.of(coordinate.notation()), false, "");
    }

    private static JavaModule module(Path directory) {
        return new JavaModule(directory, directory.getFileName().toString(), "example",
                directory.getFileName().toString(), "jar", List.of(), List.of(), null);
    }
}
