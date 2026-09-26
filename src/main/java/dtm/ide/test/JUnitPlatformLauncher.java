package dtm.ide.test;

import dtm.ide.build.ProcessRunner;
import dtm.ide.sdk.JdkInstallation;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class JUnitPlatformLauncher {

    static final List<String> CONSOLE_ARTIFACTS = List.of(
            "junit-platform-console", "junit-platform-launcher", "junit-platform-reporting");
    private static final String GROUP_PATH = "org/junit/platform";
    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final Pattern PLATFORM_ENGINE = Pattern.compile("junit-platform-engine-(\\d[^/]*)\\.jar$");
    private static final Set<String> SUREFIRE_CUSTOMIZATIONS = Set.of(
            "argLine", "systemPropertyVariables", "systemProperties", "environmentVariables",
            "forkCount", "forkMode", "reuseForks", "includes", "excludes", "include", "exclude",
            "groups", "excludedGroups", "parallel", "threadCount", "properties",
            "workingDirectory", "additionalClasspathElements", "classpathDependencyExcludes",
            "useModulePath", "test", "testSourceDirectory", "testClassesDirectory",
            "includesFile", "excludesFile", "junitArtifactName", "suiteXmlFiles");

    private final Path sdkRoot;
    private final Supplier<Path> localRepository;
    private final ProcessRunner runner = new ProcessRunner();

    public JUnitPlatformLauncher(Path sdkRoot, Supplier<Path> localRepository) {
        this.sdkRoot = sdkRoot;
        this.localRepository = localRepository;
    }

    public void cancel() {
        runner.cancel();
    }

    static Optional<String> platformVersion(String classpath) {
        for (String entry : entries(classpath)) {
            Matcher matcher = PLATFORM_ENGINE.matcher(entry.replace('\\', '/'));
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    static boolean hasJUnitEngine(String classpath) {
        return entries(classpath).stream().map(JUnitPlatformLauncher::fileName).anyMatch(name ->
                name.startsWith("junit-jupiter-engine-") || name.startsWith("junit-vintage-engine-"));
    }

    static boolean usesTestNg(String classpath) {
        return entries(classpath).stream().map(JUnitPlatformLauncher::fileName)
                .anyMatch(name -> name.startsWith("testng-"));
    }

    static boolean selectable(List<JavaTest> tests) {
        if (tests == null) {
            return true;
        }
        for (JavaTest test : tests) {
            if (test.isClassLevel()) {
                continue;
            }
            if (test.parameterized() || test.file() == null || !declaresNoArgMethod(test)) {
                return false;
            }
        }
        return true;
    }

    private static boolean declaresNoArgMethod(JavaTest test) {
        try {
            String source = Files.readString(test.file());
            return Pattern.compile("\\b" + Pattern.quote(test.methodName()) + "\\s*\\(\\s*\\)")
                    .matcher(source).find();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean surefireCustomized(Path... poms) {
        for (Path pom : poms) {
            if (pom != null && surefireCustomizedIn(pom)) {
                return true;
            }
        }
        return false;
    }

    private static boolean surefireCustomizedIn(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            NodeList plugins = document.getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                if (!(plugins.item(i) instanceof Element plugin)
                        || !"maven-surefire-plugin".equals(childText(plugin, "artifactId"))) {
                    continue;
                }
                if (child(plugin, "dependencies") != null) {
                    return true;
                }
                if (customizes(child(plugin, "configuration"))) {
                    return true;
                }
                Element executions = child(plugin, "executions");
                NodeList configurations = executions == null ? null
                        : executions.getElementsByTagName("configuration");
                for (int j = 0; configurations != null && j < configurations.getLength(); j++) {
                    if (configurations.item(j) instanceof Element configuration && customizes(configuration)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean customizes(Element configuration) {
        if (configuration == null) {
            return false;
        }
        NodeList children = configuration.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element
                    && SUREFIRE_CUSTOMIZATIONS.contains(element.getTagName())) {
                return true;
            }
        }
        return false;
    }

    Optional<List<Path>> consoleJars(String version, Consumer<String> output) {
        List<Path> jars = new ArrayList<>();
        for (String artifact : CONSOLE_ARTIFACTS) {
            Optional<Path> jar = locate(artifact, version, output);
            if (jar.isEmpty()) {
                return Optional.empty();
            }
            jars.add(jar.get());
        }
        return Optional.of(jars);
    }

    private Optional<Path> locate(String artifact, String version, Consumer<String> output) {
        String fileName = artifact + "-" + version + ".jar";
        Path repository = null;
        try {
            repository = localRepository == null ? null : localRepository.get();
        } catch (Exception ignored) {
        }
        if (repository != null) {
            Path local = repository.resolve(GROUP_PATH).resolve(artifact).resolve(version).resolve(fileName);
            if (Files.isRegularFile(local)) {
                return Optional.of(local);
            }
        }
        if (sdkRoot == null) {
            return Optional.empty();
        }
        Path cached = sdkRoot.resolve("junit-platform").resolve(version).resolve(fileName);
        if (Files.isRegularFile(cached)) {
            return Optional.of(cached);
        }
        try {
            Files.createDirectories(cached.getParent());
            emit(output, "[orion] baixando " + fileName);
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            Path temporary = Files.createTempFile(cached.getParent(), artifact, ".part");
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(CENTRAL + GROUP_PATH
                                    + "/" + artifact + "/" + version + "/" + fileName))
                            .timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(temporary);
                return Optional.empty();
            }
            Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(cached);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.info("Nao foi possivel obter {}: {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }

    static List<String> command(JdkInstallation jdk, String version, String classpath,
                                List<Path> consoleJars, List<String> jvmArguments,
                                List<JavaTest> tests, Path moduleRoot, Path testClasses,
                                Path reportsDir) {
        List<String> command = new ArrayList<>();
        command.add(jdk.javaExecutable().toString());
        command.addAll(jvmArguments == null ? List.of() : jvmArguments);
        command.add("-Dbasedir=" + moduleRoot);
        command.add("-Dfile.encoding=UTF-8");
        List<String> entries = new ArrayList<>(entries(classpath));
        consoleJars.forEach(jar -> entries.add(jar.toString()));
        command.add("-cp");
        command.add(String.join(File.pathSeparator, entries));
        command.add("org.junit.platform.console.ConsoleLauncher");
        if (supportsSubcommands(version)) {
            command.add("execute");
        }
        command.add("--disable-banner");
        command.add("--disable-ansi-colors");
        command.add("--details=tree");
        command.add("--reports-dir=" + reportsDir);
        if (tests == null || tests.isEmpty()) {
            command.add("--scan-classpath=" + testClasses);
        } else {
            for (JavaTest test : tests) {
                command.add(test.isClassLevel()
                        ? "--select-class=" + test.className()
                        : "--select-method=" + test.className() + "#" + test.methodName());
            }
        }
        return command;
    }

    static boolean supportsSubcommands(String version) {
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 1 || minor >= 10;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    int execute(List<String> command, Path workingDirectory, Map<String, String> environment,
                Consumer<String> output) {
        emit(output, "> " + String.join(" ", command.subList(0, Math.min(2, command.size())))
                + " ... ConsoleLauncher");
        return runner.run(command, workingDirectory, environment, output);
    }

    private static List<String> entries(String classpath) {
        List<String> entries = new ArrayList<>();
        if (classpath == null) {
            return entries;
        }
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return entries;
    }

    private static String fileName(String entry) {
        String normalized = entry.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static void emit(Consumer<String> output, String line) {
        if (output != null) {
            output.accept(line);
        }
    }
}
