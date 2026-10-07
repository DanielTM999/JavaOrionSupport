package dtm.ide.adapter;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.editor.AutoCompleteIdleTrigger;
import dtm.ide.editor.BuildFileCompletionProvider;
import dtm.ide.editor.CallParentheses;
import dtm.ide.editor.CompletionRanking;
import dtm.ide.editor.JavaFastCompletionProvider;
import dtm.ide.editor.JavaTypingContext;
import dtm.ide.editor.PostfixCompletionProvider;
import dtm.ide.lsp.api.CompletionTrigger;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.spring.SpringAnnotationCompletionProvider;
import dtm.ide.spring.config.SpringConfigSupport;
import dtm.ide.spring.jpa.JpaQueryCompletionProvider;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dtm.ide.adapter.CompletionSupport.completionTriggerCharacter;
import static dtm.ide.adapter.CompletionSupport.filterCompletionSuggestions;
import static dtm.ide.adapter.CompletionSupport.markUnusedMethods;
import static dtm.ide.adapter.CompletionSupport.mergeCompletionSuggestions;

@Slf4j
public final class CompletionEngine {

    public static final long AUTO_COMPLETE_IDLE_DELAY_MS = 150;
    private static final Set<Character> JAVA_COMPLETION_TRIGGER_CHARACTERS = Set.of('.', '@', '(', ':', '$');
    private static final Set<Character> BUILD_FILE_TRIGGER_CHARACTERS = Set.of('.', ':', '$', '<', '/', '\'', '"');

    private final AdapterHost host;

    public CompletionEngine(AdapterHost host) {
        this.host = host;
    }

    public Set<Character> getCompletionTriggerCharacters() {
        return JAVA_COMPLETION_TRIGGER_CHARACTERS;
    }

    public Set<Character> getCompletionTriggerCharacters(Path filePath) {
        return BuildFileCompletionProvider.handles(filePath) && host.settings().isBuildFileCompletion()
                ? BUILD_FILE_TRIGGER_CHARACTERS
                : JAVA_COMPLETION_TRIGGER_CHARACTERS;
    }

