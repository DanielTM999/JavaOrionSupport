package dtm.ide.test;

import dtm.ide.editor.JavaSourceText;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JUnitTestDiscovery {

    private static final Pattern PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern TEST_ANNOTATION = Pattern.compile(
            "(?m)^[ \\t]*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    private static final Pattern TEST_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|default)\\s+)*"
                    + "(?:<[^>]+>\\s*)?[A-Za-z_][\\w.<>\\[\\], ]*\\s+([A-Za-z_]\\w*)\\s*\\(");

    private static final Pattern DISPLAY_NAME =
            Pattern.compile("@DisplayName\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "\\b(?:class|record|interface|enum)\\s+([A-Za-z_$][\\w$]*)\\b");

    /** {@code @Test} aplicado diretamente a uma classe -- o estilo do TestNG. */
    private static final Pattern CLASS_LEVEL_TEST = Pattern.compile(
            "(?m)^[ \\t]*@Test\\b[^\\n]*\\R(?:[ \\t]*@[^\\n]*\\R)*"
                    + "[ \\t]*(?:(?:public|abstract|final|static)\\s+)*"
                    + "class\\s+[A-Za-z_$][\\w$]*");

    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "(?m)^[ \\t]*public\\s+(?!class\\b|record\\b|interface\\b|enum\\b)"
                    + "(?:(?:static|final|synchronized)\\s+)*"
                    + "(?:<[^>]+>\\s*)?[A-Za-z_][\\w.<>\\[\\], ]*\\s+([A-Za-z_]\\w*)\\s*\\(");

    private static final Pattern LIFECYCLE_ANNOTATION = Pattern.compile(
            "@(Before|After)(Suite|Test|Class|Method|Groups|Each|All)?\\b|@DataProvider\\b"
                    + "|@Factory\\b|@Override\\b");

    private static final Set<String> LIFECYCLE_NAMES = Set.of(
            "setUp", "tearDown", "setup", "teardown");

    private static final int MAX_FILES = 20_000;

    private JUnitTestDiscovery() {
    }

    public static List<JavaTest> discover(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        List<JavaTest> tests = new ArrayList<>();
        int visited = 0;

        for (JavaModule module : descriptor.modules()) {
            for (Path testRoot : module.existingTestRoots()) {
                int remaining = MAX_FILES - visited;
                if (remaining <= 0) {
                    return tests.stream().sorted().toList();
                }
                for (Path file : JavaProjectConventions.javaSources(testRoot, 0, remaining)) {
                    visited++;
                    tests.addAll(discoverInFile(file));
                }
            }
        }
        return tests.stream().sorted().toList();
    }

    public static List<JavaTest> discoverInFile(Path file) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return List.of();
        }
        return discoverInSource(file, JavaProjectConventions.readOrEmpty(file));
    }

    public static List<JavaTest> discoverInSource(Path file, String source) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return List.of();
        }
        if (source == null || source.isBlank()
                || !source.contains("@Test") && !source.contains("@Parameterized")
                && !source.contains("@RepeatedTest") && !source.contains("@TestFactory")) {
            return List.of();
        }
        String code = JavaSourceText.blankComments(source);
        int[] lineStarts = lineStarts(source);

        List<JavaTest> tests = new ArrayList<>();
        Matcher annotations = TEST_ANNOTATION.matcher(code);

        while (annotations.find()) {
            boolean parameterized = !"Test".equals(annotations.group(1));
            int signatureStart = skipAnnotations(code, annotations.start());

            Matcher method = TEST_METHOD.matcher(code);
            method.region(signatureStart, code.length());
            if (!method.lookingAt()) {
                continue;
            }
            tests.add(new JavaTest(
                    classNameAt(file, code, method.start()),
                    method.group(1),
                    displayNameNear(code, annotations.start()),
                    file,
                    lineOf(lineStarts, method.start()),
                    parameterized));
        }
        tests.addAll(testNgClassLevel(file, code, lineStarts, tests));
        return tests;
    }

    /**
     * No TestNG, um {@code @Test} na classe transforma todos os metodos publicos em testes.
     * Os metodos de ciclo de vida ({@code @BeforeMethod}, {@code @AfterClass}, ...) e os
     * provedores de dados continuam de fora.
     */
    private static List<JavaTest> testNgClassLevel(Path file, String code, int[] lineStarts,
                                                   List<JavaTest> known) {
        List<JavaTest> tests = new ArrayList<>();
        Matcher types = CLASS_LEVEL_TEST.matcher(code);

        while (types.find()) {
            int open = code.indexOf('{', types.end());
            if (open < 0) {
                continue;
            }
            int close = matchingBrace(code, open);
            if (close < 0) {
                close = code.length();
            }
            String className = classNameAt(file, code, open);
            Matcher methods = PUBLIC_METHOD.matcher(code);
            methods.region(open, close);

            while (methods.find()) {
                String name = methods.group(1);
                if (LIFECYCLE_NAMES.contains(name)
                        || isLifecycleAnnotated(code, methods.start())) {
                    continue;
                }
                boolean duplicated = known.stream().anyMatch(test ->
                        test.className().equals(className) && test.methodName().equals(name));
                if (duplicated) {
                    continue;
                }
                tests.add(new JavaTest(className, name, "", file,
                        lineOf(lineStarts, methods.start()), false));
            }
        }
        return tests;
    }

    /** {@code true} quando o metodo carrega uma anotacao de ciclo de vida ou de dados. */
    private static boolean isLifecycleAnnotated(String code, int methodStart) {
        int from = Math.max(0, methodStart - 220);
        String preceding = code.substring(from, methodStart);
        int lastBlockEnd = Math.max(preceding.lastIndexOf('}'), preceding.lastIndexOf(';'));
        String region = lastBlockEnd < 0 ? preceding : preceding.substring(lastBlockEnd + 1);
        return LIFECYCLE_ANNOTATION.matcher(region).find();
    }

    private static int skipAnnotations(String code, int from) {
        int i = from;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '@') {
                i = endOfAnnotation(code, i);
            } else {
                break;
            }
        }
        int lineStart = code.lastIndexOf('\n', Math.max(0, i - 1));
        return lineStart < 0 ? 0 : lineStart + 1;
    }

    private static int endOfAnnotation(String code, int at) {
        int i = at + 1;
        while (i < code.length() && (Character.isJavaIdentifierPart(code.charAt(i))
                || code.charAt(i) == '.')) {
            i++;
        }
        int cursor = i;
        while (cursor < code.length() && (code.charAt(cursor) == ' ' || code.charAt(cursor) == '\t')) {
            cursor++;
        }
        if (cursor >= code.length() || code.charAt(cursor) != '(') {
            return i;
        }
        int depth = 0;
        for (int j = cursor; j < code.length(); j++) {
            char c = code.charAt(j);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return j + 1;
                }
            }
        }
        return code.length();
    }

    private static String displayNameNear(String code, int annotationStart) {
        int from = Math.max(0, code.lastIndexOf('\n', Math.max(0, annotationStart - 1)));
        int lineStart = Math.max(0, code.lastIndexOf('\n', Math.max(0, from - 1)));
        String region = code.substring(lineStart, Math.min(code.length(), annotationStart + 200));

        Matcher matcher = DISPLAY_NAME.matcher(region);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String classNameOf(Path file, String code) {
        String simpleName = file.getFileName().toString();
        if (simpleName.endsWith(".java")) {
            simpleName = simpleName.substring(0, simpleName.length() - 5);
        }
        Matcher matcher = PACKAGE.matcher(code);
        return matcher.find() ? matcher.group(1) + "." + simpleName : simpleName;
    }

    private static String classNameAt(Path file, String code, int offset) {
        List<TypeScope> scopes = new ArrayList<>();
        Matcher types = TYPE_DECLARATION.matcher(code);
        while (types.find() && types.start() < offset) {
            int open = code.indexOf('{', types.end());
            if (open < 0 || open > offset) {
                continue;
            }
            int close = matchingBrace(code, open);
            if (close < 0 || offset < close) {
                scopes.add(new TypeScope(types.start(), types.group(1)));
            }
        }
        if (scopes.isEmpty()) {
            return classNameOf(file, code);
        }
        scopes.sort(java.util.Comparator.comparingInt(TypeScope::start));
        String nested = String.join("$", scopes.stream().map(TypeScope::name).toList());
        Matcher packageMatcher = PACKAGE.matcher(code);
        return packageMatcher.find() ? packageMatcher.group(1) + "." + nested : nested;
    }

    private static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private record TypeScope(int start, String name) {
    }

    private static int[] lineStarts(String source) {
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

    private static int lineOf(int[] lineStarts, int offset) {
        int index = java.util.Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }
}
