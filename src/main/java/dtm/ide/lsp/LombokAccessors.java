package dtm.ide.lsp;

import dtm.ide.index.JavaLexicalSource;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.SymbolKind;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LombokAccessors {

    public record Accessor(String oldName, String newName) {
    }

    private record Member(DocumentSymbol type, DocumentSymbol field) {
    }

    private static final Pattern BUILDER = Pattern.compile(
            "@\\s*(?:lombok\\s*\\.\\s*)?Builder(?![\\w$.])(\\s*\\(([^)]*)\\))?");
    private static final Pattern SUPER_BUILDER = Pattern.compile(
            "@\\s*(?:lombok\\s*\\.\\s*experimental\\s*\\.\\s*)?SuperBuilder(?![\\w$.])(\\s*\\(([^)]*)\\))?");
    private static final Pattern WITH = Pattern.compile(
            "@\\s*(?:lombok\\s*\\.\\s*)?With(?![\\w$.])(\\s*\\(([^)]*)\\))?");
    private static final Pattern ACCESSORS_PREFIX = Pattern.compile(
            "@\\s*(?:lombok\\s*\\.\\s*experimental\\s*\\.\\s*)?Accessors\\s*\\([^)]*\\bprefix\\b");
    private static final Pattern SETTER_PREFIX = Pattern.compile("setterPrefix\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern NONE_ACCESS = Pattern.compile("\\bAccessLevel\\s*\\.\\s*NONE\\b");
    private static final Pattern STATIC = Pattern.compile("\\bstatic\\b");
    private static final Pattern FINAL = Pattern.compile("\\bfinal\\b");

    private LombokAccessors() {
    }

    public static List<Accessor> of(String source, List<DocumentSymbol> symbols, Position field, String newName) {
        if (source == null || symbols == null || field == null || !isIdentifier(newName)) {
            return List.of();
        }
        Member member = find(symbols, null, field);
        if (member == null || member.type() == null || !isIdentifier(member.field().name())) {
            return List.of();
        }
        String oldName = member.field().name();
        String masked = JavaLexicalSource.mask(source);
        int[] lineStarts = JavaLexicalSource.lineStarts(source);
        String header = slice(source, lineStarts, member.type().range().start(), member.type().selectionRange().start());
        String maskedHeader = slice(masked, lineStarts, member.type().range().start(), member.type().selectionRange().start());
        String declaration = slice(masked, lineStarts, member.field().range().start(), member.field().selectionRange().start());
        String initializer = slice(masked, lineStarts, member.field().selectionRange().end(), member.field().range().end());
        if (STATIC.matcher(declaration).find()) {
            return List.of();
        }
        boolean initializedFinal = FINAL.matcher(declaration).find() && initializer.contains("=");
        List<Accessor> accessors = new ArrayList<>();
        if (!initializedFinal) {
            String prefix = builderPrefix(maskedHeader, header);
            if (prefix != null) {
                add(accessors, member.type(), prefixed(prefix, oldName), prefixed(prefix, newName));
            }
        }
        boolean with = has(WITH, maskedHeader) || has(WITH, declaration);
        boolean prefixed = ACCESSORS_PREFIX.matcher(maskedHeader).find()
                || ACCESSORS_PREFIX.matcher(declaration).find();
        if (with && !prefixed && !initializedFinal && !noneAccess(WITH, declaration)) {
            boolean bool = Pattern.compile("\\bboolean\\s+$").matcher(declaration).find();
            add(accessors, member.type(), "with" + capitalize(stripIs(oldName, bool)),
                    "with" + capitalize(stripIs(newName, bool)));
        }
        return List.copyOf(accessors);
    }

    private static Member find(List<DocumentSymbol> symbols, DocumentSymbol parent, Position position) {
        for (DocumentSymbol symbol : symbols) {
            if (symbol == null || symbol.range() == null || symbol.selectionRange() == null) {
                continue;
            }
            if ((symbol.kind() == SymbolKind.FIELD || symbol.kind() == SymbolKind.PROPERTY)
                    && symbol.selectionRange().contains(position)
                    && symbol.range().contains(symbol.selectionRange().start())
                    && !symbol.range().equals(symbol.selectionRange())) {
                return new Member(isType(parent) ? parent : null, symbol);
            }
            if (symbol.range().contains(position) && symbol.children() != null) {
                Member nested = find(symbol.children(), symbol, position);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static boolean isType(DocumentSymbol symbol) {
        return symbol != null && (symbol.kind() == SymbolKind.CLASS || symbol.kind() == SymbolKind.ENUM);
    }

    private static String builderPrefix(String maskedHeader, String header) {
        for (Pattern pattern : List.of(BUILDER, SUPER_BUILDER)) {
            Matcher masked = pattern.matcher(maskedHeader);
            if (!masked.find()) {
                continue;
            }
            Matcher raw = pattern.matcher(header);
            String arguments = raw.find(masked.start()) && raw.group(2) != null ? raw.group(2) : "";
            Matcher prefix = SETTER_PREFIX.matcher(arguments);
            return prefix.find() ? prefix.group(1) : "";
        }
        return null;
    }

    private static boolean noneAccess(Pattern annotation, String declaration) {
        Matcher matcher = annotation.matcher(declaration);
        return matcher.find() && matcher.group(2) != null && NONE_ACCESS.matcher(matcher.group(2)).find();
    }

    private static boolean has(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }

    private static void add(List<Accessor> accessors, DocumentSymbol type, String oldName, String newName) {
        if (oldName.equals(newName) || declaresMethod(type, oldName)) {
            return;
        }
        accessors.add(new Accessor(oldName, newName));
    }

    private static boolean declaresMethod(DocumentSymbol type, String name) {
        if (type.children() == null) {
            return false;
        }
        for (DocumentSymbol child : type.children()) {
            if (child != null && child.kind() == SymbolKind.METHOD && child.name() != null
                    && (child.name().equals(name) || child.name().startsWith(name + "("))
                    && child.range() != null && !child.range().equals(child.selectionRange())) {
                return true;
            }
        }
        return false;
    }

    private static String prefixed(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + capitalize(name);
    }

    private static String stripIs(String name, boolean bool) {
        if (bool && name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            return name.substring(2);
        }
        return name;
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String slice(String text, int[] lineStarts, Position from, Position to) {
        int start = offset(text, lineStarts, from);
        int end = offset(text, lineStarts, to);
        return start < 0 || end < start ? "" : text.substring(start, end);
    }

    private static int offset(String text, int[] lineStarts, Position position) {
        if (position == null || position.line() < 0 || position.line() >= lineStarts.length) {
            return -1;
        }
        return Math.min(text.length(), lineStarts[position.line()] + Math.max(0, position.col()));
    }

    private static boolean isIdentifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
