package dtm.ide.adapter;

import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.editor.JavaImportInserter;
import dtm.ide.lsp.api.ImportCandidateSupport;
import dtm.ide.lsp.api.ImportLookup;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.ResolvedCodeAction;
import dtm.ide.lsp.api.SourceGenerationSupport;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.ui.ImportChoicePanel;
import dtm.ide.ui.JavaSourceActionDialogs;
import dtm.stools.component.panels.editor.code.api.Command;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

public final class SourceActionSupport {

    private final AdapterHost host;
    private final Map<Path, PendingPasteImport> pendingPasteImports = new ConcurrentHashMap<>();
    private final Set<Path> pasteImportsResolving = ConcurrentHashMap.newKeySet();

    public SourceActionSupport(AdapterHost host) {
        this.host = host;
    }

    private static final long PASTE_IMPORT_WINDOW_MS = 20_000;
    private static final long PASTE_IMPORT_RETRY_MS = 1_000;
    private static final long PASTE_IMPORT_FOLLOW_UP_MS = 5_000;
    private static final int PASTE_IMPORT_MAX_ROUNDS = 3;
    private static final Pattern TYPE_LIKE_NAME = Pattern.compile("\\b\\p{Lu}");

    private record PendingPasteImport(Path file, int offset, String pasted, Range range, long deadline,
                                      int round, Set<String> handled) {
    }

    public void onPasted(IdeEditorContext context) {
        Path file = context.filePath();
        if (host.languageServer() == null || !JavaProjectConventions.isJava(file) || context.isReadOnly()) {
            return;
        }
        String pasted = clipboardText();
        if (pasted == null || pasted.isBlank() || !TYPE_LIKE_NAME.matcher(pasted).find()) {
            return;
        }
        String text = context.getText();
        int offset = context.getCaretOffset() - pasted.length();
        if (text == null || offset < 0 || !text.startsWith(pasted, offset)) {
            return;
        }
        pendingPasteImports.put(JavaProjectConventions.normalize(file), new PendingPasteImport(
                file, offset, pasted, rangeOf(text, offset, offset + pasted.length()),
                System.currentTimeMillis() + PASTE_IMPORT_WINDOW_MS, 1, Set.of()));
    }

    private static String clipboardText() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data instanceof String value ? value.replace("\r\n", "\n").replace("\r", "\n") : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static Range rangeOf(String text, int start, int end) {
        return Range.of(positionOf(text, start), positionOf(text, end));
    }

    private static Position positionOf(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return Position.of(line, offset - lineStart);
    }

