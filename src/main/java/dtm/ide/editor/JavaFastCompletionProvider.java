package dtm.ide.editor;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.index.JavaLexicalIndex;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaFastCompletionProvider {

    private static final int MAX_ITEMS = 100;
    private static final Pattern TYPE = Pattern.compile(
            "\\b(class|interface|record|enum)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract|default|"
                    + "synchronized|native|strictfp)\\s+)*(?:<[^>]+>\\s*)?"
                    + "[A-Za-z_$][\\w$.,<>? \\[\\]]*\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern DECLARATION = Pattern.compile(
            "(?<![\\w$.])((?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*[A-Za-z_$][\\w$]*"
                    + "(?:\\s*<[^<>;{}()]*>)?(?:\\s*\\[\\s*\\])*)\\s+([A-Za-z_$][\\w$]*)"
                    + "\\s*(?=[=;,)])");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "continue", "default", "do", "double", "else", "enum", "extends",
            "false", "final", "finally", "float", "for", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "native", "new", "null", "package",
            "private", "protected", "public", "record", "return", "sealed", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "true", "try", "var", "void", "volatile", "while",
            "yield", "permits", "non-sealed");

    private static final Set<String> DECLARABLE_KEYWORD_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "var");

    private static final List<String> COMMON_TYPES = List.of(
            "String", "Object", "Integer", "Long", "Double", "Boolean", "Character",
            "Exception", "RuntimeException", "System", "Math", "Thread", "Runnable",
            "List", "ArrayList", "Map", "HashMap", "Set", "HashSet", "Collection",
            "Optional", "Stream", "Collectors", "Objects", "Collections", "Arrays",
            "LocalDate", "LocalDateTime", "Instant", "BigDecimal");

    private static final Map<String, List<String>> COMMON_MEMBERS = Map.ofEntries(
            Map.entry("Object", List.of("equals", "hashCode", "toString", "getClass")),
            Map.entry("String", List.of("length", "isEmpty", "isBlank", "charAt", "substring",
                    "contains", "startsWith", "endsWith", "strip", "trim", "split", "replace",
                    "toLowerCase", "toUpperCase", "formatted", "lines")),
            Map.entry("List", List.of("add", "addAll", "get", "set", "remove", "size",
                    "isEmpty", "contains", "stream", "forEach", "iterator")),
            Map.entry("Collection", List.of("add", "addAll", "remove", "size", "isEmpty",
                    "contains", "stream", "forEach", "iterator")),
            Map.entry("Map", List.of("put", "putAll", "get", "getOrDefault", "remove", "size",
                    "isEmpty", "containsKey", "keySet", "values", "entrySet", "forEach")),
            Map.entry("Optional", List.of("of", "ofNullable", "empty", "get", "orElse",
                    "orElseGet", "orElseThrow", "ifPresent", "map", "flatMap", "filter")),
            Map.entry("Stream", List.of("map", "flatMap", "filter", "sorted", "distinct",
                    "limit", "skip", "forEach", "collect", "toList", "findFirst", "count")),
            Map.entry("System", List.of("currentTimeMillis", "nanoTime", "getenv", "getProperty",
                    "setProperty", "lineSeparator", "exit", "gc")),
            Map.entry("Objects", List.of("requireNonNull", "nonNull", "isNull", "equals",
                    "deepEquals", "hash", "hashCode", "toString")),
            Map.entry("Collections", List.of("emptyList", "emptyMap", "emptySet", "singletonList",
                    "singletonMap", "unmodifiableList", "sort", "reverse", "shuffle")),
            Map.entry("Arrays", List.of("asList", "copyOf", "copyOfRange", "equals", "deepEquals",
                    "fill", "sort", "stream", "toString")));

    private final JavaLexicalIndex projectIndex;

    public JavaFastCompletionProvider(JavaLexicalIndex projectIndex) {
        this.projectIndex = projectIndex;
    }

    private record Symbol(String name, AutoCompleteItem.Kind kind, String detail) {
        AutoCompleteItem item() {
            return new AutoCompleteItem(name, name, detail, "", null, kind);
        }
    }

    private static AutoCompleteItem.Kind kindOf(SymbolKind kind) {
        return switch (kind) {
            case INTERFACE -> AutoCompleteItem.Kind.INTERFACE;
            case ENUM -> AutoCompleteItem.Kind.ENUM;
            case CLASS, STRUCT -> AutoCompleteItem.Kind.CLASS;
            case METHOD, CONSTRUCTOR, FUNCTION -> AutoCompleteItem.Kind.METHOD;
            case FIELD, PROPERTY -> AutoCompleteItem.Kind.FIELD;
            default -> AutoCompleteItem.Kind.VARIABLE;
        };
    }

    public List<AutoCompleteItem> suggestions(IdeCompletionContext context) {
        if (context == null) return List.of();
        String prefix = context.prefix() == null ? "" : context.prefix();
        boolean memberAccess = isMemberAccess(context);
        Map<String, Symbol> candidates = new LinkedHashMap<>();

        if (memberAccess) {
            String receiver = receiverBeforeDot(context);
            String type = inferredType(context.text(), receiver);
            addMembers(candidates, type);
            addMembers(candidates, "Object");
            if ("this".equals(receiver) || type == null) {
                declarationsOf(context.text(), "arquivo atual").stream()
                        .filter(symbol -> symbol.kind() == AutoCompleteItem.Kind.METHOD)
                        .forEach(symbol -> candidates.putIfAbsent(key(symbol), symbol));
            }
        } else {
            visibleVariables(context.text(), context.prefixOffset(), "visivel no escopo")
                    .reversed()
                    .forEach(symbol -> put(candidates, symbol));
            declarationsOf(context.text(), "arquivo atual")
                    .forEach(symbol -> put(candidates, symbol));
            if (projectIndex != null) {
                projectIndex.projectSymbols().forEach(symbol -> put(candidates,
                        new Symbol(symbol.name(), kindOf(symbol.kind()), symbol.detail())));
            }
            COMMON_TYPES.forEach(value -> put(candidates,
                    new Symbol(value, AutoCompleteItem.Kind.CLASS, "Java")));
            KEYWORDS.stream().sorted().forEach(value -> put(candidates,
                    new Symbol(value, AutoCompleteItem.Kind.KEYWORD, "palavra-chave Java")));
        }

        String needle = prefix.toLowerCase(Locale.ROOT);
        List<AutoCompleteItem> exactCase = new ArrayList<>();
        List<AutoCompleteItem> insensitive = new ArrayList<>();
        for (Symbol symbol : candidates.values()) {
            if (needle.isEmpty() || symbol.name().startsWith(prefix)) {
                exactCase.add(symbol.item());
            } else if (symbol.name().toLowerCase(Locale.ROOT).startsWith(needle)) {
                insensitive.add(symbol.item());
            }
        }
        List<AutoCompleteItem> answer = new ArrayList<>(Math.min(
                MAX_ITEMS, exactCase.size() + insensitive.size()));
        exactCase.stream().limit(MAX_ITEMS).forEach(answer::add);
        insensitive.stream().limit(MAX_ITEMS - answer.size()).forEach(answer::add);
        return List.copyOf(answer);
    }

    public static boolean isMemberAccess(IdeCompletionContext context) {
        if (context == null || context.text() == null) return false;
        int index = Math.min(context.prefixOffset(), context.text().length()) - 1;
        while (index >= 0 && Character.isWhitespace(context.text().charAt(index))) index--;
        return index >= 0 && context.text().charAt(index) == '.';
    }

    private static String receiverBeforeDot(IdeCompletionContext context) {
        String text = context.text();
        int index = Math.min(context.prefixOffset(), text.length()) - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
        if (index < 0 || text.charAt(index) != '.') return "";
        index--;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
        int end = index + 1;
        while (index >= 0 && Character.isJavaIdentifierPart(text.charAt(index))) index--;
        return text.substring(index + 1, end);
    }

    private static String inferredType(String source, String receiver) {
        if (receiver == null || receiver.isBlank()) return null;
        if (Character.isUpperCase(receiver.charAt(0))) return receiver;
        Pattern declaration = Pattern.compile("\\b([A-Za-z_$][\\w$]*(?:\\s*<[^;=(){}]+>)?)"
                + "\\s+" + Pattern.quote(receiver) + "\\b");
        Matcher matches = declaration.matcher(structural(source));
        String found = null;
        while (matches.find()) {
            found = matches.group(1).replaceAll("<.*", "").trim();
        }
        return found;
    }

    private static void addMembers(Map<String, Symbol> target, String type) {
        if (type == null) return;
        List<String> members = COMMON_MEMBERS.get(type);
        if (members == null && (type.endsWith("List") || type.endsWith("Collection"))) {
            members = COMMON_MEMBERS.get("List");
        }
        if (members == null && type.endsWith("Map")) members = COMMON_MEMBERS.get("Map");
        if (members == null) return;
        for (String member : members) {
            put(target, new Symbol(member, AutoCompleteItem.Kind.METHOD, type + " member"));
        }
    }

    private static List<Symbol> declarationsOf(String source, String detail) {
        if (source == null || source.isBlank()) return List.of();
        String code = structural(source);
        Map<String, Symbol> found = new LinkedHashMap<>();
        Matcher types = TYPE.matcher(code);
        while (types.find()) {
            AutoCompleteItem.Kind kind = switch (types.group(1)) {
                case "interface" -> AutoCompleteItem.Kind.INTERFACE;
                case "enum" -> AutoCompleteItem.Kind.ENUM;
                default -> AutoCompleteItem.Kind.CLASS;
            };
            put(found, new Symbol(types.group(2), kind, detail));
        }
        Matcher methods = METHOD.matcher(code);
        while (methods.find()) {
            String name = methods.group(1);
            if (!KEYWORDS.contains(name)) {
                put(found, new Symbol(name, AutoCompleteItem.Kind.METHOD, detail));
            }
        }
        return List.copyOf(found.values());
    }

    private static List<Symbol> visibleVariables(String source, int caretOffset, String detail) {
        if (source == null || source.isBlank()) return List.of();
        String code = structural(source);
        int limit = caretOffset < 0 ? code.length() : Math.min(caretOffset, code.length());
        Map<String, Symbol> found = new LinkedHashMap<>();
        Matcher declarations = DECLARATION.matcher(code.substring(0, limit));
        while (declarations.find()) {
            String type = declarations.group(1).replaceAll("\\s+", "");
            String name = declarations.group(2);
            if (KEYWORDS.contains(name)
                    || (KEYWORDS.contains(type) && !DECLARABLE_KEYWORD_TYPES.contains(type))) {
                continue;
            }
            put(found, new Symbol(name, AutoCompleteItem.Kind.VARIABLE,
                    "var".equals(type) ? detail : type));
        }
        return List.copyOf(found.values());
    }

    private static String structural(String source) {
        return JavaSourceText.blankStringContents(JavaSourceText.blankComments(source));
    }

    private static void put(Map<String, Symbol> target, Symbol symbol) {
        target.putIfAbsent(key(symbol), symbol);
    }

    private static String key(Symbol symbol) {
        return symbol.name();
    }
}
