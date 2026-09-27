package dtm.ide.index;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectSources;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public final class JavaLexicalIndex {

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

    private record IndexedFile(List<Declaration> declarations, FileSignature signature,
                               Map<String, ProjectSymbol> symbols,
                               Map<String, Integer> selfReferences) {
    }

    private record BufferUsage(Path file, String text, Set<String> methods,
                               Map<String, Integer> references) {

        int references(String name) {
            return references.getOrDefault(name, 0);
        }
    }

    private static final class State {
        private final Map<Path, IndexedFile> files = new LinkedHashMap<>();
        private final Map<String, LinkedHashMap<Path, List<Declaration>>> declarations =
                new LinkedHashMap<>();
        private final Map<String, LinkedHashMap<Path, ProjectSymbol>> symbols =
                new LinkedHashMap<>();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "java-lexical-index");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private State state = new State();
    private volatile BufferUsage lastBuffer;

    public Snapshot snapshot() {
        lock.readLock().lock();
        try {
            Map<String, List<Declaration>> declarations = new LinkedHashMap<>();
            state.declarations.forEach((name, byFile) -> declarations.put(name,
                    byFile.values().stream().flatMap(List::stream).toList()));
            List<FileSignature> files = state.files.values().stream()
                    .map(IndexedFile::signature).toList();
            return new Snapshot(Map.copyOf(declarations), files, projectSymbolsLocked());
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return state.declarations.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        generation.incrementAndGet();
        submit(() -> replaceState(new State()));
    }

    public void rebuild(JavaProjectDescriptor descriptor) {
        rebuild(descriptor, descriptor == null ? null : JavaProjectSources.collect(descriptor));
    }

    public void rebuild(JavaProjectDescriptor descriptor, JavaProjectSources sources) {
        long ticket = generation.incrementAndGet();
        if (descriptor == null || sources == null) {
            submit(() -> replaceState(new State()));
            return;
        }
        submit(() -> {
            long started = System.nanoTime();
            State rebuilt = new State();
            for (JavaProjectSources.Source source : sources.files()) {
                if (generation.get() != ticket) {
                    return;
                }
                add(rebuilt, source.file(), parse(source.file(), source.content()));
            }
            if (generation.get() == ticket) {
                replaceState(rebuilt);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                log.info("Indice Java local: {} arquivo(s), {} ms em {}",
                        rebuilt.files.size(), elapsedMs, descriptor.root());
            }
        });
    }

    public void refreshFile(Path file, String source) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        submit(() -> {
            IndexedFile indexed = parse(normalized, source);
            lock.writeLock().lock();
            try {
                remove(state, normalized);
                add(state, normalized, indexed);
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    public List<Location> definitions(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            Map<Path, List<Declaration>> byFile = state.declarations.get(name);
            if (byFile == null || byFile.isEmpty()) {
                return List.of();
            }
            List<Location> locations = new ArrayList<>();
            byFile.values().forEach(declarations -> declarations.forEach(declaration ->
                    locations.add(Location.of(declaration.file().toUri().toString(),
                            declaration.range()))));
            return List.copyOf(locations);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<DocumentSymbol> outline(String source) {
        return JavaLexicalSource.outline(source);
    }

    public List<Location> usages(String name, long budgetMs) {
        if (name == null || name.length() < 2 || JavaLexicalSource.isKeyword(name)) {
            return List.of();
        }
        List<FileSignature> signatures;
        lock.readLock().lock();
        try {
            signatures = state.files.values().stream().map(IndexedFile::signature).toList();
        } finally {
            lock.readLock().unlock();
        }
        if (signatures.isEmpty()) {
            return List.of();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, budgetMs));
        Set<String> target = Set.of(name);
        List<Location> found = new ArrayList<>();
        int visited = 0;
        for (FileSignature signature : signatures) {
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

    public List<Path> filesMayContain(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            return state.files.values().stream()
                    .map(IndexedFile::signature)
                    .filter(signature -> signature.mayContain(name))
                    .map(FileSignature::file)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<String> unusedMethods(Collection<String> names, Path currentFile, String currentText) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Path current = currentFile == null ? null : currentFile.toAbsolutePath().normalize();
        BufferUsage buffer = bufferUsage(current, currentText);
        Set<String> unused = new LinkedHashSet<>();
        lock.readLock().lock();
        try {
            if (state.files.isEmpty()) {
                return Set.of();
            }
            for (String name : names) {
                if (name != null && name.length() > 1 && !JavaLexicalSource.isKeyword(name)
                        && isUnusedMethod(name, current, buffer)) {
                    unused.add(name);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return Set.copyOf(unused);
    }

    private boolean isUnusedMethod(String name, Path current, BufferUsage buffer) {
        boolean method = buffer != null && buffer.methods().contains(name);
        int references = buffer == null ? 0 : buffer.references(name);
        if (references > 0) {
            return false;
        }
        LinkedHashMap<Path, ProjectSymbol> containing = state.symbols.get(name);
        if (containing != null) {
            for (Path file : containing.keySet()) {
                if (buffer != null && file.equals(current)) {
                    continue;
                }
                IndexedFile indexed = state.files.get(file);
                Integer self = indexed == null ? null : indexed.selfReferences().get(name);
                if (self == null || self > 0) {
                    return false;
                }
                method |= indexed.declarations().stream().anyMatch(declaration ->
                        name.equals(declaration.name()) && declaration.kind() == SymbolKind.METHOD);
            }
        }
        return method;
    }

    private BufferUsage bufferUsage(Path file, String text) {
        if (text == null) {
            return null;
        }
        BufferUsage cached = lastBuffer;
        if (cached != null && Objects.equals(cached.file(), file) && cached.text().equals(text)) {
            return cached;
        }
        List<JavaLexicalSource.Declared> declared = JavaLexicalSource.declarations(text);
        Map<String, Integer> references = selfReferences(declared, JavaLexicalSource.mask(text), true);
        Set<String> methods = new LinkedHashSet<>();
        declared.stream().filter(entry -> entry.kind() == SymbolKind.METHOD)
                .forEach(entry -> methods.add(entry.name()));
        BufferUsage usage = new BufferUsage(file, text, Set.copyOf(methods), references);
        lastBuffer = usage;
        return usage;
    }

    private static Map<String, Integer> selfReferences(List<JavaLexicalSource.Declared> declared,
                                                       String masked, boolean everyIdentifier) {
        Map<String, Integer> references = new HashMap<>();
        if (everyIdentifier) {
            JavaLexicalSource.identifiers(masked, name -> references.merge(name, 1, Integer::sum));
        } else {
            Set<String> names = new HashSet<>();
            declared.forEach(entry -> names.add(entry.name()));
            for (int[] span : JavaLexicalSource.occurrences(masked, names)) {
                references.merge(masked.substring(span[0], span[1]), 1, Integer::sum);
            }
        }
        for (JavaLexicalSource.Declared entry : declared) {
            int adjustment = isEntryPoint(entry) ? 0 : -1;
            references.merge(entry.name(), adjustment, Integer::sum);
        }
        return references;
    }

    private static boolean isEntryPoint(JavaLexicalSource.Declared entry) {
        return entry.kind() == SymbolKind.METHOD && (entry.annotated() || "main".equals(entry.name()));
    }

    public boolean awaitIdle(long timeoutMs) {
        if (closed.get()) {
            return executor.isTerminated() || executor.isShutdown();
        }
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
        lock.readLock().lock();
        try {
            return projectSymbolsLocked().values();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            generation.incrementAndGet();
            executor.shutdownNow();
        }
    }

    private void submit(Runnable task) {
        if (closed.get()) {
            return;
        }
        try {
            executor.submit(task);
        } catch (RejectedExecutionException ignored) {
            // Shutdown raced with an editor or watcher callback.
        }
    }

    private void replaceState(State replacement) {
        lock.writeLock().lock();
        try {
            state = replacement;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static IndexedFile parse(Path file, String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        List<Declaration> declarations = new ArrayList<>();
        Map<String, ProjectSymbol> symbols = new LinkedHashMap<>();
        String detail = file.getFileName() == null ? "" : file.getFileName().toString();
        List<JavaLexicalSource.Declared> declaredInFile = JavaLexicalSource.declarations(source);
        for (JavaLexicalSource.Declared declared : declaredInFile) {
            declarations.add(new Declaration(declared.name(), declared.kind(), file,
                    declared.range()));
            symbols.put(declared.name(),
                    new ProjectSymbol(declared.name(), declared.kind(), detail));
        }
        String masked = JavaLexicalSource.mask(source);
        JavaLexicalSource.identifiers(masked, name -> symbols.putIfAbsent(name,
                new ProjectSymbol(name,
                        Character.isUpperCase(name.charAt(0))
                                ? SymbolKind.CLASS : SymbolKind.VARIABLE,
                        detail)));
        return new IndexedFile(List.copyOf(declarations),
                new FileSignature(file, signatureOf(masked)), Map.copyOf(symbols),
                Map.copyOf(selfReferences(declaredInFile, masked, false)));
    }

    private static void add(State target, Path file, IndexedFile indexed) {
        if (indexed == null) {
            return;
        }
        target.files.put(file, indexed);
        Map<String, List<Declaration>> byName = new LinkedHashMap<>();
        for (Declaration declaration : indexed.declarations()) {
            byName.computeIfAbsent(declaration.name(), ignored -> new ArrayList<>())
                    .add(declaration);
        }
        byName.forEach((name, declarations) -> target.declarations
                .computeIfAbsent(name, ignored -> new LinkedHashMap<>())
                .put(file, List.copyOf(declarations)));
        indexed.symbols().forEach((name, symbol) -> target.symbols
                .computeIfAbsent(name, ignored -> new LinkedHashMap<>()).put(file, symbol));
    }

    private static void remove(State target, Path file) {
        IndexedFile previous = target.files.remove(file);
        if (previous == null) {
            return;
        }
        Set<String> declaredNames = new LinkedHashSet<>();
        previous.declarations().forEach(declaration -> declaredNames.add(declaration.name()));
        declaredNames.forEach(name -> removeContribution(target.declarations, name, file));
        previous.symbols().keySet().forEach(name -> removeContribution(target.symbols, name, file));
    }

    private static <T> void removeContribution(
            Map<String, LinkedHashMap<Path, T>> index, String name, Path file) {
        LinkedHashMap<Path, T> byFile = index.get(name);
        if (byFile == null) {
            return;
        }
        byFile.remove(file);
        if (byFile.isEmpty()) {
            index.remove(name);
        }
    }

    private Map<String, ProjectSymbol> projectSymbolsLocked() {
        Map<String, ProjectSymbol> merged = new LinkedHashMap<>();
        state.symbols.forEach((name, byFile) -> {
            ProjectSymbol selected = null;
            for (ProjectSymbol contribution : byFile.values()) {
                selected = contribution;
            }
            if (selected != null) {
                merged.put(name, selected);
            }
        });
        return Map.copyOf(merged);
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
