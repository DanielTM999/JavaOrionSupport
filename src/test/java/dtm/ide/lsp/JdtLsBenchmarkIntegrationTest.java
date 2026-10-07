package dtm.ide.lsp;

import dtm.ide.lsp.api.CompletionTrigger;
import dtm.ide.api.extension.Resource;
import dtm.ide.navigation.JavaNavigation;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@EnabledIfSystemProperty(named = "orion.bench.jdtls", matches = "true")
class JdtLsBenchmarkIntegrationTest {

    private static final long READY_TIMEOUT_MS = 300_000;
    private static final int WARMUP_ROUNDS = 5;
    private static final int ROUNDS = Integer.getInteger("orion.bench.rounds", 40);
    private static final Map<String, Integer> EMPTY_RESULTS = new LinkedHashMap<>();
    private static final AtomicInteger ROUND_SEED = new AtomicInteger();

    @TempDir
    Path workspace;

    private JdtLsService service;

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void measuresLatencyOfTheMainLanguageServerOperations() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("bench"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>demo</groupId><artifactId>bench</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target></properties></project>");
        Path pkg = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(pkg.resolve("Repository.java"), """
                package demo;

                import java.util.List;
                import java.util.Optional;

                public interface Repository<T> {
                    Optional<T> findById(long id);
                    List<T> findAll();
                    T save(T value);
                }
                """);
        Files.writeString(pkg.resolve("Customer.java"), """
                package demo;

                public record Customer(long id, String name, String email) {
                    public String displayName() {
                        return name + " <" + email + ">";
                    }
                }
                """);
        Path file = pkg.resolve("CustomerService.java");
        String source = """
                package demo;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;
                import java.util.stream.Collectors;

                public class CustomerService {
                    private final Repository<Customer> repository;
                    private final List<String> audit = new ArrayList<>();

                    public CustomerService(Repository<Customer> repository) {
                        this.repository = repository;
                    }

                    public Map<String, Customer> byName() {
                        List<Customer> customers = repository.findAll();
                        audit.add("byName");
                        return customers.stream().collect(Collectors.toMap(Customer::name, c -> c));
                    }

                    public String describe(long id) {
                        Customer customer = repository.findById(id).orElseThrow();
                        String text = customer.displayName();
                        return text.trim();
                    }
                }
                """;
        Files.writeString(file, source);

        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        var jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "a JDK is required");
        Path sdk = integrationSdk(jdks);
        var provisioner = new JdtLsProvisioner(new SdkDownloader(null), sdk);
        assumeTrue(provisioner.find().isPresent(), "set -Dorion.it.sdk to an SDK with JDT LS installed");

        Map<String, List<Long>> samples = new LinkedHashMap<>();
        long startNanos = System.nanoTime();
        service = new JdtLsService(jdks, provisioner, null, null);
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assertTrue(service.awaitReady(READY_TIMEOUT_MS), service.getLastError());
        samples.put("startup.ready", List.of(elapsedMicros(startNanos)));
        await(() -> !service.isWarmingUp(), 60_000);
        samples.put("startup.warm", List.of(elapsedMicros(startNanos)));
        service.openDocument(file, source);

        int customerLine = lineOf(source, "String text = customer.displayName();");
        int customerCol = source.lines().toList().get(customerLine).indexOf("customer.") + "customer".length();
        int customersLine = lineOf(source, "return customers.stream()");
        int streamCol = source.lines().toList().get(customersLine).indexOf("stream");
        int auditLine = lineOf(source, "audit.add(\"byName\");");
        int auditCol = source.lines().toList().get(auditLine).indexOf("audit") + 1;

        String member = source.replace("String text = customer.displayName();", "String text = customer.;");
        String identifier = source.replace("String text = customer.displayName();", "String text = cust;");
        await(() -> !completeMember(file, member, customerLine, customerCol).isEmpty(), 60_000);

        measure(samples, "completion.member.cached", round -> completeMember(file, member, customerLine,
                customerCol));
        measure(samples, "completion.member", round -> completeMember(file, fresh(member, round), customerLine,
                customerCol));
        measure(samples, "completion.identifier", round -> {
            String text = fresh(identifier, round);
            service.changeDocument(file, text);
            return service.complete(file, text, customerLine, customerCol - "customer".length() + 4);
        });
        String cachedWord = source.replace("String text = customer.displayName();", "String text = customer.di;");
        String typedWord = source.replace("String text = customer.displayName();", "String text = customer.disp;");
        service.changeDocument(file, cachedWord);
        service.complete(file, cachedWord, customerLine, customerCol + 3,
                CompletionTrigger.INVOKED, null, JdtLsService.ANY_VERSION);
        measure(samples, "completion.reusable", round -> service.reusableCompletions(file, typedWord,
                customerLine, customerCol + 5));

