package dtm.ide.deps;

import dtm.ide.build.BuildCommand;
import dtm.ide.build.BuildSystem;
import dtm.ide.build.ProcessRunner;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class MavenLocalRepositoryResolver {

    public record Resolution(Path repository, List<Path> configurationFiles, boolean fallback) {
        public Resolution {
            repository = repository == null ? null : repository.toAbsolutePath().normalize();
            configurationFiles = configurationFiles == null
                    ? List.of() : configurationFiles.stream().filter(java.util.Objects::nonNull)
                    .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        }
    }

    private static final Pattern REPOSITORY_ARGUMENT = Pattern.compile(
            "(?:^|\\s)-Dmaven\\.repo\\.local(?:=|\\s+)(?:\"([^\"]+)\"|'([^']+)'|(\\S+))");
    private static final Pattern SETTINGS_ARGUMENT = Pattern.compile(
            "(?:^|\\s)(?:-s|--settings)(?:=|\\s+)(?:\"([^\"]+)\"|'([^']+)'|(\\S+))");
    private static final Pattern LOCAL_REPOSITORY = Pattern.compile(
            "(?s)<localRepository\\s*>\\s*([^<]+?)\\s*</localRepository\\s*>");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\$\\{env\\.([^}]+)}");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[;\\d]*[a-zA-Z]");

    private final Path userHome;
    private final Map<String, String> environment;

    public MavenLocalRepositoryResolver() {
        this(homeDirectory(), System.getenv());
    }

    MavenLocalRepositoryResolver(Path userHome, Map<String, String> environment) {
        this.userHome = userHome == null ? null : userHome.toAbsolutePath().normalize();
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public Path userSettings(Path root) {
        Path projectConfig = root == null ? null : root.resolve(".mvn/maven.config");
        String arguments = String.join(" ", environment.getOrDefault("MAVEN_OPTS", ""),
                environment.getOrDefault("MAVEN_ARGS", ""), read(projectConfig));
        String selected = argument(SETTINGS_ARGUMENT, arguments);
        return selected.isBlank() ? userHome == null ? null : userHome.resolve(".m2/settings.xml")
                : resolvePath(expand(selected), root);
    }

    public Path globalSettings() {
        String home = environment.getOrDefault("MAVEN_HOME", environment.getOrDefault("M2_HOME", ""));
        return home.isBlank() ? null : resolvePath(home, userHome).resolve("conf/settings.xml");
    }

    public Resolution resolve(JavaProjectDescriptor descriptor, BuildSystem buildSystem) {
        Path root = descriptor == null ? null : descriptor.root();
        Set<Path> configuration = new LinkedHashSet<>();
        Path projectConfig = root == null ? null : root.resolve(".mvn").resolve("maven.config");
        if (projectConfig != null) {
            configuration.add(projectConfig);
        }
        Path userSettings = userHome == null ? null : userHome.resolve(".m2").resolve("settings.xml");
        if (userSettings != null) {
            configuration.add(userSettings);
        }

        String arguments = String.join(" ", environment.getOrDefault("MAVEN_OPTS", ""),
                environment.getOrDefault("MAVEN_ARGS", ""), read(projectConfig));
        String explicitRepository = argument(REPOSITORY_ARGUMENT, arguments);
        if (!explicitRepository.isBlank()) {
            Resolution resolved = resolution(explicitRepository, root, configuration, false);
            if (resolved.repository() != null) {
                return resolved;
            }
        }

        String settingsArgument = argument(SETTINGS_ARGUMENT, arguments);
        Path selectedSettings = settingsArgument.isBlank() ? userSettings
                : resolvePath(expand(settingsArgument), root);
        if (selectedSettings != null) {
            configuration.add(selectedSettings);
            String configuredRepository = localRepository(read(selectedSettings));
            if (!configuredRepository.isBlank()) {
                Resolution resolved = resolution(configuredRepository, root, configuration, false);
                if (resolved.repository() != null) {
                    return resolved;
                }
            }
        }

        String mavenHome = environmentValue("MAVEN_HOME");
        if (mavenHome.contains("${")) {
            mavenHome = environmentValue("M2_HOME");
        }
        Path mavenHomePath = mavenHome.contains("${") ? null : resolvePath(mavenHome, root);
        Path globalSettings = mavenHomePath == null ? null
                : mavenHomePath.resolve("conf").resolve("settings.xml");
        if (globalSettings != null) {
            configuration.add(globalSettings);
            String configuredRepository = localRepository(read(globalSettings));
            if (!configuredRepository.isBlank()) {
                Resolution resolved = resolution(configuredRepository, root, configuration, false);
                if (resolved.repository() != null) {
                    return resolved;
                }
            }
        }

        if (descriptor != null && descriptor.isMaven() && buildSystem != null) {
            Path effective = queryMaven(descriptor.rootModule(), buildSystem);
            if (effective != null) {
                return new Resolution(effective, List.copyOf(configuration), false);
            }
        }

        Path fallback = userHome == null ? null : userHome.resolve(".m2").resolve("repository");
        return new Resolution(fallback, List.copyOf(configuration), true);
    }

    private Resolution resolution(String configured, Path root, Set<Path> files,
                                  boolean fallback) {
        Path path = resolvePath(expand(configured), root == null ? userHome : root);
        return new Resolution(path, List.copyOf(files), fallback);
    }

    private Path queryMaven(JavaModule module, BuildSystem buildSystem) {
        BuildCommand.Options options = BuildCommand.Options.none().withExtraArguments(List.of(
                "-q", "-Dexpression=settings.localRepository", "-DforceStdout"));
        BuildCommand command = buildSystem.toolCommand(module, List.of("help:evaluate"), options)
                .orElse(null);
        if (command == null) {
            return null;
        }
        List<String> output = new ArrayList<>();
        int exit = new ProcessRunner().run(command.command(), command.workingDirectory(),
                command.environment(), output::add);
        if (exit != 0) {
            return null;
        }
        for (int index = output.size() - 1; index >= 0; index--) {
            String value = ANSI.matcher(output.get(index)).replaceAll("").trim();
            if (value.isBlank() || value.startsWith("[") || value.contains("Downloading")) {
                continue;
            }
            try {
                Path candidate = Path.of(expand(value));
                if (candidate.isAbsolute()) {
                    return candidate.toAbsolutePath().normalize();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String localRepository(String settings) {
        Matcher matcher = LOCAL_REPOSITORY.matcher(settings);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String expand(String value) {
        if (value == null) {
            return "";
        }
        String expanded = value.trim();
        if (userHome != null) {
            expanded = expanded.replace("${user.home}", userHome.toString());
        }
        Matcher matcher = ENVIRONMENT.matcher(expanded);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = environmentValue(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String environmentValue(String name) {
        String direct = environment.get(name);
        if (direct != null) {
            return direct;
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse("${env." + name + "}");
    }

    private static String argument(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        String result = "";
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    result = matcher.group(group);
                    break;
                }
            }
        }
        return result;
    }

    private static Path resolvePath(String value, Path base) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        try {
            Path path = Path.of(value.trim());
            if (!path.isAbsolute() && base != null) {
                path = base.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return "";
        }
    }

    private static Path homeDirectory() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }
}