    public void resolvePastedImports(Path path) {
        Path key = JavaProjectConventions.normalize(path);
        PendingPasteImport pending = pendingPasteImports.get(key);
        if (pending == null) {
            return;
        }
        if (System.currentTimeMillis() > pending.deadline()) {
            pendingPasteImports.remove(key, pending);
            return;
        }
        JavaLanguageServer lsp = host.interactiveServerFor(pending.file());
        ImportCandidateSupport imports = lsp == null ? null : lsp.extension(ImportCandidateSupport.class);
        if (imports == null || !pasteImportsResolving.add(key)) {
            return;
        }
        host.background().execute(() -> {
            boolean retry = false;
            try {
                String text = lsp.documentContent(pending.file());
                if (text == null || !text.startsWith(pending.pasted(), pending.offset())) {
                    return;
                }
                ImportLookup lookup = imports.importCandidates(pending.file(), text, pending.range(),
                        pending.handled());
                if (!lookup.diagnosed()) {
                    retry = true;
                    return;
                }
                if (pendingPasteImports.remove(key, pending)) {
                    Set<String> handled = new HashSet<>(pending.handled());
                    handled.addAll(lookup.queried());
                    PendingPasteImport resolved = new PendingPasteImport(pending.file(), pending.offset(),
                            pending.pasted(), pending.range(), pending.deadline(), pending.round(),
                            Set.copyOf(handled));
                    SwingUtilities.invokeLater(() -> chooseImports(resolved, lookup.candidates()));
                }
            } finally {
                pasteImportsResolving.remove(key);
                if (retry && pendingPasteImports.get(key) == pending) {
                    host.background().schedule(() -> resolvePastedImports(path), PASTE_IMPORT_RETRY_MS,
                            TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    void chooseImports(PendingPasteImport pending, Map<String, List<String>> candidates) {
        List<String> chosen = new ArrayList<>();
        Deque<Map.Entry<String, List<String>>> ambiguous = new ArrayDeque<>();
        candidates.forEach((name, options) -> {
            if (options.size() == 1) {
                chosen.add(options.getFirst());
            } else if (options.size() > 1) {
                ambiguous.add(Map.entry(name, options));
            }
        });
        askNextImport(pending, chosen, ambiguous);
    }

    void askNextImport(PendingPasteImport pending, List<String> chosen,
                               Deque<Map.Entry<String, List<String>>> ambiguous) {
        Map.Entry<String, List<String>> next = ambiguous.poll();
        if (next == null) {
            applyPastedImports(pending, chosen);
            return;
        }
        ImportChoicePanel panel = new ImportChoicePanel(next.getKey(), next.getValue(), choice -> {
            if (choice != null) {
                chosen.add(choice);
            }
            SwingUtilities.invokeLater(() -> askNextImport(pending, chosen, ambiguous));
        });
        host.showPopup(PlatformPopupBuilder.builder()
                .component(panel)
                .title(text("pasteImports.title", "Importar classe"))
                .size(460, Math.min(380, 120 + next.getValue().size() * 44))
                .modalityType(java.awt.Dialog.ModalityType.MODELESS)
                .onLoad(component -> panel.focusList())
                .onClose(component -> panel.closed())
                .build());
    }

    void applyPastedImports(PendingPasteImport pending, List<String> imports) {
        Path file = pending.file();
        IdeEditorContext editor = host.getEditor(file);
        if (imports.isEmpty() || editor == null || editor.isReadOnly()) {
            return;
        }
        String before = editor.getText();
        JavaImportInserter.Result result = JavaImportInserter.insert(before, imports);
        if (result.insertedLines() == 0) {
            return;
        }
        int line = editor.getCaretLine();
        int col = editor.getCaretCol();
        if (!editor.applyEdits(result.edits())) {
            return;
        }
        int shiftedLines = result.edits().stream()
                .filter(edit -> edit.range().start().line() < line
                        || (edit.range().start().line() == line && edit.range().start().col() <= col))
                .mapToInt(edit -> (int) edit.newText().chars().filter(ch -> ch == '\n').count())
                .sum();
        int expectedLine = line + shiftedLines;
        if (editor.getCaretLine() != expectedLine || editor.getCaretCol() != col) {
            editor.setCaretPosition(expectedLine, col);
        }
        String after = editor.getText();
        followUpPastedImports(pending, before, after);
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            lsp.changeDocument(file, after);
        }
        editor.refreshDiagnostics();
        host.setStatusBarText(text("status.pasteImports", "Java: imports adicionados") + " - "
                + imports.stream().map(name -> name.substring(name.lastIndexOf('.') + 1))
                .collect(Collectors.joining(", ")));
    }

    void followUpPastedImports(PendingPasteImport pending, String before, String after) {
        if (pending.round() >= PASTE_IMPORT_MAX_ROUNDS || before == null || after == null
                || !before.startsWith(pending.pasted(), pending.offset())) {
            return;
        }
        int offset = pending.offset() + after.length() - before.length();
        if (offset < 0 || !after.startsWith(pending.pasted(), offset)) {
            return;
        }
        pendingPasteImports.putIfAbsent(JavaProjectConventions.normalize(pending.file()), new PendingPasteImport(
                pending.file(), offset, pending.pasted(),
                rangeOf(after, offset, offset + pending.pasted().length()),
                System.currentTimeMillis() + PASTE_IMPORT_FOLLOW_UP_MS, pending.round() + 1, pending.handled()));
    }

    public void handleCodeActionCommand(Command command) {
        if (command == null || command.arguments() == null || command.arguments().isEmpty()) {
            return;
        }
        if (DiagnosticsEngine.DISABLE_INSPECTION_COMMAND.equals(command.id())) {
            host.diagnostics().disableInspection(String.valueOf(command.arguments().getFirst()));
            return;
        }
        if (DiagnosticsEngine.HIDE_OCCURRENCE_COMMAND.equals(command.id())) {
            List<Object> arguments = command.arguments();
            if (arguments.size() >= 3) {
                host.diagnostics().hideInspectionOccurrence(String.valueOf(arguments.get(0)),
                        String.valueOf(arguments.get(1)), String.valueOf(arguments.get(2)));
            }
            return;
        }
        if (!JavaLanguageServer.APPLY_CODE_ACTION_COMMAND.equals(command.id())) {
            return;
        }
        String rawAction = String.valueOf(command.arguments().getFirst());
        String sourcePrompt = sourcePromptId(rawAction);
        if (sourcePrompt != null) {
            runSourceAction(sourcePrompt, host.activeJavaEditor());
            return;
        }
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null) {
            host.background().submit(() -> applyResolvedCodeAction(lsp, rawAction));
        }
    }

    void applyResolvedCodeAction(JavaLanguageServer lsp, String rawAction) {
        ResolvedCodeAction resolved = lsp.resolveCodeAction(rawAction);
        if (resolved == null) {
            host.setStatusBarText(text("status.codeActionFailed",
                    "Java: nao foi possivel aplicar a correcao"));
            return;
        }
        if (!resolved.edit().isEmpty()
                && UiThreads.onUi(() -> applyWorkspaceEdit(lsp, resolved.edit())) == null) {
            return;
        }
        if (resolved.commandJson() != null) {
            lsp.executeCodeAction(resolved.commandJson());
        }
    }

    public Boolean applyWorkspaceEdit(JavaLanguageServer lsp, IdeWorkspaceEdit edit) {
        boolean skipped = false;
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (!(operation instanceof IdeWorkspaceEdit.TextEdits textEdits)) {
                skipped = true;
                continue;
            }
            IdeEditorContext editor = host.getEditor(textEdits.file(), true);
            if (editor == null || editor.isReadOnly() || !editor.applyEdits(textEdits.edits())) {
                skipped = true;
                continue;
            }
            lsp.changeDocument(textEdits.file(), editor.getText());
            editor.refreshDiagnostics();
        }
        if (skipped) {
            host.setStatusBarText(text("status.codeActionPartial",
                    "Java: a correcao nao pode ser aplicada por completo"));
        }
        return !skipped;
    }

    private static String sourcePromptId(String rawAction) {
        if (rawAction == null) return null;
        for (String id : List.of(SourceGenerationSupport.OVERRIDE_METHODS_PROMPT,
                SourceGenerationSupport.HASHCODE_EQUALS_PROMPT,
                SourceGenerationSupport.GENERATE_TOSTRING_PROMPT,
                SourceGenerationSupport.GENERATE_ACCESSORS_PROMPT,
                SourceGenerationSupport.GENERATE_CONSTRUCTORS_PROMPT,
                SourceGenerationSupport.GENERATE_DELEGATE_METHODS_PROMPT)) {
            if (rawAction.contains(id)) return id;
        }
        return null;
    }

    public void showGenerateActions(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        List<JavaSourceActionDialogs.Choice<String>> choices = List.of(
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_CONSTRUCTORS_PROMPT,
                        text("generate.constructor", "Constructor..."), "",
                        JavaSourceActionDialogs.Kind.CONSTRUCTOR),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_ACCESSORS_PROMPT,
                        text("generate.accessors", "Getter and Setter..."), "",
                        JavaSourceActionDialogs.Kind.ACCESSOR),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.HASHCODE_EQUALS_PROMPT,
                        "equals() and hashCode()...", "", JavaSourceActionDialogs.Kind.EQUALS_HASH),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_TOSTRING_PROMPT,
                        "toString()...", "", JavaSourceActionDialogs.Kind.TO_STRING),
                new JavaSourceActionDialogs.Choice<>("override",
                        text("generate.override", "Override Methods..."), "",
                        JavaSourceActionDialogs.Kind.OVERRIDE, "Ctrl+Insert"),
                new JavaSourceActionDialogs.Choice<>("implement",
                        text("generate.implement", "Implement Methods..."), "",
                        JavaSourceActionDialogs.Kind.IMPLEMENT, "Ctrl+I"),
                new JavaSourceActionDialogs.Choice<>(SourceGenerationSupport.GENERATE_DELEGATE_METHODS_PROMPT,
                        text("generate.delegate", "Delegate Methods..."), "",
                        JavaSourceActionDialogs.Kind.DELEGATE)
        );
        String selected = JavaSourceActionDialogs.chooseOne(host.createModernComponentDialogBuilder(),
                text("generate.title", "Generate"),
                text("generate.choose", "Escolha o codigo que deseja gerar"), choices);
        if (selected == null) return;
        if ("override".equals(selected)) showOverrideMethods(context, false);
        else if ("implement".equals(selected)) showOverrideMethods(context, true);
        else runSourceAction(selected, context);
    }

    void runSourceAction(String command, IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        switch (command) {
            case SourceGenerationSupport.OVERRIDE_METHODS_PROMPT -> showOverrideMethods(context, false);
            case SourceGenerationSupport.GENERATE_CONSTRUCTORS_PROMPT -> showConstructors(context);
            case SourceGenerationSupport.GENERATE_ACCESSORS_PROMPT -> showAccessors(context);
            case SourceGenerationSupport.HASHCODE_EQUALS_PROMPT -> showHashCodeEquals(context);
            case SourceGenerationSupport.GENERATE_TOSTRING_PROMPT -> showToString(context);
            case SourceGenerationSupport.GENERATE_DELEGATE_METHODS_PROMPT -> showDelegateMethods(context);
            default -> { }
        }
    }

    SourceGenerationSupport sourceGenerationFor(Path file) {
        JavaLanguageServer lsp = host.interactiveServerFor(file);
        return lsp == null ? null : lsp.extension(SourceGenerationSupport.class);
    }

    public void showOverrideMethods(IdeEditorContext context, boolean implementOnly) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        host.background().submit(() -> {
            SourceGenerationSupport.OverrideStatus status = generator.overridableMethods(
                    context.filePath(), source, line, col);
            List<SourceGenerationSupport.SourceItem> methods = status.methods().stream()
                    .filter(item -> item.selected() == implementOnly).toList();
            SwingUtilities.invokeLater(() -> {
                if (methods.isEmpty()) {
                    sourceActionUnavailable(implementOnly ? "Implement Methods" : "Override Methods");
                    return;
                }
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        host.createModernComponentDialogBuilder(),
                        implementOnly ? text("generate.implement", "Implement Methods")
                                : text("generate.override", "Override Methods"),
                        status.type(), sourceChoices(methods, implementOnly
                                ? JavaSourceActionDialogs.Kind.IMPLEMENT
                                : JavaSourceActionDialogs.Kind.OVERRIDE), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateOverridableMethods(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    void showConstructors(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        host.background().submit(() -> {
            SourceGenerationSupport.ConstructorsStatus status = generator.constructorsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.constructors().isEmpty()) {
                    sourceActionUnavailable(text("generate.constructor", "Constructor"));
                    return;
                }
                List<SourceGenerationSupport.SourceItem> constructors = JavaSourceActionDialogs.chooseMany(
                        host.createModernComponentDialogBuilder(), text("generate.constructor", "Constructor"),
                        text("generate.chooseConstructors", "Selecione os construtores da superclasse"),
                        sourceChoices(status.constructors(), JavaSourceActionDialogs.Kind.CONSTRUCTOR), item -> true);
                if (constructors == null || constructors.isEmpty()) return;
                List<SourceGenerationSupport.SourceItem> fields = status.fields().isEmpty() ? List.of()
                        : JavaSourceActionDialogs.chooseMany(host.createModernComponentDialogBuilder(),
                        text("generate.constructor", "Constructor"),
                        text("generate.chooseFields", "Selecione os campos que serao inicializados"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD),
                        SourceGenerationSupport.SourceItem::selected);
                if (fields == null) return;
                submitGeneration(context, source, () -> generator.generateConstructors(
                        context.filePath(), source, line, col, constructors, fields));
            });
        });
    }

    void showAccessors(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        host.background().submit(() -> {
            List<SourceGenerationSupport.SourceItem> available = generator.accessorsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (available.isEmpty()) {
                    sourceActionUnavailable(text("generate.accessors", "Getter and Setter"));
                    return;
                }
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        host.createModernComponentDialogBuilder(), text("generate.accessors", "Getter and Setter"),
                        text("generate.chooseAccessors", "Selecione os campos"),
                        sourceChoices(available, JavaSourceActionDialogs.Kind.ACCESSOR), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateAccessors(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    void showHashCodeEquals(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        host.background().submit(() -> {
            SourceGenerationSupport.FieldsStatus status = generator.hashCodeEqualsStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.fields().isEmpty()) {
                    sourceActionUnavailable("equals() and hashCode()");
                    return;
                }
                if (status.exists() && !JavaSourceActionDialogs.confirm(host.createModernComponentDialogBuilder(Boolean.class),
                        "equals() and hashCode()", text("generate.regenerate",
                                "Os metodos ja existem. Deseja gerar novamente?"),
                        text("generate.regenerateAction", "Gerar novamente"))) return;
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        host.createModernComponentDialogBuilder(), "equals() and hashCode()",
                        text("generate.chooseFields", "Selecione os campos"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateHashCodeEquals(
                        context.filePath(), source, line, col, selected, status.exists()));
            });
        });
    }

    void showToString(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        host.background().submit(() -> {
            SourceGenerationSupport.FieldsStatus status = generator.toStringStatus(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (status.exists() && !JavaSourceActionDialogs.confirm(host.createModernComponentDialogBuilder(Boolean.class),
                        "toString()", text("generate.replaceToString",
                                "toString() ja existe. Deseja substituir a implementacao?"),
                        text("generate.replace", "Substituir"))) return;
                List<SourceGenerationSupport.SourceItem> selected = status.fields().isEmpty() ? List.of()
                        : JavaSourceActionDialogs.chooseMany(host.createModernComponentDialogBuilder(), "toString()",
                        text("generate.chooseFields", "Selecione os campos"),
                        sourceChoices(status.fields(), JavaSourceActionDialogs.Kind.FIELD),
                        SourceGenerationSupport.SourceItem::selected);
                if (selected == null) return;
                submitGeneration(context, source, () -> generator.generateToString(
                        context.filePath(), source, line, col, selected));
            });
        });
    }

    void showDelegateMethods(IdeEditorContext context) {
        if (context == null || !JavaProjectConventions.isJava(context.filePath())) return;
        SourceGenerationSupport generator = sourceGenerationFor(context.filePath());
        if (generator == null) return;
        String source = context.getText();
        int line = context.getCaretLine(), col = context.getCaretCol();
        host.background().submit(() -> {
            List<SourceGenerationSupport.DelegateTarget> targets = generator.delegateTargets(
                    context.filePath(), source, line, col);
            SwingUtilities.invokeLater(() -> {
                if (targets.isEmpty()) {
                    sourceActionUnavailable(text("generate.delegate", "Delegate Methods"));
                    return;
                }
                List<JavaSourceActionDialogs.Choice<SourceGenerationSupport.DelegateTarget>> choices = targets.stream()
                        .map(target -> new JavaSourceActionDialogs.Choice<>(target, target.label(), "",
                                JavaSourceActionDialogs.Kind.FIELD))
                        .toList();
                SourceGenerationSupport.DelegateTarget target = JavaSourceActionDialogs.chooseOne(
                        host.createModernComponentDialogBuilder(), text("generate.delegate", "Delegate Methods"),
                        text("generate.chooseDelegateTarget", "Selecione o campo delegado"), choices);
                if (target == null) return;
                List<SourceGenerationSupport.SourceItem> selected = JavaSourceActionDialogs.chooseMany(
                        host.createModernComponentDialogBuilder(), text("generate.delegate", "Delegate Methods"),
                        text("generate.chooseDelegateMethods", "Selecione os metodos delegados"),
                        sourceChoices(target.methods(), JavaSourceActionDialogs.Kind.DELEGATE), item -> true);
                if (selected == null || selected.isEmpty()) return;
                submitGeneration(context, source, () -> generator.generateDelegateMethods(
                        context.filePath(), source, line, col, target, selected));
            });
        });
    }

    private static List<JavaSourceActionDialogs.Choice<SourceGenerationSupport.SourceItem>> sourceChoices(
            List<SourceGenerationSupport.SourceItem> items, JavaSourceActionDialogs.Kind kind) {
        return items.stream().map(item -> new JavaSourceActionDialogs.Choice<>(
                item, item.label(), item.detail(), kind)).toList();
    }

    void submitGeneration(IdeEditorContext context, String source,
                                  java.util.function.Supplier<List<TextEdit>> generation) {
        host.background().submit(() -> {
            List<TextEdit> edits = generation.get();
            SwingUtilities.invokeLater(() -> applyGeneratedEdits(context, source, edits));
        });
    }

    void applyGeneratedEdits(IdeEditorContext context, String source, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            sourceActionUnavailable(text("generate.title", "Generate"));
            return;
        }
        if (!Objects.equals(source, context.getText())) {
            host.setStatusBarText(text("status.generate.changed",
                    "Java: o arquivo mudou durante a geracao; tente novamente"));
            return;
        }
        JavaLanguageServer lsp = host.languageServer();
        if (lsp == null) return;
        int line = context.getCaretLine(), col = context.getCaretCol();
        String generated = lsp.applyTextEdits(source, edits);
        context.setText(generated);
        context.setCaretPosition(line, col);
        lsp.changeDocument(context.filePath(), generated);
        context.refreshDiagnostics();
        context.refreshCodeLenses();
        host.setStatusBarText(text("status.generate.done", "Java: codigo gerado"));
    }

    void sourceActionUnavailable(String action) {
        host.setStatusBarText(text("status.generate.unavailable", "Java: acao indisponivel")
                + " - " + action);
    }
}
