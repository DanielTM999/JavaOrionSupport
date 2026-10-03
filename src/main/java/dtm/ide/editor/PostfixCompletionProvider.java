package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * IntelliJ-style postfix templates: {@code list.for}, {@code value.nn}, {@code expr.sout}...
 * The template replaces the word after the dot, and an additional edit removes {@code expr.}.
 */
public final class PostfixCompletionProvider {

    private static final String DETAIL = "postfix";

    private record Template(String key, String body, String description) {
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template("var", "var ${1:value} = EXPR;$0", "var value = expr;"),
            new Template("if", "if (EXPR) {\n    $0\n}", "if (expr) { }"),
            new Template("nn", "if (EXPR != null) {\n    $0\n}", "if (expr != null) { }"),
            new Template("null", "if (EXPR == null) {\n    $0\n}", "if (expr == null) { }"),
            new Template("not", "!EXPR$0", "!expr"),
            new Template("for", "for (var ${1:item} : EXPR) {\n    $0\n}", "for (var item : expr) { }"),
            new Template("fori", "for (int ${1:i} = 0; ${1:i} < EXPR; ${1:i}++) {\n    $0\n}",
                    "for (int i = 0; i < expr; i++) { }"),
            new Template("sout", "System.out.println(EXPR);$0", "System.out.println(expr);"),
            new Template("return", "return EXPR;$0", "return expr;"),
            new Template("par", "(EXPR)$0", "(expr)"),
            new Template("try", "try {\n    EXPR;$0\n} catch (Exception ${1:e}) {\n    \n}",
                    "try { expr; } catch (Exception e) { }"));

    private PostfixCompletionProvider() {
    }

    /** Postfix items for the word being typed after {@code expr.}, or an empty list. */
    public static List<AutoCompleteItem> suggestions(String text, int caretOffset) {
        if (text == null || caretOffset <= 0 || caretOffset > text.length()) {
            return List.of();
        }
        int wordStart = caretOffset;
        while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        int dot = wordStart - 1;
        if (dot <= 0 || text.charAt(dot) != '.') {
            return List.of();
        }
        int expressionStart = expressionStart(text, dot);
        if (expressionStart < 0) {
            return List.of();
        }
        String expression = text.substring(expressionStart, dot);
        String typed = text.substring(wordStart, caretOffset).toLowerCase(Locale.ROOT);
        String escaped = expression.replace("\\", "\\\\").replace("$", "\\$");
        TextEdit removeExpression = TextEdit.delete(new Range(
                TextOffsets.position(text, expressionStart), TextOffsets.position(text, dot + 1)));
        List<AutoCompleteItem> items = new ArrayList<>();
        for (Template template : TEMPLATES) {
            if (!template.key().startsWith(typed)) {
                continue;
            }
            items.add(new AutoCompleteItem(template.body().replace("EXPR", escaped), template.key(), DETAIL,
                    template.description().replace("expr", expression), null, AutoCompleteItem.Kind.SNIPPET,
                    List.of(removeExpression)));
        }
        return List.copyOf(items);
    }

    /**
     * Start of the expression that ends right before {@code dot}: identifiers, member access,
     * calls, array access and string literals on the same line. Returns -1 when there is none.
     */
    static int expressionStart(String text, int dot) {
        int index = dot - 1;
        int lineStart = text.lastIndexOf('\n', dot) + 1;
        int start = -1;
        while (index >= lineStart) {
            char c = text.charAt(index);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                index--;
            } else if (c == ')' || c == ']') {
                index = matchingOpen(text, index, lineStart);
                if (index < 0) {
                    return -1;
                }
                index--;
            } else if (c == '"') {
                int open = text.lastIndexOf('"', index - 1);
                if (open < lineStart) {
                    return -1;
                }
                index = open - 1;
            } else {
                break;
            }
            start = index + 1;
        }
        if (start < 0 || start >= dot) {
            return -1;
        }
        String expression = text.substring(start, dot);
        boolean numeric = expression.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.' || ch == '_');
        char first = expression.charAt(0);
        if (numeric || first == '.' || expression.endsWith(".")) {
            return -1;
        }
        return start;
    }

    private static int matchingOpen(String text, int close, int lineStart) {
        char closing = text.charAt(close);
        char opening = closing == ')' ? '(' : '[';
        int depth = 0;
        for (int i = close; i >= lineStart; i--) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int open = text.lastIndexOf(c, i - 1);
                if (open < lineStart) {
                    return -1;
                }
                i = open;
            } else if (c == closing) {
                depth++;
            } else if (c == opening && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
