package dtm.ide.sdk;

import dtm.ide.api.extension.Resource;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.project.JavaModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkServiceTest {

    @TempDir
    Path resourceRoot;

    @TempDir
    Path projectRoot;

    private JdkService service;

    @BeforeEach
    void setUp() {
        service = new JdkService(new FakeResource(resourceRoot), null);
    }

    @Test
    void picksTheExactVersionTheProjectAsksFor() {
        JdkService jdks = withInstalled(installation(17), installation(21), installation(25));

        JdkInstallation chosen = jdks.resolveForProject(descriptor(17)).orElseThrow();

        assertEquals(17, chosen.major());
    }

    @Test
    void fallsBackToTheClosestNewerVersionWhenTheExactOneIsMissing() {
        JdkService jdks = withInstalled(installation(17), installation(21), installation(25));

        JdkInstallation chosen = jdks.resolveForProject(descriptor(11)).orElseThrow();

        assertEquals(17, chosen.major(), "deve subir para a mais proxima, nao para a mais nova");
    }

    @Test
    void usesTheDefaultVersionWhenTheProjectDeclaresNothing() {
        JdkService jdks = withInstalled(installation(17), installation(21), installation(25));

        JdkInstallation chosen = jdks.resolveForProject(descriptor(null)).orElseThrow();

        assertEquals(JdkService.DEFAULT_MAJOR, chosen.major());
    }

    @Test
    void fallsBackToTheNewestInstalledWhenTheDefaultIsAbsent() {
        JdkService jdks = withInstalled(installation(11), installation(17));

        JdkInstallation chosen = jdks.resolveForProject(descriptor(null)).orElseThrow();

        assertEquals(17, chosen.major());
    }

    @Test
    void resolvesNothingWhenNoJdkIsInstalled() {
        assertTrue(withInstalled().resolveForProject(descriptor(21)).isEmpty());
    }

    @Test
    void olderProjectStillResolvesWhenOnlyNewerJdksExist() {
        JdkService jdks = withInstalled(installation(21));

        assertEquals(21, jdks.resolveForProject(descriptor(8)).orElseThrow().major());
    }

    @Test
    void languageServerPrefersTheOldestSupportedJdk() {
        JdkService jdks = withInstalled(installation(17), installation(21), installation(25));

        JdkInstallation chosen = jdks.languageServerJdk().orElseThrow();

        assertEquals(JdkService.LANGUAGE_SERVER_MIN_MAJOR, chosen.major(),
                "nao deve ocupar a JDK mais nova sem necessidade");
    }

    @Test
    void languageServerHasNoJdkWhenEverythingIsTooOld() {
        JdkService jdks = withInstalled(installation(11), installation(17));

        assertTrue(jdks.languageServerJdk().isEmpty());
    }

    @Test
    void managedInstallationsAreDiscoveredUnderTheResourcePath() throws IOException {
        JdkDetectorTest.fakeJdk(service.managedRoot().resolve("temurin-21"),
                "JAVA_VERSION=\"21.0.4\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        assertTrue(service.available().stream()
                .anyMatch(installation -> installation.isManaged() && installation.major() == 21));
    }

    @Test
    void refreshPicksUpAnInstallationAddedAfterTheFirstScan() throws IOException {
        service.available();
        JdkDetectorTest.fakeJdk(service.managedRoot().resolve("temurin-17"),
                "JAVA_VERSION=\"17.0.9\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        assertFalse(hasManaged17(), "o cache nao deve enxergar a instalacao nova");
        service.refresh();
        assertTrue(hasManaged17());
    }

    @Test
    void removeDeletesAManagedInstallationAndKeepsSystemOnes() throws IOException {
        Path installDir = service.managedRoot().resolve("temurin-21");
        Path home = JdkDetectorTest.fakeJdk(installDir.resolve("jdk-21.0.4+7"),
                "JAVA_VERSION=\"21.0.4\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        JdkInstallation managed = JdkDetector
                .inspect(home, JdkInstallation.JdkOrigin.MANAGED)
                .orElseThrow();

        assertTrue(service.remove(managed));
        assertFalse(Files.exists(installDir), "a pasta inteira do download deve sair");

        JdkInstallation system = new JdkInstallation(projectRoot, JdkVendor.ORACLE, 17, "17.0.9",
                JdkInstallation.JdkOrigin.SYSTEM);
        assertFalse(service.remove(system), "instalacao do sistema nao e do plugin para remover");
        assertTrue(Files.exists(projectRoot));
    }

    @Test
    void pinWritesAVersionThatTheProjectConventionsReadBack() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
                "<project><artifactId>demo</artifactId></project>");

        service.pinForProject(projectRoot, 25);

        assertEquals(25, JavaProjectConventions.describe(projectRoot).jdkMajor().orElseThrow());
    }

    @Test
    void pinWithNullClearsThePreviousChoice() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"),
                "<project><artifactId>demo</artifactId></project>");
        service.pinForProject(projectRoot, 25);

        service.pinForProject(projectRoot, null);

        assertTrue(JavaProjectConventions.describe(projectRoot).jdkMajor().isEmpty());
    }

    @Test
    void pinPreservesUnrelatedProjectSettings() throws IOException {
        Path settings = projectRoot.resolve(JavaProjectConventions.ORION_SETTINGS_DIR)
                .resolve(JavaProjectConventions.ORION_JAVA_PROPERTIES);
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "vmOptions=-Xmx2g\n");

        service.pinForProject(projectRoot, 21);

        assertTrue(Files.readString(settings).contains("vmOptions"));
        assertTrue(Files.readString(settings).contains("jdk.version=21"));
    }

    private boolean hasManaged17() {
        return service.available().stream()
                .anyMatch(installation -> installation.isManaged() && installation.major() == 17);
    }

    private JdkService withInstalled(JdkInstallation... installations) {
        List<JdkInstallation> inventory = List.of(installations);
        return new JdkService(new FakeResource(resourceRoot), null) {
            @Override
            public List<JdkInstallation> available() {
                return inventory;
            }
        };
    }

    private JdkInstallation installation(int major) {
        Path home = resourceRoot.resolve("installed").resolve("jdk-" + major);
        try {
            JdkDetectorTest.fakeJdk(home, "JAVA_VERSION=\"" + major + ".0.1\"\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new JdkInstallation(home, JdkVendor.TEMURIN, major, major + ".0.1",
                JdkInstallation.JdkOrigin.SYSTEM);
    }

    private JavaProjectDescriptor descriptor(Integer jdkMajor) {
        JavaModule module = new JavaModule(projectRoot, "demo", "com.example", "demo", "jar",
                List.of(), List.of(), projectRoot.resolve("target/classes"));
        return new JavaProjectDescriptor(projectRoot, JavaProjectKind.MAVEN, List.of(module),
                false, false, jdkMajor, null);
    }

    private record FakeResource(Path root) implements Resource {
        @Override
        public Path getResourcePath() {
            return root;
        }

        @Override
        public Path getResourcePath(String path) {
            return root.resolve(path);
        }

        @Override
        public Path getResourcePath(Path path) {
            return root.resolve(path);
        }

        @Override
        public URL getResource(String name) {
            return null;
        }

        @Override
        public List<URL> getResources(Collection<String> name) {
            return List.of();
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return null;
        }

        @Override
        public List<InputStream> getResourcesAsStreams(Collection<String> name) {
            return List.of();
        }

        @Override
        public Path getSharedResourcePath() {
            return root;
        }

        @Override
        public URL getSharedResource(String name) {
            return null;
        }

        @Override
        public List<URL> getSharedResources(Collection<String> name) {
            return List.of();
        }

        @Override
        public InputStream getSharedResourceAsStream(String name) {
            return null;
        }

        @Override
        public List<InputStream> getSharedResourcesAsStreams(Collection<String> name) {
            return List.of();
        }
    }
}
