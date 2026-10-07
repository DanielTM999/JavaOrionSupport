package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dtm.ide.deps.MavenLocalRepositoryResolver;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.JdkVendor;
import dtm.ide.sdk.Platform;
import dtm.ide.settings.InlayHintsMode;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLsWireGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden", "jdtls-wire.json");
    private static final Set<String> TIMING_DEPENDENT = Set.of("$/cancelRequest");

    @TempDir
    Path temp;

    private JdtLsService service;

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void everythingSentToTheLanguageServerMatchesTheRecordedGolden() throws Exception {
        Path sdk = temp.resolve("sdk");
        Path home = Files.createDirectories(sdk.resolve("jdtls").resolve(JdtLsProvisioner.DEFAULT_VERSION));
        Files.createDirectories(home.resolve("config_" + Platform.current().jdtLsConfig()));
        writeServerJar(Files.createDirectories(home.resolve("plugins"))
                .resolve("org.eclipse.equinox.launcher_1.0.0.jar"));

        Path project = Files.createDirectories(temp.resolve("wire"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>demo</groupId><artifactId>wire</artifactId><version>1</version></project>");
        Path dir = Files.createDirectories(project.resolve("src/main/java/demo"));
        Path file = dir.resolve("Demo.java");
        String source = """
                package demo;

                public class Demo {
                    private String name = "x";

                    int size() {
                        int size = name.length();
                        return size;
                    }
                }
                """;
        Files.writeString(file, source);
        int line = 6;
        int col = source.lines().toList().get(line).indexOf("name") + 1;

        JdkInstallation runtime = currentRuntime();
        JdkService jdks = new JdkService(null, null) {
            @Override
            public List<JdkInstallation> available() {
                return List.of(runtime);
            }
        };
        service = new JdtLsService(jdks, new JdtLsProvisioner(null, sdk), null, null);
        service.start(project, runtime, DownloadProgressListener.NOOP).get(60, TimeUnit.SECONDS);
        assertTrue(service.awaitReady(60_000), service.getLastError());
        await(() -> !service.isWarmingUp(), 20_000);

        service.openDocument(file, source);
        String edited = source.replace("int size = name.length();", "int size = name.;");
        service.changeDocument(file, edited);
        service.complete(file, edited, line, col + 4, JdtLsService.CompletionTrigger.TRIGGER_CHARACTER, '.',
                JdtLsService.ANY_VERSION);
        service.changeDocument(file, source);
        service.hover(file, source, line, col);
        service.signatureHelp(file, source, line, col);
        for (JavaNavigation.Kind kind : JavaNavigation.Kind.values()) {
            service.navigation(kind, file, source, line, col);
        }
        service.documentSymbols(file, source);
        service.documentHighlights(file, source, line, col);
        service.prepareRename(file, source, line, col);
        service.renameWorkspace(file, source, line, col, "label");
        service.codeActions(file, source, new Range(new Position(line, col), new Position(line, col + 3)),
                List.of());
        service.inlayHints(file, source, 0, 10);
        service.semanticTokens(file, source);
        service.codeLenses(file, source);
        service.format(file, source, 4, true);
        service.foldingRangesAsync(file, source).get(10, TimeUnit.SECONDS);
        service.selectionRangesAsync(file, source, line, col).get(10, TimeUnit.SECONDS);
        service.prepareTypeHierarchy(file, source, 2, 14);
        service.prepareCallHierarchy(file, source, 5, 6);
        service.workspaceTypes("Demo");
        service.classFileContents("jdt://contents/rt.jar/java.lang/String.class");
        service.overridableMethods(file, source, 4, 4);
        service.toStringStatus(file, source, 4, 4);
        service.hashCodeEqualsStatus(file, source, 4, 4);
        service.constructorsStatus(file, source, 4, 4);
        service.accessorsStatus(file, source, 4, 4);
        service.delegateTargets(file, source, 4, 4);
        service.runtimeClasspath(project);
        service.updateProjectConfiguration(project);
        service.willRenameFilesWorkspace(Map.of(file, file.resolveSibling("Renamed.java")));
        service.setInlayHintsMode(InlayHintsMode.ALL);
        service.saveDocument(file, source);
        Path created = Files.writeString(dir.resolve("Extra.java"), "package demo;\nclass Extra {}\n");
        service.pathCreated(created);
        Path wire = project.resolve(FakeJdtLsServer.WIRE_LOG);
        await(() -> wireContains(wire, "workspace/didChangeWatchedFiles"), 10_000);
        service.closeDocument(file);
        service.stop();
        service = null;
        await(() -> wireContains(wire, "\"method\":\"exit\""), 10_000);

        String actual = JSON.writeValueAsString(record(wire, replacements(project, sdk, temp, runtime)));
        Files.writeString(Files.createDirectories(Path.of("target", "golden")).resolve("jdtls-wire.actual.json"),
                actual, StandardCharsets.UTF_8);
        if (Boolean.getBoolean("orion.golden.update") || !Files.exists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(GOLDEN, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                actual.replace("\r\n", "\n"));
    }

    private static ObjectNode record(Path wire, Map<String, String> replacements) throws IOException {
        Map<String, ArrayNode> byKey = new TreeMap<>();
        JsonNode launch = null;
        for (String line : Files.readAllLines(wire, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = JSON.readTree(line);
            if (message.has("launch")) {
                launch = message.get("launch");
                continue;
            }
            String key;
            JsonNode payload;
            if (message.has("method")) {
                key = message.get("method").asText();
                if (TIMING_DEPENDENT.contains(key)) {
                    continue;
                }
                payload = message.path("params");
            } else {
                key = "response:" + message.path("id").asText();
                payload = message.path("result");
            }
            byKey.computeIfAbsent(key, ignored -> JsonNodeFactory.instance.arrayNode())
                    .add(canonical(payload, replacements));
        }
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("launch", canonical(launch, replacements));
        ObjectNode messages = root.putObject("messages");
        byKey.forEach(messages::set);
        return root;
    }

    private static JsonNode canonical(JsonNode node, Map<String, String> replacements) {
        if (node == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                if (!"processId".equals(name)) {
                    sorted.set(name, canonical(node.get(name), replacements));
                }
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(canonical(child, replacements)));
            return array;
        }
        if (node.isTextual()) {
            String text = node.asText();
            for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                text = Pattern.compile(Pattern.quote(replacement.getKey()), Pattern.CASE_INSENSITIVE)
                        .matcher(text).replaceAll(Matcher.quoteReplacement(replacement.getValue()));
            }
            return TextNode.valueOf(text);
        }
        return node;
    }

    private static Map<String, String> replacements(Path project, Path sdk, Path temp, JdkInstallation runtime) {
        Map<String, Path> tokens = new LinkedHashMap<>();
        tokens.put("<WORKSPACE>", new JdtLsProvisioner(null, sdk).workspaceFor(project));
        tokens.put("<PROJECT>", project);
        tokens.put("<SDK>", sdk);
        tokens.put("<TMP>", temp);
        var described = JavaProjectConventions.describe(project);
        Path repository = described == null ? null
                : new MavenLocalRepositoryResolver().resolve(described, null).repository();
        if (repository != null) {
            tokens.put("<M2_REPO>", repository);
        }
        tokens.put("<JAVA_HOME>", runtime.home());
        tokens.put("<HOME>", Path.of(System.getProperty("user.home")));
        Map<String, String> replacements = new LinkedHashMap<>();
        tokens.forEach((token, path) -> {
            Path normalized = path.toAbsolutePath().normalize();
            String uri = LspConversions.toUri(normalized);
            replacements.put(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri, "file://" + token);
            String plain = normalized.toUri().toString();
            replacements.put(plain.endsWith("/") ? plain.substring(0, plain.length() - 1) : plain,
                    "file://" + token);
            replacements.put(normalized.toString(), token);
            replacements.put(normalized.toString().replace('\\', '/'), token);
        });
        return replacements;
    }

    private static boolean wireContains(Path wire, String fragment) {
        try {
            return Files.exists(wire) && Files.readString(wire, StandardCharsets.UTF_8).contains(fragment);
        } catch (IOException e) {
            return false;
        }
    }

    private static JdkInstallation currentRuntime() {
        String vmName = System.getProperty("java.vm.name", "");
        return new JdkInstallation(Path.of(System.getProperty("java.home")),
                vmName.contains("OpenJ9") ? JdkVendor.SEMERU : JdkVendor.TEMURIN,
                Runtime.version().feature(), System.getProperty("java.version"),
                JdkInstallation.JdkOrigin.MANAGED);
    }

    private static void writeServerJar(Path jar) throws IOException {
        String entry = FakeJdtLsServer.class.getName().replace('.', '/') + ".class";
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeJdtLsServer.class.getName());
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var in = FakeJdtLsServer.class.getClassLoader().getResourceAsStream(entry)) {
            out.putNextEntry(new JarEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condicao nao atingida a tempo");
    }
}
