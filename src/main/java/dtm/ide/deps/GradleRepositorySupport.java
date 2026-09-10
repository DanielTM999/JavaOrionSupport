package dtm.ide.deps;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class GradleRepositorySupport {

    private static final Pattern MAVEN_LOCAL = Pattern.compile("\\bmavenLocal\\s*\\(\\s*\\)");

    private GradleRepositorySupport() {
    }

    public static boolean hasMavenLocal(JavaProjectDescriptor descriptor, JavaModule module) {
        if (descriptor == null || !descriptor.isGradle()) {
            return descriptor != null && descriptor.isMaven();
        }
        Set<Path> scripts = new LinkedHashSet<>();
        add(scripts, descriptor.root().resolve(JavaProjectConventions.GRADLE_SETTINGS_KOTLIN));
        add(scripts, descriptor.root().resolve(JavaProjectConventions.GRADLE_SETTINGS_GROOVY));
        add(scripts, JavaProjectConventions.gradleBuildFile(descriptor.root()));
        if (module != null) {
            add(scripts, JavaProjectConventions.gradleBuildFile(module.root()));
        }
        return scripts.stream().map(JavaProjectConventions::readOrEmpty)
                .map(GradleRepositorySupport::stripComments)
                .anyMatch(script -> MAVEN_LOCAL.matcher(script).find());
    }

    private static void add(Set<Path> paths, Path path) {
        if (path != null && java.nio.file.Files.isRegularFile(path)) {
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    private static String stripComments(String source) {
        if (source == null) {
            return "";
        }
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"")
                .replaceAll("(?s)'(?:\\\\.|[^'\\\\])*'", "''")
                .replaceAll("(?s)\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }
}
