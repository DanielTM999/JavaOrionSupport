package dtm.ide.refactor;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.tree.PathTransfer;
import dtm.ide.api.project.tree.PathTransferKind;
import dtm.ide.api.project.tree.PathTransferRequest;
import dtm.ide.editor.JavaSourceText;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.spring.JavaSourceLexer;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class JavaPathTransferPlan {

    public record FileTransfer(Path source, Path target, String oldPackage, String newPackage,
                               String oldType, String newType) {

        public boolean packageChanged() {
            return !oldPackage.equals(newPackage);
        }

        public boolean typeRenamed() {
            return !oldType.equals(newType);
        }
    }

    public record FolderTransfer(Path source, Path target, String oldPackage, String newPackage) {
    }

    private final PathTransferKind kind;
    private final Path targetDirectory;
    private final String targetPackage;
    private final List<FileTransfer> files;
    private final List<FolderTransfer> folders;
    private final List<String> conflicts;

    private JavaPathTransferPlan(PathTransferKind kind, Path targetDirectory, String targetPackage,
                                 List<FileTransfer> files, List<FolderTransfer> folders, List<String> conflicts) {
        this.kind = kind;
        this.targetDirectory = targetDirectory;
        this.targetPackage = targetPackage;
        this.files = List.copyOf(files);
        this.folders = List.copyOf(folders);
        this.conflicts = List.copyOf(conflicts);
    }

    public static JavaPathTransferPlan of(PathTransferRequest request, JavaProjectDescriptor descriptor) {
        PathTransferKind kind = request == null ? PathTransferKind.MOVE : request.kind();
        Path targetDirectory = request == null ? null : request.targetDirectory();
        if (request == null || descriptor == null || targetDirectory == null) {
            return new JavaPathTransferPlan(kind, targetDirectory, "", List.of(), List.of(), List.of());
        }
        Path targetRoot = sourceRootOf(targetDirectory, descriptor);
        if (targetRoot == null) {
            return new JavaPathTransferPlan(kind, targetDirectory, "", List.of(), List.of(), List.of());
        }
        String targetPackage = packageName(targetRoot, targetDirectory);
        List<FileTransfer> files = new ArrayList<>();
        List<FolderTransfer> folders = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (PathTransfer transfer : request.transfers()) {
            Path source = transfer.source();
            Path target = transfer.target();
            if (source == null || target == null) {
                continue;
            }
            if (isJavaFile(source)) {
                Path sourceRoot = sourceRootOf(source.getParent(), descriptor);
                String oldPackage = sourceRoot == null
                        ? null : packageName(sourceRoot, source.getParent());
                if (oldPackage == null && kind == PathTransferKind.MOVE) {
                    continue;
                }
                FileTransfer file = new FileTransfer(source, target, oldPackage == null ? "" : oldPackage,
                        packageName(targetRoot, target.getParent()), typeName(source), typeName(target));
                if (kind == PathTransferKind.MOVE && !file.packageChanged() && !file.typeRenamed()) {
                    continue;
                }
                if (kind == PathTransferKind.MOVE && file.typeRenamed()) {
                    conflicts.add(file.oldType());
                }
                if (oldPackage == null) {
                    files.add(new FileTransfer(source, target, ownPackage(source), file.newPackage(),
                            file.oldType(), file.newType()));
                } else {
                    files.add(file);
                }
                continue;
            }
            if (Files.isDirectory(source)) {
                Path sourceRoot = sourceRootOf(source, descriptor);
                if (sourceRoot == null || sourceRoot.equals(source) || !containsJava(source)) {
                    continue;
                }
                String oldPackage = packageName(sourceRoot, source);
                String newPackage = packageName(targetRoot, target);
                if (kind == PathTransferKind.MOVE && oldPackage.equals(newPackage)) {
                    continue;
                }
                if (!isValidPackage(newPackage)) {
                    conflicts.add(target.getFileName() == null ? newPackage : target.getFileName().toString());
                }
                folders.add(new FolderTransfer(source, target, oldPackage, newPackage));
            }
        }
        return new JavaPathTransferPlan(kind, targetDirectory, targetPackage, files, folders, conflicts);
    }

    public PathTransferKind kind() {
        return kind;
    }

    public Path targetDirectory() {
        return targetDirectory;
    }

    public String targetPackage() {
        return targetPackage;
    }

    public List<FileTransfer> files() {
        return files;
    }

    public List<FolderTransfer> folders() {
        return folders;
    }

    public List<String> conflicts() {
        return conflicts;
    }

    public boolean isEmpty() {
        return files.isEmpty() && folders.isEmpty();
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    public Map<Path, Path> moves() {
        Map<Path, Path> moves = new LinkedHashMap<>();
        files.forEach(file -> moves.put(file.source(), file.target()));
        folders.forEach(folder -> moves.put(folder.source(), folder.target()));
        return moves;
    }

    public JavaPathTransferPlan restrictTo(List<PathTransfer> completed) {
        Map<Path, Path> actual = new LinkedHashMap<>();
        if (completed != null) {
            completed.forEach(transfer -> actual.put(transfer.source(), transfer.target()));
        }
        List<FileTransfer> keptFiles = files.stream()
                .filter(file -> actual.containsKey(file.source()))
                .map(file -> {
                    Path target = actual.get(file.source());
                    return new FileTransfer(file.source(), target, file.oldPackage(), file.newPackage(),
                            file.oldType(), typeName(target));
                })
                .toList();
        List<FolderTransfer> keptFolders = folders.stream()
                .filter(folder -> actual.containsKey(folder.source()))
                .map(folder -> new FolderTransfer(folder.source(), actual.get(folder.source()),
                        folder.oldPackage(), folder.newPackage()))
                .toList();
        return new JavaPathTransferPlan(kind, targetDirectory, targetPackage, keptFiles, keptFolders, conflicts);
    }

    public static IdeWorkspaceEdit relocate(IdeWorkspaceEdit edit, Map<Path, Path> moves) {
        if (edit == null || edit.isEmpty()) {
            return IdeWorkspaceEdit.empty();
        }
        Map<Path, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (!(operation instanceof IdeWorkspaceEdit.TextEdits textEdits) || textEdits.file() == null) {
                continue;
            }
            Path file = relocatePath(textEdits.file(), moves);
            byFile.computeIfAbsent(file, ignored -> new ArrayList<>()).addAll(textEdits.edits());
        }
        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>();
        byFile.forEach((file, edits) -> {
            if (!edits.isEmpty()) {
                operations.add(new IdeWorkspaceEdit.TextEdits(file, edits));
            }
        });
        return new IdeWorkspaceEdit(operations);
    }

    public static Path relocatePath(Path file, Map<Path, Path> moves) {
        Path normalized = file.toAbsolutePath().normalize();
        if (moves == null) {
            return normalized;
        }
        Path bestSource = null;
        for (Path source : moves.keySet()) {
            Path normalizedSource = source.toAbsolutePath().normalize();
            if (normalized.startsWith(normalizedSource)
                    && (bestSource == null || normalizedSource.getNameCount() > bestSource.getNameCount())) {
                bestSource = normalizedSource;
            }
        }
        if (bestSource == null) {
            return normalized;
        }
        Path target = moves.get(bestSource);
        if (target == null) {
            for (Map.Entry<Path, Path> entry : moves.entrySet()) {
                if (entry.getKey().toAbsolutePath().normalize().equals(bestSource)) {
                    target = entry.getValue();
                }
            }
        }
        if (target == null) {
            return normalized;
        }
        Path relative = bestSource.relativize(normalized);
        return relative.toString().isEmpty()
                ? target.toAbsolutePath().normalize()
                : target.resolve(relative).toAbsolutePath().normalize();
    }

    public static boolean isValidTypeName(String name) {
        return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

    public static boolean isValidPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        return packageName.isEmpty() || SourceVersion.isName(packageName);
    }

    public static String typeName(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    static Path sourceRootOf(Path path, JavaProjectDescriptor descriptor) {
        if (path == null || descriptor == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        JavaModule module = descriptor.moduleOf(normalized).orElse(descriptor.rootModule());
        if (module == null) {
            return null;
        }
        Path best = null;
        List<Path> roots = new ArrayList<>(module.sourceRoots());
        roots.addAll(module.testRoots());
        for (Path root : roots) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (normalized.startsWith(normalizedRoot)
                    && (best == null || normalizedRoot.getNameCount() > best.getNameCount())) {
                best = normalizedRoot;
            }
        }
        return best;
    }

    static String packageName(Path sourceRoot, Path directory) {
        Path relative = sourceRoot.relativize(directory.toAbsolutePath().normalize());
        String text = relative.toString();
        return text.isBlank() ? "" : text.replace('\\', '.').replace('/', '.');
    }

    private static String ownPackage(Path source) {
        try {
            return JavaSourceLexer.packageOf(JavaSourceText.blankComments(Files.readString(source)));
        } catch (IOException | UncheckedIOException e) {
            return "";
        }
    }

    private static boolean isJavaFile(Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")
                && !Files.isDirectory(path);
    }

    private static boolean containsJava(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.anyMatch(path -> Files.isRegularFile(path) && isJavaFile(path));
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }
}
