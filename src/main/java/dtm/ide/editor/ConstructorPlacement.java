package dtm.ide.editor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import dtm.stools.component.panels.editor.code.api.Position;
import javax.tools.SimpleJavaFileObject;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.List;

public final class ConstructorPlacement {
    private ConstructorPlacement() { }
    public static Position afterFields(String source, int line, int column) {
        Position fallback = new Position(line, column);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || source == null) return fallback;
        int cursor = 0;
        for (int n = 0; n < line && cursor < source.length(); n++) {
            int next = source.indexOf('\n', cursor); cursor = next < 0 ? source.length() : next + 1;
        }
        final int caret = Math.min(source.length(), cursor + column);
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, ignored -> { },
                    List.of("-proc:none"), null, List.of(input));
            var unit = task.parse().iterator().next();
            var positions = Trees.instance(task).getSourcePositions();
            long[] best = {Long.MAX_VALUE, -1};
            new TreeScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree type, Void unused) {
                    long start = positions.getStartPosition(unit, type), end = positions.getEndPosition(unit, type);
                    if (start <= caret && caret <= end && end - start < best[0]) {
                        long anchor = -1;
                        for (var member : type.getMembers()) {
                            if (member instanceof VariableTree) anchor = Math.max(anchor, positions.getEndPosition(unit, member));
                        }
                        if (anchor < 0) anchor = source.indexOf('{', (int) start) + 1;
                        best[0] = end - start; best[1] = anchor;
                    }
                    return super.visitClass(type, unused);
                }
            }.scan(unit, null);
            if (best[1] < 0) return fallback;
            int target = Math.min(source.length(), (int) best[1]), targetLine = 0, start = 0;
            for (int i = 0; i < target; i++) if (source.charAt(i) == '\n') { targetLine++; start = i + 1; }
            return new Position(targetLine, target - start);
        } catch (Exception ignored) { return fallback; }
    }
}
