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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative fallback for Safe Delete when the language server has not indexed a source yet. */
public final class JavaSafeDeleteScanner {

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
                    + "|@interface\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final int MAX_FILES = 50_000;

    private JavaSafeDeleteScanner() {
    }

    public static List<Location> findExternalUsages(Path projectRoot, List<Path> targets) {
        if (projectRoot == null || targets == null || targets.isEmpty()) {
            return List.of();
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> deleted = normalizedTargets(targets);
        Set<String> symbols = declaredTypeNames(deleted);
        if (symbols.isEmpty() || !Files.isDirectory(root)) {
            return List.of();
        }

        List<Location> usages = new ArrayList<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && (JavaProjectConventions.isIgnoredFolder(dir)
                            || isInside(dir, deleted))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return visited[0] >= MAX_FILES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] > MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isRegularFile() && JavaProjectConventions.isJava(file)
                            && !isInside(file, deleted)) {
                        scanFile(file, symbols, usages);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Safe Delete still has the language-server result when a folder cannot be scanned.
        }
        return List.copyOf(usages);
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

    private static void scanFile(Path file, Set<String> symbols, List<Location> usages) {
        String text = read(file);
        if (text.isEmpty()) {
            return;
        }
        String code = maskNonCode(text);
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
