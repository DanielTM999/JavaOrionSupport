package dtm.ide.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record JavaModule(
        Path root,
        String name,
        String groupId,
        String artifactId,
        String packaging,
        List<Path> sourceRoots,
        List<Path> testRoots,
        Path outputDir
) {

    public JavaModule {
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
        name = name == null || name.isBlank() ? root.getFileName().toString() : name.trim();
        groupId = groupId == null ? "" : groupId.trim();
        artifactId = artifactId == null || artifactId.isBlank() ? name : artifactId.trim();
        packaging = packaging == null || packaging.isBlank() ? "jar" : packaging.trim();
        sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        testRoots = testRoots == null ? List.of() : List.copyOf(testRoots);
    }

    public boolean isAggregator() {
        return "pom".equalsIgnoreCase(packaging);
    }

    public boolean isWebArchive() {
        return "war".equalsIgnoreCase(packaging);
    }

    public String coordinates() {
        return groupId.isBlank() ? artifactId : groupId + ":" + artifactId;
    }

    public List<Path> existingSourceRoots() {
        return sourceRoots.stream().filter(Files::isDirectory).toList();
    }

    public List<Path> existingTestRoots() {
        return testRoots.stream().filter(Files::isDirectory).toList();
    }

    public boolean contains(Path path) {
        if (path == null) {
            return false;
        }
        return path.toAbsolutePath().normalize().startsWith(root);
    }
}
