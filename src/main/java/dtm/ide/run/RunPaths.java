package dtm.ide.run;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

public final class RunPaths {

    private RunPaths() {
    }

    public static Optional<Path> base(JavaModule module, JavaProjectDescriptor descriptor) {
        if (module != null) {
            return Optional.of(module.root());
        }
        return Optional.ofNullable(descriptor).map(JavaProjectDescriptor::root);
    }

    public static Optional<Path> resolve(String raw, JavaModule module,
                                         JavaProjectDescriptor descriptor) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Path candidate = Path.of(raw.trim());
            if (candidate.isAbsolute()) {
                return Optional.of(candidate.normalize());
            }
            return base(module, descriptor)
                    .map(root -> root.resolve(candidate).normalize());
        } catch (InvalidPathException error) {
            return Optional.empty();
        }
    }

    public static boolean isMalformed(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            Path.of(raw.trim());
            return false;
        } catch (InvalidPathException error) {
            return true;
        }
    }

    public static String relativize(Path path, JavaModule module,
                                    JavaProjectDescriptor descriptor) {
        if (path == null) {
            return "";
        }
        Path normalized = path.toAbsolutePath().normalize();
        return base(module, descriptor)
                .filter(normalized::startsWith)
                .map(root -> root.relativize(normalized).toString().replace('\\', '/'))
                .filter(relative -> !relative.isBlank())
                .orElseGet(normalized::toString);
    }
}
