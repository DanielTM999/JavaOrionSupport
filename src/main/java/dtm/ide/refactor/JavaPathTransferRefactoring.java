package dtm.ide.refactor;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.api.project.tree.PathTransferDecision;
import dtm.ide.api.project.tree.PathTransferKind;
import dtm.ide.api.project.tree.PathTransferRequest;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.TypeMoveSupport;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.ui.JavaCopyDialogPanel;
import dtm.ide.ui.JavaMoveDialogPanel;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Slf4j
public final class JavaPathTransferRefactoring {

    public interface Host {

        JavaProjectDescriptor descriptor();

        JavaLanguageServer readyServer();

        JavaMoveDialogPanel.Choice askMove(JavaPathTransferPlan plan);

        JavaCopyDialogPanel.Result askCopy(JavaPathTransferPlan plan, Map<Path, String> defaultNames);

        String readText(Path file);

        void warn(String message);
    }

    private record Pending(JavaPathTransferPlan plan, IdeWorkspaceEdit edit, boolean adjust) {
    }

    private final Host host;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public JavaPathTransferRefactoring(Host host) {
        this.host = host;
    }

    public PathTransferDecision before(PathTransferRequest request) {
        JavaPathTransferPlan plan = JavaPathTransferPlan.of(request, host.descriptor());
        if (plan.isEmpty()) {
            return PathTransferDecision.proceed();
        }
        return request.kind() == PathTransferKind.COPY ? beforeCopy(request, plan) : beforeMove(request, plan);
    }

    public IdeWorkspaceEdit after(PathTransferRequest request) {
        if (request == null) {
            return IdeWorkspaceEdit.empty();
        }
        Pending current = pending.remove(key(request));
        if (current == null || !current.adjust() || request.transfers().isEmpty()) {
            return IdeWorkspaceEdit.empty();
        }
        JavaPathTransferPlan plan = current.plan().restrictTo(request.transfers());
        if (plan.isEmpty()) {
            return IdeWorkspaceEdit.empty();
        }
        try {
            return request.kind() == PathTransferKind.COPY
                    ? rewrites(copyRewrites(plan))
                    : moveEdit(plan, current.edit());
        } catch (RuntimeException e) {
            log.warn("Falha ao ajustar os fontes Java depois de {} para {}", request.kind(),
                    request.targetDirectory(), e);
            host.warn(e.getMessage());
            throw e;
        }
    }

    private PathTransferDecision beforeMove(PathTransferRequest request, JavaPathTransferPlan plan) {
        JavaMoveDialogPanel.Choice choice = host.askMove(plan);
        if (choice == null || choice == JavaMoveDialogPanel.Choice.CANCEL) {
            return PathTransferDecision.cancel();
        }
        if (choice == JavaMoveDialogPanel.Choice.MOVE_ONLY) {
            return PathTransferDecision.proceed();
        }
        if (plan.hasConflicts()) {
            host.warn("Conflito no destino: " + String.join(", ", plan.conflicts()));
            return PathTransferDecision.cancel();
        }
        IdeWorkspaceEdit prepared = choice == JavaMoveDialogPanel.Choice.PACKAGE_ONLY
                ? packageOnlyEdit(plan) : computeMoveEdit(plan);
        if (prepared == null) {
            prepared = rewrites(lexicalMove(plan, true));
            host.warn(text("move.lexicalFallback",
                    "The Java server was not available: package and imports were adjusted by text; review the references."));
        }
        pending.put(key(request), new Pending(plan, prepared, true));
        return PathTransferDecision.proceed();
    }