    public boolean shouldAutoTriggerCompletion(IdeCompletionContext context) {
        if (host.debugActive() || context == null) {
            return false;
        }
        if (BuildFileCompletionProvider.handles(context.filePath())) {
            return host.settings().isBuildFileCompletion();
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            return host.settings().isSpringSupport();
        }
        if (!JavaProjectConventions.isJava(context.filePath())) {
            return false;
        }
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return false;
        }
        char previous = line.charAt(col - 1);
        if (previous == '"') {
            return isJpaQueryLiteral(context) || isSpringAnnotationLiteral(line, col);
        }
        return JavaTypingContext.allowsTriggerCharacter(context.text(), context.caretOffset(), previous);
    }

    public boolean isIdleCompletionEligible(Path filePath) {
        return !host.debugActive() && JavaProjectConventions.isJava(filePath);
    }

    public boolean isIdleCompletionReady() {
        return !host.debugActive() && !isAutoCompletePopupVisible();
    }

    boolean isAutoCompletePopupVisible() {
        IdeEditorContext editor = host.activeJavaEditor();
        return editor != null && editor.isAutoCompleteVisible();
    }

    public AutoCompleteIdleTrigger.Caret currentIdleCaret() {
        IdeEditorContext editor = host.activeJavaEditor();
        if (editor == null) {
            return null;
        }
        return new AutoCompleteIdleTrigger.Caret(
                JavaProjectConventions.normalize(editor.filePath()), editor.getCaretOffset());
    }

    public void onLateCompletion(Path filePath, int line, int col) {
        SwingUtilities.invokeLater(() -> {
            IdeEditorContext editor = host.activeJavaEditor();
            if (editor == null || host.debugActive() || !Objects.equals(
                    JavaProjectConventions.normalize(editor.filePath()),
                    JavaProjectConventions.normalize(filePath))) {
                return;
            }
            if (editor.getCaretLine() == line && editor.getCaretCol() == col) {
                host.requestCodeEditorAutocomplete();
            }
        });
    }

    public void fireIdleCompletion() {
        SwingUtilities.invokeLater(() -> {
            IdeEditorContext editor = host.activeJavaEditor();
            if (editor == null || editor.isAutoCompleteVisible()
                    || !JavaTypingContext.allowsIdleCompletion(editor.getText(), editor.getCaretOffset())) {
                return;
            }
            log.debug("Autocomplete: disparo automatico apos {} ms de pausa", AUTO_COMPLETE_IDLE_DELAY_MS);
            host.requestCodeEditorAutocomplete();
        });
    }

    public boolean isAutoCompletionOnTypingEnabled() {
        return !host.debugActive();
    }

    public List<AutoCompleteItem> getCompletionSuggestions(IdeCompletionContext context) {
        if (host.debugActive() || context == null) {
            return null;
        }
        if (SpringConfigSupport.isConfigFile(context.filePath())) {
            List<AutoCompleteItem> values = SpringConfigSupport.completeValues(host.spring().configIndex(),
                    context.filePath(), context.text(), context.caretLine(), context.caretCol());
            if (!values.isEmpty()) {
                return values;
            }
            return SpringConfigSupport.complete(host.spring().metadata(), context.filePath(),
                    context.text(), context.caretLine(), context.caretCol());
        }
        List<AutoCompleteItem> query = jpaQueryCompletion(context);
        if (query != null) {
            return query;
        }
        List<AutoCompleteItem> springAnnotation = springAnnotationCompletion(context);
        if (springAnnotation != null) {
            return springAnnotation;
        }
        if (BuildFileCompletionProvider.handles(context.filePath())) {
            return host.settings().isBuildFileCompletion()
                    ? host.buildFileCompletion().suggestions(context)
                    : null;
        }
        if (!JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        List<AutoCompleteItem> snippetsLocal = javaSnippets(context);

        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null && lsp.isInteractive() && lsp.isReady()) {
            long started = System.nanoTime();
            List<AutoCompleteItem> semantic = reusableJavaCompletions(lsp, context);
            boolean reused = !semantic.isEmpty();
            if (!reused) {
                CompletionTrigger trigger = javaCompletionTrigger(context);
                semantic = lsp.complete(context.filePath(), context.text(),
                        context.caretLine(), context.caretCol(), trigger, javaTriggerCharacter(context),
                        lsp.documentVersion(context.filePath()), true);
            }
            return finishSemanticCompletion(lsp, context, semantic, snippetsLocal, started,
                    reused ? "cache" : "jdtls");
        }

        List<AutoCompleteItem> lexical = host.fastCompletion().suggestions(context);
        List<AutoCompleteItem> local = mergeCompletionSuggestions(lexical, snippetsLocal);
        List<AutoCompleteItem> contextual = List.of();
        if (lsp != null && lsp.isInteractive()) {
            contextual = filterCompletionSuggestions(lsp.cachedCompletions(context.filePath(),
                    context.text(), context.caretLine(), context.caretCol()), context.prefix());
            lsp.warmCompletion(context.filePath(), context.text(),
                    context.caretLine(), context.caretCol());
        }
        return javaCompletion(mergeCompletionSuggestions(contextual, local), context);
    }

    public CompletableFuture<List<AutoCompleteItem>> getCompletionSuggestionsAsync(
            IdeCompletionContext context) {
        JavaLanguageServer lsp = host.languageServer();
        if (host.debugActive() || context == null || !JavaProjectConventions.isJava(context.filePath())
                || lsp == null || !lsp.isInteractive() || !lsp.isReady()) {
            return CompletableFuture.completedFuture(getCompletionSuggestions(context));
        }
        List<AutoCompleteItem> query = jpaQueryCompletion(context);
        if (query != null) {
            return CompletableFuture.completedFuture(query);
        }
        List<AutoCompleteItem> springAnnotation = springAnnotationCompletion(context);
        if (springAnnotation != null) {
            return CompletableFuture.completedFuture(springAnnotation);
        }
        long started = System.nanoTime();
        List<AutoCompleteItem> snippetsLocal = javaSnippets(context);
        List<AutoCompleteItem> reusable = reusableJavaCompletions(lsp, context);
        if (!reusable.isEmpty()) {
            return CompletableFuture.completedFuture(
                    finishSemanticCompletion(lsp, context, reusable, snippetsLocal, started, "cache"));
        }
        return lsp.completeAsync(context.filePath(), context.text(), context.caretLine(),
                        context.caretCol(), javaCompletionTrigger(context), javaTriggerCharacter(context),
                        lsp.documentVersion(context.filePath()))
                .thenApply(semantic -> finishSemanticCompletion(lsp, context, semantic, snippetsLocal,
                        started, "jdtls"));
    }

    public CompletableFuture<AutoCompleteItem> resolveCompletionItem(AutoCompleteItem item) {
        JavaLanguageServer lsp = host.languageServer();
        return lsp == null || !lsp.isInteractive() ? CompletableFuture.completedFuture(item)
                : lsp.resolveCompletionAsync(item);
    }

    List<AutoCompleteItem> javaSnippets(IdeCompletionContext context) {
        if (JavaFastCompletionProvider.isMemberAccess(context)) {
            return PostfixCompletionProvider.suggestions(context.text(), context.caretOffset());
        }
        JavaProjectDescriptor current = host.descriptor();
        return host.snippets().suggestions(context.prefix(), current != null && current.spring());
    }

    static List<AutoCompleteItem> reusableJavaCompletions(JavaLanguageServer lsp,
                                                                  IdeCompletionContext context) {
        return filterCompletionSuggestions(lsp.reusableCompletions(context.filePath(), context.text(),
                context.caretLine(), context.caretCol()), context.prefix());
    }

    static Character javaTriggerCharacter(IdeCompletionContext context) {
        return completionTriggerCharacter(context.currentLine(), context.caretCol(),
                JAVA_COMPLETION_TRIGGER_CHARACTERS);
    }

    static CompletionTrigger javaCompletionTrigger(IdeCompletionContext context) {
        return context.triggerKind() == IdeCompletionTriggerKind.TYPING && javaTriggerCharacter(context) != null
                ? CompletionTrigger.TRIGGER_CHARACTER
                : CompletionTrigger.INVOKED;
    }

    List<AutoCompleteItem> finishSemanticCompletion(JavaLanguageServer lsp, IdeCompletionContext context,
                                                            List<AutoCompleteItem> semantic,
                                                            List<AutoCompleteItem> snippetsLocal,
                                                            long started, String origin) {
        List<AutoCompleteItem> items = semantic == null ? List.of() : semantic;
        if (items.isEmpty()) {
            items = filterCompletionSuggestions(lsp.cachedCompletions(context.filePath(),
                    context.text(), context.caretLine(), context.caretCol()), context.prefix());
        }
        if (items.isEmpty()) {
            items = host.fastCompletion().suggestions(context);
        }
        long fetched = System.nanoTime();
        List<AutoCompleteItem> result = javaCompletion(
                mergeCompletionSuggestions(items, snippetsLocal), context);
        log.debug("Autocomplete: {} item(ns) em {} ms (origem {}, pos-processamento {} ms)",
                result.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), origin,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fetched));
        return result;
    }

    List<AutoCompleteItem> javaCompletion(List<AutoCompleteItem> items,
                                                  IdeCompletionContext context) {
        List<AutoCompleteItem> ranked = CallParentheses.apply(CompletionRanking.rank(items, context.prefix()),
                context.text(), context.prefixOffset(), context.caretOffset());
        return markUnusedMethods(ranked, names -> host.lexicalIndex().unusedMethods(names,
                context.filePath(), context.text()));
    }

    boolean isSpringAnnotationLiteral(String line, int col) {
        return host.isSpringNavigationEnabled()
                && SpringAnnotationCompletionProvider.opensAnnotationLiteral(line, col);
    }

    boolean isJpaQueryLiteral(IdeCompletionContext context) {
        return isJpaCompletionEnabled(context)
                && JpaQueryCompletionProvider.isInsideQuery(context.text(), context.caretOffset());
    }

    List<AutoCompleteItem> jpaQueryCompletion(IdeCompletionContext context) {
        if (!isJpaCompletionEnabled(context)) {
            return null;
        }
        return JpaQueryCompletionProvider.suggestions(host.spring().index().snapshot(), context.filePath(),
                context.text(), context.caretOffset());
    }

    boolean isJpaCompletionEnabled(IdeCompletionContext context) {
        JavaProjectDescriptor current = host.descriptor();
        return context != null && JavaProjectConventions.isJava(context.filePath())
                && current != null && current.spring() && host.settings().isSpringSupport()
                && host.settings().isSpringJpa();
    }

    List<AutoCompleteItem> springAnnotationCompletion(IdeCompletionContext context) {
        if (!host.isSpringNavigationEnabled()
                || !JavaProjectConventions.isJava(context.filePath())) {
            return null;
        }
        return SpringAnnotationCompletionProvider.suggestions(host.spring().index().snapshot(),
                context.filePath(), context.currentLine(), context.caretCol(),
                context.caretLine());
    }
}
