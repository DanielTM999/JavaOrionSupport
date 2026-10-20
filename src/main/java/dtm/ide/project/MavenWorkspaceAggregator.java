package dtm.ide.project;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public final class MavenWorkspaceAggregator {

    public static final String DIRECTORY = JavaProjectConventions.ORION_SETTINGS_DIR + "/maven-workspace";
    public static final String GROUP_ID = "dtm.orion.workspace";
    public static final String ARTIFACT_ID = "orion-maven-workspace";

    private MavenWorkspaceAggregator() {
    }

    public static Path pomOf(Path projectRoot) {
        return projectRoot.resolve(DIRECTORY).resolve(JavaProjectConventions.POM_FILE);
    }

    public static Optional<Path> ensure(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return Optional.empty();
        }
        Path pom = pomOf(descriptor.root());
        if (!descriptor.isMavenWorkspace()) {
            remove(descriptor.root());
            return Optional.empty();
        }
        String content = render(pom.getParent(), memberRoots(descriptor));
        try {
            if (Files.isRegularFile(pom) && Files.readString(pom, StandardCharsets.UTF_8).equals(content)) {
                return Optional.of(pom);
            }
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, content, StandardCharsets.UTF_8);
            log.info("Agregador do workspace Maven gravado em {}", pom);
            return Optional.of(pom);
        } catch (Exception e) {
            log.warn("Falha ao gravar o agregador do workspace Maven em {}: {}", pom, e.getMessage());
            return Optional.empty();
        }
    }

    public static void remove(Path projectRoot) {
        if (projectRoot == null) {
            return;
        }
        Path pom = pomOf(projectRoot);
        try {
            if (Files.deleteIfExists(pom)) {
                Files.deleteIfExists(pom.getParent());
            }
        } catch (Exception e) {
            log.debug("Nao foi possivel remover {}: {}", pom, e.getMessage());
        }
    }

    static List<Path> memberRoots(JavaProjectDescriptor descriptor) {
        Map<String, Path> byCoordinates = new LinkedHashMap<>();
        for (Path buildRoot : descriptor.buildRoots()) {
            String coordinates = coordinatesOf(buildRoot.resolve(JavaProjectConventions.POM_FILE));
            Path previous = byCoordinates.putIfAbsent(coordinates, buildRoot);
            if (previous != null) {
                log.warn("Projeto {} ignorado no workspace Maven: {} ja esta em {}", buildRoot, coordinates,
                        previous);
            }
        }
        return List.copyOf(byCoordinates.values());
    }

    static String render(Path aggregatorDirectory, List<Path> members) {
        List<String> modules = new ArrayList<>();
        for (Path member : members) {
            modules.add(aggregatorDirectory.relativize(member).toString().replace('\\', '/'));
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ");
        xml.append("https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        xml.append("    <modelVersion>4.0.0</modelVersion>\n");
        xml.append("    <groupId>").append(GROUP_ID).append("</groupId>\n");
        xml.append("    <artifactId>").append(ARTIFACT_ID).append("</artifactId>\n");
        xml.append("    <version>0</version>\n");
        xml.append("    <packaging>pom</packaging>\n");
        xml.append("    <modules>\n");
        for (String module : modules) {
            xml.append("        <module>").append(module).append("</module>\n");
        }
        xml.append("    </modules>\n");
        xml.append("</project>\n");
        return xml.toString();
    }

    private static String coordinatesOf(Path pomFile) {
        MavenPom pom = MavenPom.parse(pomFile);
        String groupId = pom.value("groupId");
        if (groupId.isBlank()) {
            groupId = pom.value("parent", "groupId");
        }
        String version = pom.value("version");
        if (version.isBlank()) {
            version = pom.value("parent", "version");
        }
        String artifactId = pom.value("artifactId");
        return artifactId.isBlank() ? pomFile.toString() : groupId + ":" + artifactId + ":" + version;
    }
}
