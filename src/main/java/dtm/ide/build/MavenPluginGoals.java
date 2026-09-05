package dtm.ide.build;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public final class MavenPluginGoals {

    public record Goal(String name, String prefix, String description) {

        public Goal {
            name = name == null ? "" : name.trim();
            prefix = prefix == null ? "" : prefix.trim();
            description = description == null ? "" : description.trim();
        }

        public String invocation() {
            return prefix.isBlank() ? name : prefix + ":" + name;
        }
    }

    private static final Pattern MOJO = Pattern.compile("(?s)<mojo>(.*?)</mojo>");
    private static final Pattern GOAL_PREFIX = Pattern.compile("<goalPrefix>\\s*([^<]+)</goalPrefix>");
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("^\\d[\\w.\\-]*$");

    private final Map<String, List<Goal>> cache = new ConcurrentHashMap<>();
    private final Path localRepository;

    public MavenPluginGoals() {
        this(defaultLocalRepository());
    }

    public MavenPluginGoals(Path localRepository) {
        this.localRepository = localRepository;
    }

    static Path defaultLocalRepository() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home, ".m2", "repository");
    }

    public List<Goal> goalsOf(String groupId, String artifactId, String version) {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            return List.of();
        }
        String key = groupId + ":" + artifactId + ":" + (version == null ? "" : version);
        List<Goal> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Goal> goals = read(groupId, artifactId, version);
        cache.put(key, goals);
        return goals;
    }

    private List<Goal> read(String groupId, String artifactId, String version) {
        Optional<Path> jar = locate(groupId, artifactId, version);
        if (jar.isEmpty()) {
            return List.of();
        }
        try (ZipFile zip = new ZipFile(jar.get().toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/maven/plugin.xml");
            if (entry == null) {
                return List.of();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.debug("Descritor de {}:{} ilegivel: {}", groupId, artifactId, e.getMessage());
            return List.of();
        }
    }

    private Optional<Path> locate(String groupId, String artifactId, String version) {
        if (localRepository == null) {
            return Optional.empty();
        }
        Path artifactRoot = localRepository;
        for (String segment : groupId.split("\\.")) {
            artifactRoot = artifactRoot.resolve(segment);
        }
        artifactRoot = artifactRoot.resolve(artifactId);
        if (!Files.isDirectory(artifactRoot)) {
            return Optional.empty();
        }
        String effective = version == null || version.isBlank()
                ? newestVersion(artifactRoot).orElse("")
                : version;
        if (effective.isBlank()) {
            return Optional.empty();
        }
        Path jar = artifactRoot.resolve(effective).resolve(artifactId + "-" + effective + ".jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    private static Optional<String> newestVersion(Path artifactRoot) {
        try (Stream<Path> versions = Files.list(artifactRoot)) {
            return versions.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> VERSION_DIRECTORY.matcher(name).matches())
                    .max(Comparator.naturalOrder());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static List<Goal> parse(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return List.of();
        }
        Matcher prefixMatcher = GOAL_PREFIX.matcher(descriptor);
        String prefix = prefixMatcher.find() ? prefixMatcher.group(1).trim() : "";

        List<Goal> goals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher mojos = MOJO.matcher(descriptor);
        while (mojos.find()) {
            String block = mojos.group(1);
            String name = tag(block, "goal");
            if (name.isBlank() || !seen.add(name)) {
                continue;
            }
            goals.add(new Goal(name, prefix, tag(block, "description")));
        }
        goals.sort(Comparator.comparing(Goal::name));
        return List.copyOf(goals);
    }

    private static String tag(String block, String name) {
        Matcher matcher = Pattern.compile("(?s)<" + Pattern.quote(name) + ">(.*?)</"
                + Pattern.quote(name) + ">").matcher(block);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    public void clearCache() {
        cache.clear();
    }
}
