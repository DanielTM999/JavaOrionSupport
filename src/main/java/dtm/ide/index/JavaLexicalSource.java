package dtm.ide.index;

import dtm.ide.refactor.JavaSafeDeleteScanner;
import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaLexicalSource {

    private static final Pattern TYPE = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"
                    + "|@interface\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract|default|"
                    + "synchronized|native|strictfp)\\s+)*(?:<[^>]+>\\s*)?"
                    + "[A-Za-z_$][\\w$.,<>? \\[\\]]*\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern FIELD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private)\\s+)"
                    + "(?:(?:static|final|transient|volatile)\\s+)*"
                    + "[A-Za-z_$][\\w$.<>,\\[\\] ?]*\\s+([A-Za-z_$][\\w$]*)\\s*(?:=|;)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "continue", "default", "do", "double", "else", "enum", "extends",
            "false", "final", "finally", "float", "for", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new", "null", "package",
            "private", "protected", "public", "record", "return", "sealed", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "true", "try", "var", "void", "volatile", "while",
            "yield", "permits", "requires", "exports", "module", "open", "opens", "provides",
            "to", "uses", "with", "transitive");

    public record Declared(String name, SymbolKind kind, Range range, int depth) {
    }

    private JavaLexicalSource() {
    }

    public static String mask(String source) {
        return JavaSafeDeleteScanner.maskNonCode(source);
    }

    public static boolean isKeyword(String name) {
        return KEYWORDS.contains(name);
    }

    public static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] answer = new int[starts.size()];
        for (int i = 0; i < answer.length; i++) {
            answer[i] = starts.get(i);
        }
        return answer;
    }

    public static int lineOf(int[] lineStarts, int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    public static Range rangeOf(int[] lineStarts, int start, int end) {
        int startLine = lineOf(lineStarts, start);
        int endLine = lineOf(lineStarts, end);
        return Range.of(startLine, start - lineStarts[startLine],
                endLine, end - lineStarts[endLine]);
    }

    public static List<Declared> declarations(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String code = mask(source);
        int[] lineStarts = lineStarts(code);
        int[] depths = depthPrefix(code);
        List<Declared> found = new ArrayList<>();

        Matcher types = TYPE.matcher(code);
        while (types.find()) {
            int group = types.group(2) != null ? 2 : 3;
            String name = types.group(group);
            if (name == null) {
                continue;
            }
            SymbolKind kind = switch (types.group(1) == null ? "@interface" : types.group(1)) {
                case "interface", "@interface" -> SymbolKind.INTERFACE;
                case "enum" -> SymbolKind.ENUM;
                case "record" -> SymbolKind.STRUCT;
                default -> SymbolKind.CLASS;
            };
            found.add(new Declared(name, kind,
                    rangeOf(lineStarts, types.start(group), types.end(group)),
                    depthAt(depths, types.start(group))));
        }

        Matcher methods = METHOD.matcher(code);
        while (methods.find()) {
            String name = methods.group(1);
            if (KEYWORDS.contains(name)) {
                continue;
            }
            found.add(new Declared(name, SymbolKind.METHOD,
                    rangeOf(lineStarts, methods.start(1), methods.end(1)),
                    depthAt(depths, methods.start(1))));
        }

        Matcher fields = FIELD.matcher(code);
        while (fields.find()) {
            String name = fields.group(1);
            if (KEYWORDS.contains(name)) {
                continue;
            }
            found.add(new Declared(name, SymbolKind.FIELD,
                    rangeOf(lineStarts, fields.start(1), fields.end(1)),
                    depthAt(depths, fields.start(1))));
        }

        found.sort((left, right) -> {
            int line = Integer.compare(left.range().start().line(), right.range().start().line());
            return line != 0 ? line
                    : Integer.compare(left.range().start().col(), right.range().start().col());
        });
        return List.copyOf(found);
    }

    public static List<DocumentSymbol> outline(String source) {
        List<Declared> declared = declarations(source);
        if (declared.isEmpty()) {
            return List.of();
        }
        List<DocumentSymbol> roots = new ArrayList<>();
        Deque<Container> stack = new ArrayDeque<>();
        for (Declared entry : declared) {
            while (!stack.isEmpty() && entry.depth() <= stack.peek().depth()) {
                closeTop(stack, roots);
            }
            if (isType(entry.kind())) {
                stack.push(new Container(entry, new ArrayList<>()));
                continue;
            }
            DocumentSymbol leaf = DocumentSymbol.leaf(entry.name(), entry.kind(), entry.range());
            if (stack.isEmpty()) {
                roots.add(leaf);
            } else {
                stack.peek().children().add(leaf);
            }
        }
        while (!stack.isEmpty()) {
            closeTop(stack, roots);
        }
        return List.copyOf(roots);
    }

    public static List<int[]> occurrences(String maskedCode, Set<String> names) {
        List<int[]> found = new ArrayList<>();
        if (maskedCode == null || maskedCode.isEmpty() || names == null || names.isEmpty()) {
            return found;
        }
        Matcher matcher = IDENTIFIER.matcher(maskedCode);
        while (matcher.find()) {
            if (names.contains(matcher.group())) {
                found.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return found;
    }

    public static void identifiers(String maskedCode, java.util.function.Consumer<String> sink) {
        if (maskedCode == null || maskedCode.isEmpty()) {
            return;
        }
        Matcher matcher = IDENTIFIER.matcher(maskedCode);
        while (matcher.find()) {
            String name = matcher.group();
            if (name.length() > 1 && !KEYWORDS.contains(name)) {
                sink.accept(name);
            }
        }
    }

    private record Container(Declared declared, List<DocumentSymbol> children) {
        int depth() {
            return declared.depth();
        }
    }

    private static void closeTop(Deque<Container> stack, List<DocumentSymbol> roots) {
        Container top = stack.pop();
        DocumentSymbol symbol = new DocumentSymbol(top.declared().name(), null,
                top.declared().kind(), top.declared().range(), top.declared().range(),
                top.children());
        if (stack.isEmpty()) {
            roots.add(symbol);
        } else {
            stack.peek().children().add(symbol);
        }
    }

    private static boolean isType(SymbolKind kind) {
        return kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE
                || kind == SymbolKind.ENUM || kind == SymbolKind.STRUCT;
    }

    private static int[] depthPrefix(String code) {
        int[] depths = new int[code.length() + 1];
        int depth = 0;
        for (int i = 0; i < code.length(); i++) {
            depths[i] = Math.max(0, depth);
            char current = code.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
            }
        }
        depths[code.length()] = Math.max(0, depth);
        return depths;
    }

    private static int depthAt(int[] depths, int offset) {
        if (offset < 0) {
            return 0;
        }
        return offset < depths.length ? depths[offset] : depths[depths.length - 1];
    }
}
