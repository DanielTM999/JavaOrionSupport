package dtm.ide.index;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class JavaLexicalIndex {

    private static final int MAX_FILES = 20_000;
    private static final int SIGNATURE_LONGS = 32;
    private static final int SIGNATURE_BITS = SIGNATURE_LONGS * 64;
    private static final int MAX_USAGE_FILES = 4_000;
    private static final int MAX_USAGES = 500;

    public record Declaration(String name, SymbolKind kind, Path file, Range range) {
    }

    public record ProjectSymbol(String name, SymbolKind kind, String detail) {
    }

    public record Snapshot(Map<String, List<Declaration>> declarations,
                           List<FileSignature> files,
                           Map<String, ProjectSymbol> projectSymbols) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), List.of(), Map.of());
        }
    }

    public record FileSignature(Path file, long[] bits) {

        boolean mayContain(String name) {
            for (int hash : hashes(name)) {
                if ((bits[hash >>> 6] & (1L << (hash & 63))) == 0L) {
                    return false;
                }
            }
            return true;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "java-lexical-index");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicLong generation = new AtomicLong();

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public boolean isEmpty() {
        return snapshot.get().declarations().isEmpty();
    }

    public void clear() {
        generation.incrementAndGet();
        executor.submit(() -> snapshot.set(Snapshot.empty()));
    }

    public void rebuild(JavaProjectDescriptor descriptor) {
        long ticket = generation.incrementAndGet();
        if (descriptor == null) {
            executor.submit(() -> snapshot.set(Snapshot.empty()));
            return;
        }
        executor.submit(() -> {
            Snapshot scanned = scan(descriptor, ticket);
            if (generation.get() == ticket) {
                snapshot.set(scanned);
            }
        });
    }

    public void refreshFile(Path file, String source) {
        if (!JavaProjectConventions.isJava(file) || file == null) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        executor.submit(() -> snapshot.updateAndGet(current -> merge(current, normalized, source)));
    }

    public List<Location> definitions(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<Declaration> declared = snapshot.get().declarations().get(name);
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<Location> locations = new ArrayList<>(declared.size());
        for (Declaration declaration : declared) {
            locations.add(Location.of(declaration.file().toUri().toString(), declaration.range()));
        }
        return List.copyOf(locations);
    }

    public List<DocumentSymbol> outline(String source) {
        return JavaLexicalSource.outline(source);
    }

    public List<Location> usages(String name, long budgetMs) {
        if (name == null || name.length() < 2 || JavaLexicalSource.isKeyword(name)) {
            return List.of();
        }
        Snapshot current = snapshot.get();
        if (current.files().isEmpty()) {
            return List.of();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, budgetMs));
        Set<String> target = Set.of(name);
        List<Location> found = new ArrayList<>();
        int visited = 0;
        for (FileSignature signature : current.files()) {
            if (found.size() >= MAX_USAGES || visited >= MAX_USAGE_FILES
                    || System.nanoTime() > deadline) {
                break;
            }
            if (!signature.mayContain(name)) {
                continue;
            }
            visited++;
            String code = JavaLexicalSource.mask(
                    JavaProjectConventions.readOrEmpty(signature.file()));
            if (code.isEmpty()) {
                continue;
            }
            int[] lineStarts = JavaLexicalSource.lineStarts(code);
            String uri = signature.file().toUri().toString();
            for (int[] span : JavaLexicalSource.occurrences(code, target)) {
                found.add(Location.of(uri,
                        JavaLexicalSource.rangeOf(lineStarts, span[0], span[1])));
                if (found.size() >= MAX_USAGES) {
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    public boolean awaitIdle(long timeoutMs) {
        try {
            executor.submit(() -> {
            }).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public Collection<ProjectSymbol> projectSymbols() {
        return snapshot.get().projectSymbols().values();
    }

    private Snapshot scan(JavaProjectDescriptor descriptor, long ticket) {
        Map<String, List<Declaration>> declarations = new LinkedHashMap<>();
        List<FileSignature> files = new ArrayList<>();
        Map<String, ProjectSymbol> symbols = new LinkedHashMap<>();
        int visited = 0;

        outer:
        for (JavaModule module : descriptor.modules()) {
            List<Path> roots = new ArrayList<>(module.existingSourceRoots());
            roots.addAll(module.existingTestRoots());
            for (Path root : roots) {
                int remaining = MAX_FILES - visited;
                if (remaining <= 0 || generation.get() != ticket) {
                    break outer;
                }
                for (Path file : JavaProjectConventions.javaSources(root, 0, remaining)) {
                    if (generation.get() != ticket) {
                        break outer;
                    }
                    visited++;
                    indexFile(file.toAbsolutePath().normalize(),
                            JavaProjectConventions.readOrEmpty(file), declarations, files, symbols);
                }
            }
        }
        return freeze(declarations, files, symbols);
    }

    private static void indexFile(Path file, String source,
                                  Map<String, List<Declaration>> declarations,
                                  List<FileSignature> files,
                                  Map<String, ProjectSymbol> symbols) {
        if (source == null || source.isBlank()) {
            return;
        }
        String detail = file.getFileName() == null ? "" : file.getFileName().toString();
        for (JavaLexicalSource.Declared declared : JavaLexicalSource.declarations(source)) {
            declarations.computeIfAbsent(declared.name(), key -> new ArrayList<>())
                    .add(new Declaration(declared.name(), declared.kind(), file, declared.range()));
            symbols.put(declared.name(),
                    new ProjectSymbol(declared.name(), declared.kind(), detail));
        }
        String masked = JavaLexicalSource.mask(source);
        JavaLexicalSource.identifiers(masked, name -> symbols.putIfAbsent(name,
                new ProjectSymbol(name,
                        Character.isUpperCase(name.charAt(0))
                                ? SymbolKind.CLASS : SymbolKind.VARIABLE,
                        detail)));
        files.add(new FileSignature(file, signatureOf(masked)));
    }

    private static Snapshot freeze(Map<String, List<Declaration>> declarations,
                                   List<FileSignature> files,
                                   Map<String, ProjectSymbol> symbols) {
        Map<String, List<Declaration>> frozen = new LinkedHashMap<>(declarations.size());
        declarations.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return new Snapshot(Map.copyOf(frozen), List.copyOf(files), Map.copyOf(symbols));
    }

    private static Snapshot merge(Snapshot current, Path file, String source) {
        Map<String, List<Declaration>> declarations = new LinkedHashMap<>();
        current.declarations().forEach((key, value) -> {
            List<Declaration> kept = new ArrayList<>(value.size());
            for (Declaration declaration : value) {
                if (!declaration.file().equals(file)) {
                    kept.add(declaration);
                }
            }
            if (!kept.isEmpty()) {
                declarations.put(key, kept);
            }
        });
        List<FileSignature> files = new ArrayList<>(current.files().size() + 1);
        for (FileSignature signature : current.files()) {
            if (!signature.file().equals(file)) {
                files.add(signature);
            }
        }
        Map<String, ProjectSymbol> symbols = new LinkedHashMap<>(current.projectSymbols());
        indexFile(file, source, declarations, files, symbols);
        return freeze(declarations, files, symbols);
    }

    private static long[] signatureOf(String maskedCode) {
        long[] bits = new long[SIGNATURE_LONGS];
        JavaLexicalSource.identifiers(maskedCode, name -> {
            for (int hash : hashes(name)) {
                bits[hash >>> 6] |= 1L << (hash & 63);
            }
        });
        return bits;
    }

    private static int[] hashes(String name) {
        int first = name.hashCode();
        int second = first * 0x9E3779B9;
        return new int[]{
                Math.floorMod(first, SIGNATURE_BITS),
                Math.floorMod(second, SIGNATURE_BITS)};
    }

}
