package dtm.ide.project;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class LanguageLevelEditor {

    private static final Pattern MAVEN_RELEASE = property("maven.compiler.release");
    private static final Pattern MAVEN_SOURCE = property("maven.compiler.source");
    private static final Pattern MAVEN_TARGET = property("maven.compiler.target");
    private static final Pattern PROPERTIES_BLOCK = Pattern.compile("(?s)(<properties\\b[^>]*>)(.*?)(</properties>)");
    private static final Pattern PROJECT_OPEN = Pattern.compile("<project\\b[^>]*>");

    private static final Pattern GRADLE_RELEASE = Pattern.compile(
            "(?m)^(\\s*)(sourceCompatibility|targetCompatibility)\\s*=?\\s*[^\\r\\n]*$");
    private static final Pattern GRADLE_TOOLCHAIN = Pattern.compile(
            "languageVersion\\s*(?:=|\\.set\\()\\s*JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)");

    private LanguageLevelEditor() {
    }

    private static Pattern property(String name) {
        return Pattern.compile("(?s)(<" + Pattern.quote(name) + "\\s*>)(.*?)(</"
                + Pattern.quote(name) + ">)");
    }

    public static Optional<Integer> read(Path moduleRoot) {
        if (moduleRoot == null) {
            return Optional.empty();
        }
        Path pom = moduleRoot.resolve(JavaProjectConventions.POM_FILE);
        if (Files.isRegularFile(pom)) {
            return readMaven(JavaProjectConventions.readOrEmpty(pom));
        }
        Path gradle = JavaProjectConventions.gradleBuildFile(moduleRoot);
        return Files.isRegularFile(gradle)
                ? readGradle(JavaProjectConventions.readOrEmpty(gradle))
                : Optional.empty();
    }

    static Optional<Integer> readMaven(String pomXml) {
        for (Pattern pattern : new Pattern[]{MAVEN_RELEASE, MAVEN_SOURCE, MAVEN_TARGET}) {
            Matcher matcher = pattern.matcher(pomXml);
            if (matcher.find()) {
                Optional<Integer> major = parseMajor(matcher.group(2));
                if (major.isPresent()) {
                    return major;
                }
            }
        }
        return Optional.empty();
    }

    static Optional<Integer> readGradle(String script) {
        Matcher toolchain = GRADLE_TOOLCHAIN.matcher(script);
        if (toolchain.find()) {
            return parseMajor(toolchain.group(1));
        }
        Matcher compatibility = GRADLE_RELEASE.matcher(script);
        return compatibility.find() ? parseMajor(compatibility.group(0)) : Optional.empty();
    }

    public static boolean write(Path moduleRoot, int major) {
        if (moduleRoot == null || major <= 0) {
            return false;
        }
        Path pom = moduleRoot.resolve(JavaProjectConventions.POM_FILE);
        if (Files.isRegularFile(pom)) {
            return rewrite(pom, content -> writeMaven(content, major));
        }
        Path gradle = JavaProjectConventions.gradleBuildFile(moduleRoot);
        return Files.isRegularFile(gradle) && rewrite(gradle, content -> writeGradle(content, major));
    }

    static String writeMaven(String pomXml, int major) {
        String value = String.valueOf(major);
        boolean touched = false;
        String result = pomXml;

        if (MAVEN_RELEASE.matcher(result).find()) {
            return replaceProperty(result, MAVEN_RELEASE, value);
        }
        if (MAVEN_SOURCE.matcher(result).find()) {
            result = replaceProperty(result, MAVEN_SOURCE, value);
            touched = true;
        }
        if (MAVEN_TARGET.matcher(result).find()) {
            result = replaceProperty(result, MAVEN_TARGET, value);
            touched = true;
        }
        return touched ? result : insertMavenProperties(result, value);
    }

    private static String replaceProperty(String pomXml, Pattern pattern, String value) {
        return pattern.matcher(pomXml)
                .replaceAll(match -> Matcher.quoteReplacement(
                        match.group(1) + value + match.group(3)));
    }

    private static String insertMavenProperties(String pomXml, String value) {
        String entries = "\n        <maven.compiler.source>" + value + "</maven.compiler.source>"
                + "\n        <maven.compiler.target>" + value + "</maven.compiler.target>";

        Matcher block = PROPERTIES_BLOCK.matcher(pomXml);
        if (block.find()) {
            return pomXml.substring(0, block.end(2)) + entries + pomXml.substring(block.end(2));
        }
        Matcher project = PROJECT_OPEN.matcher(pomXml);
        if (!project.find()) {
            return pomXml;
        }
        String properties = "\n    <properties>" + entries + "\n    </properties>\n";
        return pomXml.substring(0, project.end()) + properties + pomXml.substring(project.end());
    }

    static String writeGradle(String script, int major) {
        String value = String.valueOf(major);
        Matcher toolchain = GRADLE_TOOLCHAIN.matcher(script);
        if (toolchain.find()) {
            return script.substring(0, toolchain.start(1)) + value + script.substring(toolchain.end(1));
        }
        Matcher compatibility = GRADLE_RELEASE.matcher(script);
        if (compatibility.find()) {
            return compatibility.replaceAll(match -> Matcher.quoteReplacement(
                    match.group(1) + match.group(2) + " = JavaVersion.VERSION_" + value));
        }
        return script + "\njava {\n    toolchain {\n        languageVersion = JavaLanguageVersion.of("
                + value + ")\n    }\n}\n";
    }

    private static boolean rewrite(Path file, java.util.function.UnaryOperator<String> edit) {
        String original = JavaProjectConventions.readOrEmpty(file);
        String updated = edit.apply(original);
        if (updated == null || updated.equals(original)) {
            return false;
        }
        try {
            Files.writeString(file, updated);
            return true;
        } catch (Exception e) {
            log.warn("Falha ao gravar o nivel de linguagem em {}: {}", file, e.getMessage());
            return false;
        }
    }

    static Optional<Integer> parseMajor(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher digits = Pattern.compile("(?:1\\.)?(\\d+)").matcher(raw.trim());
        if (!digits.find()) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(digits.group(1));
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