        measure(samples, "document.change", round -> {
            service.changeDocument(file, fresh(source, round));
            return List.of(round);
        });
        measure(samples, "hover", round -> service.hover(file, fresh(source, round), customersLine,
                streamCol + 1));
        measure(samples, "navigation.definition", round -> {
            JavaNavigation.Result result = service.navigation(JavaNavigation.Kind.DEFINITION, file,
                    fresh(source, round), auditLine, auditCol);
            assertTrue(result.resolved(), "definicao nao resolvida: " + result.status());
            return result.locations();
        });
        measure(samples, "navigation.references", round -> service.navigation(JavaNavigation.Kind.REFERENCES,
                file, fresh(source, round), auditLine, auditCol).locations());
        measure(samples, "documentSymbols", round -> service.documentSymbols(file, fresh(source, round)));
        measure(samples, "semanticTokens", round -> service.semanticTokens(file, fresh(source, round)));
        measure(samples, "codeLenses", round -> service.codeLenses(file, fresh(source, round)));
        measure(samples, "inlayHints", round -> service.inlayHints(file, fresh(source, round), 0,
                source.lines().toList().size()));
        measure(samples, "renameWorkspace", round -> service.renameWorkspace(file, fresh(source, round),
                auditLine, auditCol, "auditTrail"));
        service.changeDocument(file, source);

        report(samples);
    }

    private List<AutoCompleteItem> completeMember(Path file, String text, int line, int customerCol) {
        service.changeDocument(file, text);
        return service.complete(file, text, line, customerCol + 1,
                CompletionTrigger.TRIGGER_CHARACTER, '.', JdtLsService.ANY_VERSION);
    }

    private static String fresh(String text, int round) {
        return text + "// round " + ROUND_SEED.incrementAndGet() + "-" + round + "\n";
    }

    private static void measure(Map<String, List<Long>> samples, String name, IntFunction<?> operation) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            operation.apply(-1 - i);
        }
        List<Long> values = new ArrayList<>(ROUNDS);
        int empty = 0;
        for (int i = 0; i < ROUNDS; i++) {
            long start = System.nanoTime();
            Object result = operation.apply(i);
            values.add((System.nanoTime() - start) / 1_000);
            if (result == null || result instanceof Collection<?> collection && collection.isEmpty()) {
                empty++;
            }
        }
        samples.put(name, values);
        EMPTY_RESULTS.put(name, empty);
    }

    private static long elapsedMicros(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000;
    }

    private static void report(Map<String, List<Long>> samples) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%-24s %6s %6s %10s %10s %10s %10s%n",
                "operation", "n", "empty", "p50 ms", "p90 ms", "p99 ms", "max ms"));
        samples.forEach((name, values) -> {
            List<Long> sorted = values.stream().sorted().toList();
            out.append(String.format(Locale.ROOT, "%-24s %6d %6d %10.2f %10.2f %10.2f %10.2f%n", name,
                    sorted.size(), EMPTY_RESULTS.getOrDefault(name, 0), percentile(sorted, 50),
                    percentile(sorted, 90), percentile(sorted, 99), sorted.getLast() / 1000.0));
        });
        System.out.print(out);
        Path dir = Files.createDirectories(Path.of("target", "benchmark"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.writeString(dir.resolve("jdtls-" + stamp + ".txt"), out);
    }

    private static double percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1000.0;
    }

    private static int lineOf(String source, String fragment) {
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        throw new IllegalArgumentException(fragment);
    }

    private static Path integrationSdk(JdkService jdks) {
        String configured = System.getProperty("orion.it.sdk", "");
        return configured.isBlank() ? jdks.sdkRoot() : Path.of(configured).toAbsolutePath().normalize();
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "condicao nao atingida a tempo");
    }

    private static Resource resourceAt(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new Resource() {
            @Override
            public Path getResourcePath() {
                return directory;
            }

            @Override
            public Path getResourcePath(String path) {
                return directory.resolve(path);
            }

            @Override
            public Path getResourcePath(Path path) {
                return directory.resolve(path);
            }

            @Override
            public URL getResource(String name) {
                return null;
            }

            @Override
            public List<URL> getResources(Collection<String> name) {
                return List.of();
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }

            @Override
            public List<InputStream> getResourcesAsStreams(Collection<String> name) {
                return List.of();
            }

            @Override
            public Path getSharedResourcePath() {
                return directory;
            }

            @Override
            public URL getSharedResource(String name) {
                return null;
            }

            @Override
            public List<URL> getSharedResources(Collection<String> name) {
                return List.of();
            }

            @Override
            public InputStream getSharedResourceAsStream(String name) {
                return null;
            }

            @Override
            public List<InputStream> getSharedResourcesAsStreams(Collection<String> name) {
                return List.of();
            }
        };
    }
}
