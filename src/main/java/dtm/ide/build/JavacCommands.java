package dtm.ide.build;

import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class JavacCommands {

    public static final int ARGUMENT_FILE_THRESHOLD = 30;

    private static final int MIN_RELEASE = 7;

    private JavacCommands() {
    }

    public static Optional<String> releaseArgument(JavaProjectDescriptor descriptor,
                                                   JdkInstallation jdk,
                                                   List<String> extraArguments) {
        for (String argument : extraArguments == null ? List.<String>of() : extraArguments) {
            if (argument.equals("--release") || argument.startsWith("--release=")
                    || argument.equals("-source") || argument.equals("-target")) {
                return Optional.empty();
            }
        }
        Optional<Integer> requested = descriptor == null ? Optional.empty() : descriptor.jdkMajor();
        if (requested.isEmpty() || jdk == null) {
            return Optional.empty();
        }
        int major = requested.get();
        if (major < MIN_RELEASE || major > jdk.major()) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(major));
    }

    public static Path writeArgumentFile(Collection<Path> sources) throws Exception {
        Path file = Files.createTempFile("orion-javac-sources", ".txt");
        StringBuilder content = new StringBuilder();
        for (Path source : sources) {
            content.append('"').append(source.toString().replace("\\", "\\\\")).append('"')
                    .append(System.lineSeparator());
        }
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        return file;
    }

    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
