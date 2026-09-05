package dtm.ide.test;

import java.nio.file.Path;
import java.util.List;

public record JavaTest(
        String className,
        String methodName,
        String displayName,
        Path file,
        int line,
        boolean parameterized
) implements Comparable<JavaTest> {

    public JavaTest {
        className = className == null ? "" : className.trim();
        methodName = methodName == null ? "" : methodName.trim();
        displayName = displayName == null ? "" : displayName.trim();
        line = Math.max(1, line);
    }

    public boolean isClassLevel() {
        return methodName.isBlank();
    }

    public String simpleClassName() {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    public String display() {
        if (!displayName.isBlank()) {
            return displayName;
        }
        return isClassLevel() ? simpleClassName() : methodName + "()";
    }

    public String selector() {
        return isClassLevel() ? className : className + "#" + methodName;
    }

    @Override
    public int compareTo(JavaTest other) {
        int byClass = className.compareTo(other.className);
        return byClass != 0 ? byClass : Integer.compare(line, other.line);
    }

    public static java.util.Map<String, List<JavaTest>> byClass(List<JavaTest> tests) {
        java.util.Map<String, List<JavaTest>> grouped = new java.util.LinkedHashMap<>();
        tests.stream().sorted().forEach(test ->
                grouped.computeIfAbsent(test.className(), key -> new java.util.ArrayList<>()).add(test));
        return grouped;
    }
}
