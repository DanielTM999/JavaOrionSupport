package dtm.ide.swingdesigner.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JavaSourceTree {

    private static final int MAX_SNIPPET = 90;

    private JavaSourceTree() {
    }

    public record Unit(CompilationUnitTree tree, SourcePositions positions, String source) {

        public int start(Tree node) {
            return (int) positions.getStartPosition(tree, node);
        }

        public int end(Tree node) {
            return (int) positions.getEndPosition(tree, node);
        }

        public int startLine(Tree node) {
            return line(start(node));
        }

        public int endLine(Tree node) {
            return line(Math.max(start(node), end(node) - 1));
        }

        public int line(int offset) {
            return (int) tree.getLineMap().getLineNumber(Math.max(0, offset));
        }

        public String text(Tree node) {
            int start = start(node);
            int end = end(node);
            if (start < 0 || end <= start) {
                return "";
            }
            return source.substring(start, Math.min(end, source.length()));
        }

        public String snippet(Tree node) {
            String text = text(node).replaceAll("\\s+", " ").trim();
            return text.length() > MAX_SNIPPET ? text.substring(0, MAX_SNIPPET) + "..." : text;
        }

        public String packageName() {
            return tree.getPackageName() == null ? "" : tree.getPackageName().toString();
        }
    }

    public static Optional<Unit> parse(String source) {
        if (source == null) {
            return Optional.empty();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Optional.empty();
        }
        try {
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Source.java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> { },
                    List.of("-proc:none"), null, List.of(file));
            CompilationUnitTree tree = task.parse().iterator().next();
            return Optional.of(new Unit(tree, Trees.instance(task).getSourcePositions(), source));
        } catch (RuntimeException | java.io.IOException e) {
            return Optional.empty();
        }
    }

    public static List<String> simpleNames(String className) {
        int dot = className.lastIndexOf('.');
        String binary = dot < 0 ? className : className.substring(dot + 1);
        List<String> names = new ArrayList<>();
        for (String part : binary.split("\\$")) {
            if (!part.isEmpty() && !Character.isDigit(part.charAt(0))) {
                names.add(part);
            }
        }
        return names;
    }

    public static Optional<ClassTree> findClass(CompilationUnitTree unit, List<String> names) {
        List<? extends Tree> scope = unit.getTypeDecls();
        ClassTree found = null;
        for (String name : names) {
            found = null;
            for (Tree member : scope) {
                if (member instanceof ClassTree candidate && candidate.getSimpleName().contentEquals(name)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                return Optional.empty();
            }
            scope = found.getMembers();
        }
        return Optional.ofNullable(found);
    }

    public static Optional<MethodTree> findMethod(Unit unit, ClassTree type, String method, int line) {
        MethodTree single = null;
        int count = 0;
        for (Tree member : type.getMembers()) {
            if (!(member instanceof MethodTree candidate) || !candidate.getName().contentEquals(method)) {
                continue;
            }
            count++;
            single = candidate;
            if (unit.startLine(candidate) <= line && line <= unit.endLine(candidate)) {
                return Optional.of(candidate);
            }
        }
        return count == 1 ? Optional.of(single) : Optional.empty();
    }
}
