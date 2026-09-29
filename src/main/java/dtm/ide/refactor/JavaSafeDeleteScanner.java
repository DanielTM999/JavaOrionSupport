package dtm.ide.refactor;

import dtm.ide.project.JavaProjectConventions;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative fallback for Safe Delete when the language server has not indexed a source yet. */
public final class JavaSafeDeleteScanner {

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
                    + "|@interface\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w$.\\s]+?)\\s*;");
    private static final Pattern IMPORT = Pattern.compile(
            "\\bimport\\s+(?:static\\s+)?([\\w$][\\w$.\\s]*(?:\\.\\s*\\*)?)\\s*;");
    private static final int MAX_FILES = 50_000;

    private JavaSafeDeleteScanner() {
    }

    public record ScanResult(List<Location> locations, boolean complete) {
        public ScanResult { locations = List.copyOf(locations); }
    }

    public static List<Location> findExternalUsages(Path projectRoot, List<Path> targets) {
        return scan(projectRoot, targets, java.util.Map.of()).locations();
    }

    public static ScanResult scan(Path projectRoot, List<Path> targets, java.util.Map<Path, String> buffers) {
        return scan(projectRoot, targets, buffers, MAX_FILES);
    }

    static ScanResult scan(Path projectRoot, List<Path> targets, java.util.Map<Path, String> buffers, int limit) {
        if (projectRoot == null || targets == null || targets.isEmpty()) return new ScanResult(List.of(), false);
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> deleted = normalizedTargets(targets);
        Map<String, Set<String>> symbols = new LinkedHashMap<>();
        boolean[] complete = {true};
        for (Path target : deleted) {
            try (var walk = Files.walk(target)) {
                for (Path source : walk.filter(JavaProjectConventions::isJava).filter(Files::isRegularFile).toList()) {
                    String content = buffers.containsKey(source) ? buffers.get(source) : Files.readString(source);
                    String code = maskNonCode(content);
                    String packageName = packageOf(code);
                    String name = source.getFileName().toString();
                    declare(symbols, name.substring(0, name.length() - 5), packageName);
                    Matcher matcher = TYPE_DECLARATION.matcher(code);
                    while (matcher.find()) {
                        declare(symbols, matcher.group(1) == null ? matcher.group(2) : matcher.group(1), packageName);
                    }
                }
            } catch (IOException | java.io.UncheckedIOException | SecurityException failure) { complete[0] = false; }
        }
        if (!Files.isDirectory(root)) return new ScanResult(List.of(), false);
        List<Location> usages = new ArrayList<>();
        Set<Path> scanned = new LinkedHashSet<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && (JavaProjectConventions.isIgnoredFolder(dir) || isInside(dir, deleted))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] > limit || Thread.currentThread().isInterrupted()) {
                        complete[0] = false;
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isRegularFile() && JavaProjectConventions.isJava(file) && !isInside(file, deleted)) {
                        try {
                            String content = buffers.containsKey(file) ? buffers.get(file) : Files.readString(file);
                            scanText(file, content, symbols, usages);
                            scanned.add(file);
                        } catch (IOException | SecurityException failure) { complete[0] = false; }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                    complete[0] = false;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException failure) { complete[0] = false; }
        for (var buffer : buffers.entrySet()) {
            Path file = buffer.getKey();
            if (file.startsWith(root) && JavaProjectConventions.isJava(file)
                    && !isInside(file, deleted) && !scanned.contains(file)) {
                scanText(file, buffer.getValue(), symbols, usages);
            }
        }
        return new ScanResult(usages, complete[0]);
    }

    static Set<String> declaredTypeNames(Set<Path> targets) {
        Set<String> names = new LinkedHashSet<>();
        for (Path source : javaSources(targets)) {
            String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
            if (fileName.endsWith(".java") && fileName.length() > 5) {
                names.add(fileName.substring(0, fileName.length() - 5));
            }
            String text = read(source);
            Matcher matcher = TYPE_DECLARATION.matcher(maskNonCode(text));
            while (matcher.find()) {
                names.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
            }
        }
        return names;
    }

    private static void declare(Map<String, Set<String>> symbols, String name, String packageName) {
        symbols.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(packageName);
    }

    static String packageOf(String code) {
        Matcher matcher = PACKAGE.matcher(code);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", "") : "";
    }

    static Set<String> visibleSymbols(String code, Map<String, Set<String>> symbols) {
        String packageName = packageOf(code);
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(code);
        while (matcher.find()) {
            imports.add(matcher.group(1).replaceAll("\\s+", ""));
        }
        String compact = code.replaceAll("\\s*\\.\\s*", ".");
        Set<String> visible = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> symbol : symbols.entrySet()) {
            String name = symbol.getKey();
            Set<String> packages = symbol.getValue();
            List<String> explicit = imports.stream()
                    .filter(imported -> !imported.endsWith(".*") && imported.endsWith("." + name))
                    .toList();
            if (!explicit.isEmpty()) {
                if (explicit.stream().anyMatch(imported -> declaredIn(imported, packages))) {
                    visible.add(name);
                }
                continue;
            }
            boolean samePackage = packages.contains(packageName);
            boolean wildcard = packages.stream().anyMatch(declared -> !declared.isEmpty()
                    && imports.contains(declared + ".*"));
            boolean qualified = packages.stream().anyMatch(declared -> !declared.isEmpty()
                    && Pattern.compile("(?<![\\w$.])" + Pattern.quote(declared + "." + name) + "(?![\\w$])")
                    .matcher(compact).find());
            if (samePackage || wildcard || qualified) {
                visible.add(name);
            }
        }
        return visible;
    }

    private static boolean declaredIn(String imported, Set<String> packages) {
        return packages.stream().anyMatch(declared -> declared.isEmpty()
                ? !imported.contains(".")
                : imported.startsWith(declared + "."));
    }

    private static void scanText(Path file, String text, Map<String, Set<String>> declared,
                                 List<Location> usages) {
        if (text.isEmpty()) {
            return;
        }
        String code = maskNonCode(text);
        Set<String> symbols = visibleSymbols(code, declared);
        if (symbols.isEmpty()) {
            return;
        }
        int line = 0;
        int column = 0;
        for (int index = 0; index < code.length();) {
            char character = code.charAt(index);
            if (character == '\n') {
                line++;
                column = 0;
                index++;
                continue;
            }
            if (!Character.isJavaIdentifierStart(character)) {
                column++;
                index++;
                continue;
            }
            int start = index++;
            int startColumn = column++;
            while (index < code.length() && Character.isJavaIdentifierPart(code.charAt(index))) {
                index++;
                column++;
            }
            if (symbols.contains(code.substring(start, index))) {
                usages.add(Location.of(file.toUri().toString(),
                        Range.of(line, startColumn, line, column)));
            }
        }
    }

    public static String maskNonCode(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder(text);
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        boolean textBlock = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            boolean tripleQuote = current == '"' && i + 2 < text.length()
                    && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"';

            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                } else {
                    masked.setCharAt(i, ' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    masked.setCharAt(i, ' ');
                    masked.setCharAt(++i, ' ');
                    blockComment = false;
                } else if (current != '\n') {
                    masked.setCharAt(i, ' ');
                }
                continue;
            }
            if (textBlock) {
                if (tripleQuote) {
                    masked.setCharAt(i, ' ');
                    masked.setCharAt(++i, ' ');
                    masked.setCharAt(++i, ' ');
                    textBlock = false;
                } else if (current != '\n') {
                    masked.setCharAt(i, ' ');
                }
                continue;
            }
            if (string || character) {
                if (current != '\n') {
                    masked.setCharAt(i, ' ');
                }
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((string && current == '"') || (character && current == '\'')) {
                    string = false;
                    character = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                masked.setCharAt(i, ' ');
                masked.setCharAt(++i, ' ');
                lineComment = true;
            } else if (current == '/' && next == '*') {
                masked.setCharAt(i, ' ');
                masked.setCharAt(++i, ' ');
                blockComment = true;
            } else if (tripleQuote) {
                masked.setCharAt(i, ' ');
                masked.setCharAt(++i, ' ');
                masked.setCharAt(++i, ' ');
                textBlock = true;
            } else if (current == '"') {
                masked.setCharAt(i, ' ');
                string = true;
            } else if (current == '\'') {
                masked.setCharAt(i, ' ');
                character = true;
            }
        }
        return masked.toString();
    }

    private static Set<Path> normalizedTargets(List<Path> targets) {
        Set<Path> normalized = new LinkedHashSet<>();
        targets.stream().filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize()).forEach(normalized::add);
        return normalized;
    }

    private static List<Path> javaSources(Set<Path> targets) {
        List<Path> sources = new ArrayList<>();
        for (Path target : targets) {
            if (Files.isRegularFile(target)) {
                if (JavaProjectConventions.isJava(target)) {
                    sources.add(target);
                }
                continue;
            }
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (var walk = Files.walk(target)) {
                walk.filter(Files::isRegularFile).filter(JavaProjectConventions::isJava)
                        .forEach(sources::add);
            } catch (IOException ignored) {
            }
        }
        return sources;
    }

    private static boolean isInside(Path path, Set<Path> roots) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.stream().anyMatch(normalized::startsWith);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ignored) {
            return "";
        }
    }
}
