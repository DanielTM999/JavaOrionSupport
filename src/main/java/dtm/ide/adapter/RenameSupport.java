package dtm.ide.adapter;

import dtm.ide.api.hierarchy.CallHierarchyItem;
import dtm.ide.api.project.editor.IdeRenameContext;
import dtm.ide.api.project.editor.IdeRenamePolicy;
import dtm.ide.api.project.editor.IdeRenamePreparation;
import dtm.ide.api.project.editor.IdeRenamePrepareContext;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.index.JavaLocalScope;
import dtm.ide.lsp.LombokAccessorRename;
import dtm.ide.lsp.LombokAccessors;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.LanguageServerState;
import dtm.ide.lsp.api.PrepareRenameResult;
import dtm.ide.project.JavaProjectConventions;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.documenthighlight.DocumentHighlight;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class RenameSupport {

    private static final long RENAME_WAIT_BUDGET_MS = 60_000;
    private static final String RENAME_PROGRESS_ID = "javaRenameWait";
    private static final String RENAME_COMPUTE_PROGRESS_ID = "javaRename";
    private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    private final AdapterHost host;
    private final AtomicBoolean renameWaitCanceled = new AtomicBoolean();

    public RenameSupport(AdapterHost host) {
        this.host = host;
    }

    public List<TextEdit> computeRenameEdits(IdeRenameContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        JavaLanguageServer lsp = host.runningServerFor(filePath);
        return lsp == null ? null : lsp.rename(context.filePath(), context.text(),
                context.line(), context.col(), context.newName());
    }

    public IdeWorkspaceEdit computeRenameWorkspaceEdit(IdeRenameContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        host.showProgress(RENAME_COMPUTE_PROGRESS_ID, text("rename.progress", "Java: renomeando para '{name}'...")
                .replace("{name}", context.newName() == null ? "" : context.newName().trim()));
        try {
            JavaLanguageServer lsp = host.runningServerFor(filePath);
            if (lsp == null) {
                lsp = awaitServerForRename(filePath);
            }
            if (lsp == null) {
                return null;
            }
            IdeWorkspaceEdit edit = lsp.renameWorkspace(filePath, context.text(),
                    context.line(), context.col(), context.newName());
            String problem = lsp.lastRenameProblem();
            if (problem != null) {
                host.setStatusBarText(text("rename.unsafeEdit",
                        "Rename cancelado para proteger o código: {reason}").replace("{reason}", problem));
                return edit;
            }
            return edit == null || edit.isEmpty() ? edit : withLombokAccessors(lsp, context, edit);
        } finally {
            host.hideProgress(RENAME_COMPUTE_PROGRESS_ID);
        }
    }

    IdeWorkspaceEdit withLombokAccessors(JavaLanguageServer lsp, IdeRenameContext context, IdeWorkspaceEdit edit) {
        try {
            Path current = JavaProjectConventions.normalize(context.filePath());
            LombokAccessorRename.Result result = LombokAccessorRename.apply(lsp, current, context.text(),
                    context.line(), context.col(), context.newName(), edit, host.lexicalIndex()::filesMayContain,
                    file -> renameContentOf(lsp, file, current, context.text()));
            if (result.accessors().isEmpty()) {
                return edit;
            }
            log.info("Rename com Lombok: {} chamada(s) de {} atualizada(s)", result.calls(),
                    result.accessors().stream().map(LombokAccessors.Accessor::oldName).toList());
            if (result.calls() > 0) {
                host.setStatusBarText(text("rename.lombokAccessors", "Java: {count} chamada(s) de métodos do Lombok renomeada(s)")
                        .replace("{count}", Integer.toString(result.calls())));
            }
            return result.edit();
        } catch (Exception e) {
            log.warn("Nao foi possivel renomear os metodos gerados pelo Lombok: {}", e.toString());
            return edit;
        }
    }

    private static String renameContentOf(JavaLanguageServer lsp, Path file, Path current, String currentText) {
        Path normalized = JavaProjectConventions.normalize(file);
        if (normalized.equals(current)) {
            return currentText;
        }
        String open = lsp.documentContent(normalized);
        if (open != null) {
            return open;
        }
        String disk = JavaProjectConventions.readOrEmpty(normalized);
        return disk.startsWith("﻿") ? disk.substring(1) : disk;
    }

    public boolean isRenameEnabled(Path filePath) {
        return host.runningServerFor(filePath) != null;
    }

    public IdeRenamePolicy getRenamePolicy(Path filePath) {
        return JavaProjectConventions.isJava(filePath) ? IdeRenamePolicy.inline() : IdeRenamePolicy.undeclared();
    }

    public IdeRenamePreparation prepareRename(IdeRenamePrepareContext context) {
        Path filePath = context == null ? null : context.filePath();
        if (!JavaProjectConventions.isJava(filePath)) {
            return null;
        }
        String text = context.text();
        JavaLanguageServer lsp = host.runningServerFor(filePath);
        if (lsp == null) {
            return IdeRenamePreparation.rejected(isServerStarting(filePath)
                    ? text("rename.serverLoading", "Aguarde o servidor Java terminar de carregar para renomear")
                    : text("rename.serverUnavailable", "O servidor Java não está disponível para renomear com segurança"));
        }
        PrepareRenameResult prepared = lsp.prepareRename(filePath, text, context.line(), context.col());
        if (prepared != null && !prepared.renameable()) {
            return IdeRenamePreparation.rejected(text("rename.notRenameable",
                    "Este elemento não pode ser renomeado"));
        }
        Range range = prepared == null ? null : prepared.range();
        if (range == null) {
            range = identifierRangeAt(text, context.line(), context.col());
        }
        if (range == null) {
            return null;
        }
        String placeholder = prepared == null ? null : prepared.placeholder();
        if (placeholder == null || placeholder.isBlank()) {
            placeholder = textOfRange(text, range);
        }
        List<Range> occurrences = new ArrayList<>();
        List<DocumentHighlight> highlights = lsp.documentHighlights(filePath, text, context.line(), context.col());
        if (highlights != null) {
            for (DocumentHighlight highlight : highlights) {
                if (highlight != null && highlight.range() != null) {
                    occurrences.add(highlight.range());
                }
            }
        }
        SymbolKind kind = resolveRenameKind(lsp, filePath, text, context.line(), context.col(), placeholder);
        return IdeRenamePreparation.of(range, placeholder)
                .withOccurrences(occurrences)
                .withKind(kind);
    }

    public String validateRenameName(IdeRenamePrepareContext context, String newName) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) {
            return null;
        }
        if (JAVA_RESERVED_WORDS.contains(name)) {
            return text("rename.reservedWord", "'{name}' é uma palavra reservada do Java").replace("{name}", name);
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return text("rename.invalidIdentifier", "'{name}' não é um identificador Java válido").replace("{name}", name);
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return text("rename.invalidIdentifier", "'{name}' não é um identificador Java válido").replace("{name}", name);
            }
        }
        return null;
    }

    boolean isServerStarting(Path filePath) {
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null || !JavaProjectConventions.isJava(filePath)) {
            return false;
        }
        LanguageServerState state = lsp.getState();
        return state == LanguageServerState.NOT_STARTED
                || state == LanguageServerState.STARTING
                || state == LanguageServerState.INDEXING;
    }

    SymbolKind resolveRenameKind(JavaLanguageServer lsp, Path filePath, String text, int line, int col, String name) {
        Position position = new Position(line, col);
        SymbolKind declared = symbolKindAt(lsp.documentSymbols(filePath, text), position);
        if (declared != null) {
            return declared;
        }
        try {
            if (JavaLocalScope.at(text, line, col) != null) {
                return SymbolKind.VARIABLE;
            }
        } catch (Exception ignored) {
        }
        List<Location> definitions = lsp.definitions(filePath, text, line, col);
        Location definition = definitions == null || definitions.isEmpty() ? null : definitions.get(0);
        if (definition != null && definition.range() != null && isSameFile(definition, filePath)) {
            SymbolKind atDefinition = symbolKindAt(lsp.documentSymbols(filePath, text), definition.range().start());
            if (atDefinition != null) {
                return atDefinition;
            }
            return SymbolKind.VARIABLE;
        }
        List<CallHierarchyItem> calls = lsp.prepareCallHierarchy(filePath, text, line, col);
        if (calls != null && !calls.isEmpty() && calls.get(0).kind() != null) {
            return calls.get(0).kind();
        }
        if (definition != null && name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            String fileName = definitionFileName(definition);
            if (fileName != null && fileName.equals(name + ".java")) {
                return SymbolKind.CLASS;
            }
        }
        return SymbolKind.FIELD;
    }

    private static SymbolKind symbolKindAt(List<DocumentSymbol> symbols, Position position) {
        if (symbols == null || position == null) {
            return null;
        }
        for (DocumentSymbol symbol : symbols) {
            if (symbol.range() != null && !symbol.range().contains(position)) {
                continue;
            }
            SymbolKind nested = symbolKindAt(symbol.children(), position);
            if (nested != null) {
                return nested;
            }
            if (symbol.selectionRange() != null && symbol.selectionRange().contains(position)) {
                return symbol.kind();
            }
        }
        return null;
    }

    private static boolean isSameFile(Location location, Path filePath) {
        if (location.isLocal()) {
            return true;
        }
        try {
            Path target = Path.of(URI.create(location.uri())).toAbsolutePath().normalize();
            return filePath != null && target.equals(filePath.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static String definitionFileName(Location location) {
        if (location == null || location.isLocal()) {
            return null;
        }
        String uri = location.uri();
        int slash = uri.lastIndexOf('/');
        String name = slash >= 0 ? uri.substring(slash + 1) : uri;
        int query = name.indexOf('?');
        return query >= 0 ? name.substring(0, query) : name;
    }

    private static Range identifierRangeAt(String text, int line, int col) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        if (line < 0 || line >= lines.length) {
            return null;
        }
        String lineText = lines[line];
        int start = Math.max(0, Math.min(col, lineText.length()));
        int end = start;
        while (start > 0 && Character.isJavaIdentifierPart(lineText.charAt(start - 1))) {
            start--;
        }
        while (end < lineText.length() && Character.isJavaIdentifierPart(lineText.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        return Range.of(line, start, line, end);
    }

    private static String textOfRange(String text, Range range) {
        if (text == null || range == null || range.start().line() != range.end().line()) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        int line = range.start().line();
        if (line < 0 || line >= lines.length) {
            return null;
        }
        String lineText = lines[line];
        int start = Math.max(0, Math.min(range.start().col(), lineText.length()));
        int end = Math.max(start, Math.min(range.end().col(), lineText.length()));
        return lineText.substring(start, end);
    }

    JavaLanguageServer awaitServerForRename(Path filePath) {
        if (!isServerStarting(filePath)) {
            return null;
        }
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null) {
            return null;
        }
        renameWaitCanceled.set(false);
        String waiting = text("status.renameWaiting",
                "Java: aguardando a indexacao para renomear com seguranca");
        host.showProgress(RENAME_PROGRESS_ID, waiting, true, () -> renameWaitCanceled.set(true));
        host.updateProgress(RENAME_PROGRESS_ID, waiting, -1, true, () -> renameWaitCanceled.set(true));
        try {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(RENAME_WAIT_BUDGET_MS);
            while (System.nanoTime() < deadline && !renameWaitCanceled.get()) {
                if (lsp.awaitReady(250)) {
                    return host.runningServerFor(filePath);
                }
                if (lsp.getState() == LanguageServerState.ERROR
                        || lsp.getState() == LanguageServerState.STOPPED) {
                    break;
                }
            }
        } finally {
            host.hideProgress(RENAME_PROGRESS_ID);
        }
        host.setStatusBarText(text("status.renameDuringIndexing",
                "Java: renomear com seguranca exige a indexacao concluida"));
        return null;
    }
}