    private IdeWorkspaceEdit packageOnlyEdit(JavaPathTransferPlan plan) {
        Map<Path, String> texts = new LinkedHashMap<>();
        for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
            String original = host.readText(file.source());
            if (original == null) throw new IllegalStateException("Cannot read " + file.source());
            texts.put(file.source(), JavaSourceRelocator.withPackage(original, file.newPackage()));
        }
        for (JavaPathTransferPlan.FolderTransfer folder : plan.folders()) {
            for (Path file : javaFilesUnder(folder.source())) {
                String relative = folder.source().relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
                String pkg = folder.newPackage() + (relative.isEmpty() ? "" : "." + relative);
                texts.put(file, JavaSourceRelocator.withPackage(host.readText(file), pkg));
            }
        }
        return rewrites(texts);
    }

    private PathTransferDecision beforeCopy(PathTransferRequest request, JavaPathTransferPlan plan) {
        Map<Path, String> defaults = defaultCopyNames(plan);
        JavaCopyDialogPanel.Result result = host.askCopy(plan, defaults);
        if (result == null) {
            return PathTransferDecision.cancel();
        }
        if (!result.adjustSources() || plan.hasConflicts()) {
            return PathTransferDecision.proceed();
        }
        Map<Path, Path> retargets = new LinkedHashMap<>();
        for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
            String name = result.newTypeNames().getOrDefault(file.source(), defaults.get(file.source()));
            if (JavaPathTransferPlan.isValidTypeName(name)) {
                retargets.put(file.source(), file.target().getParent().resolve(name + ".java"));
            }
        }
        PathTransferRequest retargeted = request.retarget(retargets);
        pending.put(key(retargeted), new Pending(JavaPathTransferPlan.of(retargeted, host.descriptor()), null, true));
        return PathTransferDecision.retarget(retargets);
    }

    IdeWorkspaceEdit computeMoveEdit(JavaPathTransferPlan plan) {
        JavaLanguageServer lsp = host.readyServer();
        TypeMoveSupport moves = lsp == null ? null : lsp.extension(TypeMoveSupport.class);
        if (moves == null) {
            return null;
        }
        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>();
        List<Path> files = plan.files().stream()
                .filter(JavaPathTransferPlan.FileTransfer::packageChanged)
                .map(JavaPathTransferPlan.FileTransfer::source)
                .toList();
        if (!files.isEmpty()) {
            IdeWorkspaceEdit edit = moves.moveTypesWorkspace(files, plan.targetDirectory());
            if (edit.isEmpty()) {
                log.info("java/move sem edicoes para {}: {}", files, moves.lastMoveProblem());
                return null;
            }
            operations.addAll(edit.operations());
        }
        for (JavaPathTransferPlan.FolderTransfer folder : plan.folders()) {
            IdeWorkspaceEdit edit = lsp.willRenameFilesWorkspace(Map.of(folder.source(), folder.target()));
            if (edit.isEmpty() && moves.lastMoveProblem() != null) {
                log.info("workspace/willRenameFiles sem edicoes para {}: {}", folder.source(), moves.lastMoveProblem());
                return null;
            }
            operations.addAll(edit.operations());
        }
        return new IdeWorkspaceEdit(operations);
    }

    private IdeWorkspaceEdit moveEdit(JavaPathTransferPlan plan, IdeWorkspaceEdit edit) {
        if (edit != null) {
            return JavaPathTransferPlan.relocate(edit, plan.moves());
        }
        IdeWorkspaceEdit lexical = rewrites(lexicalMove(plan));
        host.warn(text("move.lexicalFallback",
                "The Java server was not available: package and imports were adjusted by text; review the references."));
        return lexical;
    }

    Map<Path, String> lexicalMove(JavaPathTransferPlan plan) {
        return lexicalMove(plan, false);
    }

    private Map<Path, String> lexicalMove(JavaPathTransferPlan plan, boolean beforeMove) {
        Map<Path, String> texts = new LinkedHashMap<>();
        List<Path> projectSources = projectJavaFiles();
        for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
            Path movedFile = beforeMove ? file.source() : file.target();
            String movedText = textOf(texts, movedFile);
            if (movedText != null) {
                texts.put(movedFile, JavaSourceRelocator.relocateMovedType(movedText, file.newPackage(),
                        file.oldType(), file.newType(), file.oldPackage(), typesIn(file.source().getParent())));
            }
            String oldFqn = qualified(file.oldPackage(), file.oldType());
            String newFqn = qualified(file.newPackage(), file.newType());
            for (Path other : projectSources) {
                if (other.equals(movedFile)) {
                    continue;
                }
                String text = textOf(texts, other);
                if (text == null) {
                    continue;
                }
                String updated = file.oldPackage().isEmpty() ? text
                        : JavaSourceRelocator.replaceQualifiedPrefix(text, oldFqn, newFqn);
                if (other.getParent().equals(file.source().getParent()) && !file.newPackage().isEmpty()
                        && !JavaSourceRelocator.referencedTypes(updated, JavaPathTransferPlan.typeName(other),
                        List.of(file.oldType())).isEmpty()) {
                    updated = JavaSourceRelocator.addImports(updated, List.of(newFqn));
                }
                if (!updated.equals(text)) {
                    texts.put(other, updated);
                }
            }
        }
        for (JavaPathTransferPlan.FolderTransfer folder : plan.folders()) {
            for (Path source : projectJavaFiles()) {
                String text = textOf(texts, source);
                if (text == null) {
                    continue;
                }
                String updated = JavaSourceRelocator.replaceQualifiedPrefix(text, folder.oldPackage(), folder.newPackage());
                if (!updated.equals(text)) {
                    texts.put(source, updated);
                }
            }
        }
        return texts;
    }

    private Map<Path, String> copyRewrites(JavaPathTransferPlan plan) {
        Map<Path, String> texts = new LinkedHashMap<>();
        for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
            String text = textOf(texts, file.target());
            if (text == null) {
                continue;
            }
            texts.put(file.target(), JavaSourceRelocator.relocateMovedType(text, file.newPackage(),
                    file.oldType(), file.newType(), file.oldPackage(), typesIn(file.source().getParent())));
        }
        for (JavaPathTransferPlan.FolderTransfer folder : plan.folders()) {
            for (Path copied : javaFilesUnder(folder.target())) {
                String text = textOf(texts, copied);
                if (text != null) {
                    texts.put(copied, JavaSourceRelocator.replaceQualifiedPrefix(text,
                            folder.oldPackage(), folder.newPackage()));
                }
            }
        }
        return texts;
    }

    private IdeWorkspaceEdit rewrites(Map<Path, String> rewritten) {
        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>();
        rewritten.forEach((file, updated) -> {
            String current = host.readText(file);
            if (current == null) {
                return;
            }
            String normalizedCurrent = normalize(current);
            String normalizedUpdated = normalize(updated);
            if (!normalizedCurrent.equals(normalizedUpdated)) {
                operations.add(new IdeWorkspaceEdit.TextEdits(file,
                        List.of(new TextEdit(wholeDocument(normalizedCurrent), normalizedUpdated))));
            }
        });
        return new IdeWorkspaceEdit(operations);
    }

    static Range wholeDocument(String normalizedText) {
        int line = 0;
        int lastLineStart = 0;
        for (int i = 0; i < normalizedText.length(); i++) {
            if (normalizedText.charAt(i) == '\n') {
                line++;
                lastLineStart = i + 1;
            }
        }
        return new Range(Position.of(0, 0), Position.of(line, normalizedText.length() - lastLineStart));
    }

    Map<Path, String> defaultCopyNames(JavaPathTransferPlan plan) {
        Map<Path, String> names = new LinkedHashMap<>();
        Set<Path> reserved = new HashSet<>();
        for (JavaPathTransferPlan.FileTransfer file : plan.files()) {
            Path directory = file.target().getParent();
            String name = file.oldType();
            boolean samePlace = directory.equals(file.source().getParent());
            if (samePlace || taken(directory.resolve(name + ".java"), reserved)) {
                String base = name + "Copy";
                name = base;
                int index = 2;
                while (taken(directory.resolve(name + ".java"), reserved)) {
                    name = base + index++;
                }
            }
            reserved.add(directory.resolve(name + ".java"));
            names.put(file.source(), name);
        }
        return names;
    }

    private static boolean taken(Path candidate, Set<Path> reserved) {
        return reserved.contains(candidate) || Files.exists(candidate);
    }

    private String textOf(Map<Path, String> texts, Path file) {
        String cached = texts.get(file);
        if (cached != null) {
            return cached;
        }
        String text = host.readText(file);
        return text == null ? null : normalize(text);
    }

    private List<Path> projectJavaFiles() {
        JavaProjectDescriptor descriptor = host.descriptor();
        if (descriptor == null) {
            return List.of();
        }
        Set<Path> files = new LinkedHashSet<>();
        for (JavaModule module : descriptor.modules()) {
            List<Path> roots = new ArrayList<>(module.sourceRoots());
            roots.addAll(module.testRoots());
            roots.forEach(root -> files.addAll(javaFilesUnder(root)));
        }
        return List.copyOf(files);
    }

    private static List<Path> javaFilesUnder(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    private static List<String> typesIn(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(JavaPathTransferPlan::typeName)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    private static String qualified(String packageName, String type) {
        return packageName == null || packageName.isEmpty() ? type : packageName + "." + type;
    }

    private static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String key(PathTransferRequest request) {
        return request.kind() + "|" + request.targetDirectory();
    }

    private static String text(String key, String fallback) {
        return I18n.getText(JavaPathTransferRefactoring.class, key, fallback);
    }
}
