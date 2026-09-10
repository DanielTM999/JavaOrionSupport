package dtm.ide.spring;

import dtm.ide.editor.JavaSourceText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaSourceLexer {

    public static final Pattern PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    public static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

    public static final Pattern TYPE_DECLARATION =
            Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Za-z_]\\w*)");

    public static final Pattern ANNOTATION =
            Pattern.compile("@([A-Za-z_][\\w.]*)\\s*(?:\\(\\s*(.*?)\\s*\\))?", Pattern.DOTALL);

    public static final Pattern FIELD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:private|protected|public|final|static|transient|volatile)\\s+)*"
                    + "([A-Za-z_][\\w.]*(?:\\s*<[^;=]*>)?)\\s+([A-Za-z_]\\w*)\\s*[;=]");

    public static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract|synchronized|default)\\s+)*"
                    + "(?:<[^>]+>\\s*)?"
                    + "(?:([A-Za-z_][\\w.]*(?:\\s*<[^{;]*>)?(?:\\[\\])?)\\s+)?"
                    + "([A-Za-z_]\\w*)\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)");

    public static final Pattern QUALIFIER_ARGUMENT =
            Pattern.compile("@Qualifier\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    public static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    private JavaSourceLexer() {
    }

    public record Source(String structural, String literal) {

        public static Source of(String raw) {
            String withoutComments = JavaSourceText.blankComments(raw);
            return new Source(JavaSourceText.blankStringContents(withoutComments), withoutComments);
        }

        public Source sub(int from, int to) {
            int start = Math.max(0, Math.min(from, structural.length()));
            int end = Math.max(start, Math.min(to, structural.length()));
            return new Source(structural.substring(start, end), literal.substring(start, end));
        }
    }

    public record Annotation(String name, String arguments) {

        public String simpleName() {
            String value = name;
            int lastDot = value.lastIndexOf('.');
            return (lastDot >= 0 ? value.substring(lastDot + 1) : value).toLowerCase(Locale.ROOT);
        }
    }

    public record Parameter(String type, String name, String qualifier,
                            List<String> annotations) {

        public Parameter {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }

        public Parameter(String type, String name, String qualifier) {
            this(type, name, qualifier, List.of());
        }
    }

    public static String packageOf(String code) {
        Matcher matcher = PACKAGE.matcher(code);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static List<String> importsOf(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(code);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return List.copyOf(imports);
    }

    public static boolean isAnnotationDeclaration(String code, int declarationStart) {
        for (int i = declarationStart - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '@') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    public static String blankComments(String source) {
        return JavaSourceText.blankComments(source);
    }

    public static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    public static int lineOf(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        if (index >= 0) {
            return index + 1;
        }
        return -index - 1;
    }

    public static int matchingBrace(String code, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return code.length();
    }

    public static int openingParenthesis(String code, int closeIndex) {
        int depth = 0;
        for (int i = closeIndex; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return 0;
    }

    public static List<String> splitTopLevel(String value) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    public static List<Annotation> annotationsBefore(Source source, int position) {
        String code = source.structural();
        int start = Math.min(position, code.length());
        while (start > 0) {
            char c = code.charAt(start - 1);
            if (c == ')') {
                start = openingParenthesis(code, start - 1);
                continue;
            }
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            start--;
        }
        List<Annotation> annotations = new ArrayList<>();
        String literal = source.literal();
        int cursor = Math.max(0, start);
        int limit = Math.min(position, code.length());
        while (cursor < limit) {
            int at = code.indexOf('@', cursor);
            if (at < 0 || at >= limit) {
                break;
            }
            int nameStart = at + 1;
            int nameEnd = nameStart;
            while (nameEnd < limit) {
                char c = code.charAt(nameEnd);
                if (!Character.isJavaIdentifierPart(c) && c != '.') {
                    break;
                }
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                cursor = at + 1;
                continue;
            }
            int next = nameEnd;
            while (next < limit && Character.isWhitespace(code.charAt(next))) {
                next++;
            }
            String arguments = "";
            if (next < limit && code.charAt(next) == '(') {
                int close = matchingParenthesis(code, next, limit);
                arguments = literal.substring(next + 1, close);
                cursor = Math.min(limit, close + 1);
            } else {
                cursor = nameEnd;
            }
            annotations.add(new Annotation(literal.substring(nameStart, nameEnd), arguments));
        }
        return annotations;
    }

    private static int matchingParenthesis(String code, int open, int limit) {
        int depth = 0;
        for (int i = open; i < limit; i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return limit;
    }

    public static boolean hasAnnotation(List<Annotation> annotations, String simpleName) {
        return annotations.stream().anyMatch(annotation -> annotation.simpleName().equals(simpleName));
    }

    public static boolean hasAnyAnnotation(List<Annotation> annotations, java.util.Set<String> names) {
        return annotations.stream().anyMatch(annotation -> names.contains(annotation.simpleName()));
    }

    public static Annotation annotationNamed(List<Annotation> annotations, String simpleName) {
        return annotations.stream()
                .filter(annotation -> annotation.simpleName().equals(simpleName))
                .findFirst()
                .orElse(null);
    }

    public static String argumentsOf(List<Annotation> annotations, String simpleName) {
        return annotations.stream()
                .filter(annotation -> annotation.simpleName().equals(simpleName))
                .map(Annotation::arguments)
                .findFirst()
                .orElse("");
    }

    public static String firstStringLiteral(String arguments) {
        List<String> literals = stringLiterals(arguments);
        return literals.isEmpty() ? "" : literals.getFirst();
    }

    public static String concatenatedStringLiterals(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        int cursor = 0;
        boolean literalSeen = false;
        boolean expectsLiteral = true;
        while (cursor < expression.length()) {
            char current = expression.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (expression.startsWith("//", cursor)) {
                int newline = expression.indexOf('\n', cursor + 2);
                cursor = newline < 0 ? expression.length() : newline + 1;
                continue;
            }
            if (expression.startsWith("/*", cursor)) {
                int close = expression.indexOf("*/", cursor + 2);
                if (close < 0) {
                    return "";
                }
                cursor = close + 2;
                continue;
            }
            if (current == '(' || current == ')') {
                cursor++;
                continue;
            }
            if (current == '+') {
                if (expectsLiteral || !literalSeen) {
                    return "";
                }
                expectsLiteral = true;
                cursor++;
                continue;
            }
            if (current != '"' || !expectsLiteral) {
                return "";
            }
            int end;
            int delimiter;
            if (expression.startsWith("\"\"\"", cursor)) {
                end = textBlockEnd(expression, cursor);
                delimiter = 3;
            } else {
                end = stringLiteralEnd(expression, cursor);
                delimiter = 1;
            }
            boolean closed = end >= cursor + delimiter * 2 && end <= expression.length()
                    && expression.substring(end - delimiter, end)
                    .equals(delimiter == 3 ? "\"\"\"" : "\"");
            if (!closed) {
                return "";
            }
            value.append(expression, cursor + delimiter, end - delimiter);
            literalSeen = true;
            expectsLiteral = false;
            cursor = end;
        }
        return literalSeen && !expectsLiteral ? value.toString() : "";
    }

    public static List<String> stringLiterals(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        int cursor = 0;
        while (cursor < arguments.length()) {
            int quote = arguments.indexOf('"', cursor);
            if (quote < 0) {
                break;
            }
            if (arguments.startsWith("\"\"\"", quote)) {
                int close = textBlockEnd(arguments, quote);
                boolean closed = close >= quote + 6 && close <= arguments.length()
                        && arguments.startsWith("\"\"\"", close - 3);
                int contentEnd = closed ? close - 3 : arguments.length();
                values.add(arguments.substring(quote + 3, contentEnd));
                cursor = Math.max(quote + 3, close);
                continue;
            }
            int close = stringLiteralEnd(arguments, quote);
            int contentEnd = close > quote && close <= arguments.length()
                    && arguments.charAt(close - 1) == '"' ? close - 1 : close;
            values.add(arguments.substring(quote + 1, Math.max(quote + 1, contentEnd)));
            cursor = Math.max(quote + 1, close);
        }
        return List.copyOf(values);
    }

    private static int textBlockEnd(String source, int at) {
        int cursor = at + 3;
        while (cursor < source.length()) {
            if (source.charAt(cursor) == '\\') {
                cursor = Math.min(source.length(), cursor + 2);
            } else if (source.startsWith("\"\"\"", cursor)) {
                return cursor + 3;
            } else {
                cursor++;
            }
        }
        return source.length();
    }

    private static int stringLiteralEnd(String source, int at) {
        int cursor = at + 1;
        while (cursor < source.length()) {
            char c = source.charAt(cursor);
            if (c == '\\' && cursor + 1 < source.length()) {
                cursor += 2;
            } else if (c == '"') {
                return cursor + 1;
            } else if (c == '\n' || c == '\r') {
                return cursor;
            } else {
                cursor++;
            }
        }
        return cursor;
    }

    public static String namedArgument(String arguments, String name) {
        return firstStringLiteral(namedArgumentRegion(arguments, name));
    }

    public static String namedArgumentRegion(String arguments, String name) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        String searchable = maskLiteralsAndComments(arguments);
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=")
                .matcher(searchable);
        if (!matcher.find()) {
            return "";
        }
        Matcher next = Pattern.compile("\\b[A-Za-z_]\\w*\\s*=")
                .matcher(searchable.substring(matcher.end()));
        int end = next.find() ? matcher.end() + next.start() : arguments.length();
        return arguments.substring(matcher.end(), end);
    }

    public static String firstArgumentRegion(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        String searchable = maskLiteralsAndComments(arguments);
        int depth = 0;
        for (int index = 0; index < searchable.length(); index++) {
            char current = searchable.charAt(index);
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                return arguments.substring(0, index);
            }
        }
        return arguments;
    }

    private static String maskLiteralsAndComments(String source) {
        char[] masked = source.toCharArray();
        int cursor = 0;
        while (cursor < source.length()) {
            int end;
            if (source.startsWith("//", cursor)) {
                int newline = source.indexOf('\n', cursor + 2);
                end = newline < 0 ? source.length() : newline;
            } else if (source.startsWith("/*", cursor)) {
                int close = source.indexOf("*/", cursor + 2);
                end = close < 0 ? source.length() : close + 2;
            } else if (source.startsWith("\"\"\"", cursor)) {
                end = textBlockEnd(source, cursor);
            } else if (source.charAt(cursor) == '"') {
                end = stringLiteralEnd(source, cursor);
            } else {
                cursor++;
                continue;
            }
            for (int i = cursor; i < end; i++) {
                if (masked[i] != '\n' && masked[i] != '\r') {
                    masked[i] = ' ';
                }
            }
            cursor = Math.max(cursor + 1, end);
        }
        return new String(masked);
    }

    public static List<Parameter> parseParameters(String rawParameters) {
        if (rawParameters == null || rawParameters.isBlank()) {
            return List.of();
        }
        List<Parameter> parameters = new ArrayList<>();
        for (String raw : splitTopLevel(rawParameters)) {
            String declaration = raw.trim();
            if (declaration.isEmpty()) {
                continue;
            }
            String qualifier = "";
            Matcher qualifierMatcher = QUALIFIER_ARGUMENT.matcher(declaration);
            if (qualifierMatcher.find()) {
                qualifier = qualifierMatcher.group(1);
            }
            List<String> parameterAnnotations = new ArrayList<>();
            Matcher annotationMatcher = ANNOTATION.matcher(declaration);
            while (annotationMatcher.find()) {
                parameterAnnotations.add(
                        new Annotation(annotationMatcher.group(1), "").simpleName());
            }
            declaration = ANNOTATION.matcher(declaration).replaceAll(" ").trim();
            declaration = declaration.replaceAll("\\bfinal\\b", " ").trim();

            int lastSpace = lastTopLevelSpace(declaration);
            if (lastSpace <= 0) {
                continue;
            }
            String type = declaration.substring(0, lastSpace).trim();
            String name = declaration.substring(lastSpace + 1).trim();
            if (!type.isBlank() && !name.isBlank()) {
                parameters.add(new Parameter(type, name, qualifier,
                        List.copyOf(parameterAnnotations)));
            }
        }
        return parameters;
    }

    public static List<String> supertypesOf(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> supertypes = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?:extends|implements)\\s+([^{]+)").matcher(header);
        while (matcher.find()) {
            for (String candidate : splitTopLevel(matcher.group(1))) {
                String name = candidate.trim().replaceAll("\\s+", " ");
                for (String part : name.split("\\bimplements\\b|\\bextends\\b")) {
                    String supertype = part.trim();
                    if (!supertype.isBlank()) {
                        supertypes.add(supertype);
                    }
                }
            }
        }
        return List.copyOf(supertypes);
    }

    public static List<String> typeArgumentsOf(String type) {
        if (type == null) {
            return List.of();
        }
        int open = type.indexOf('<');
        if (open < 0 || !type.trim().endsWith(">")) {
            return List.of();
        }
        int close = type.lastIndexOf('>');
        if (close <= open) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        for (String part : splitTopLevel(type.substring(open + 1, close))) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                arguments.add(trimmed);
            }
        }
        return arguments;
    }

    private static int lastTopLevelSpace(String declaration) {
        int depth = 0;
        int lastSpace = -1;
        for (int i = 0; i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                lastSpace = i;
            }
        }
        return lastSpace;
    }
}
