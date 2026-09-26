package dtm.ide.index;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import dtm.stools.component.panels.editor.code.api.Range;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.*;

public final class JavaLocalScope {
    private static final int CACHE_LIMIT = 32;
    private static final Map<String, Parsed> CACHE = new LinkedHashMap<>(CACHE_LIMIT, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
            return size() > CACHE_LIMIT;
        }
    };
    private static final Object PARSE_LOCK = new Object();
    static final java.util.concurrent.atomic.AtomicLong PARSES = new java.util.concurrent.atomic.AtomicLong();
    private static JavaCompiler compiler;
    private static StandardJavaFileManager fileManager;

    public record Scope(String name, Range declaration, boolean onDeclaration, int startLine, int endLine, List<Range> usages) { }

    public enum Kind { LOCAL, PARAMETER, FIELD }

    public record Visible(String name, Kind kind) { }

    private record Declared(String name, Kind kind, int declarationLine, int startLine, int endLine) { }

    private record Parsed(List<Scope> scopes, List<Declared> declared) {
        static final Parsed EMPTY = new Parsed(List.of(), List.of());
    }

    private JavaLocalScope() { }

    public static Scope at(String source, int line, int col) {
        if (source == null || source.isEmpty() || line < 0 || col < 0) return null;
        for (Scope symbol : scopes(source)) {
            boolean declaration = contains(symbol.declaration(), line, col);
            if (declaration || symbol.usages().stream().anyMatch(r -> contains(r, line, col))) {
                return new Scope(symbol.name(), symbol.declaration(), declaration,
                        symbol.startLine(), symbol.endLine(), symbol.usages());
            }
        }
        return null;
    }

    private static boolean contains(Range range, int line, int col) {
        return range.start().line() == line && col >= range.start().col() && col < range.end().col();
    }

    public static List<Visible> visibleAt(String source, int line) {
        if (source == null || source.isEmpty() || line < 0) return List.of();
        Map<String, Visible> visible = new LinkedHashMap<>();
        List<Declared> declared = new ArrayList<>(parsed(source).declared());
        declared.sort(Comparator.comparingInt((Declared d) -> d.kind() == Kind.FIELD ? 1 : 0)
                .thenComparing(Comparator.comparingInt(Declared::declarationLine).reversed()));
        for (Declared candidate : declared) {
            boolean inScope = candidate.startLine() <= line && line <= candidate.endLine();
            boolean declaredBefore = candidate.declarationLine() < line;
            if (inScope && declaredBefore) {
                visible.putIfAbsent(candidate.name(), new Visible(candidate.name(), candidate.kind()));
            }
        }
        return List.copyOf(visible.values());
    }

    public static void warmUp() {
        scopes("class Warmup { void run() { int value = 0; } }");
    }

    public static void preload(String source) {
        if (source == null || source.isEmpty()) return;
        scopes(source);
    }

    private static List<Scope> scopes(String source) {
        return parsed(source).scopes();
    }

    private static Parsed parsed(String source) {
        synchronized (CACHE) {
            Parsed cached = CACHE.get(source);
            if (cached != null) return cached;
        }
        synchronized (PARSE_LOCK) {
            synchronized (CACHE) {
                Parsed cached = CACHE.get(source);
                if (cached != null) return cached;
            }
            Parsed parsed = parse(source);
            synchronized (CACHE) {
                CACHE.put(source, parsed);
            }
            return parsed;
        }
    }

    private static Parsed parse(String source) {
        PARSES.incrementAndGet();
        try {
            if (compiler == null) compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return Parsed.EMPTY;
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Buffer.java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source; }
            };
            if (fileManager == null) fileManager = compiler.getStandardFileManager(null, null, null);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none"), null, List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            // Broken syntax can change nesting. Never guess a binding in that case.
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                return Parsed.EMPTY;
            }
            Scanner scanner = new Scanner(source, unit, Trees.instance(task).getSourcePositions());
            scanner.scan(unit, null);
            return new Parsed(scanner.symbols.stream().map(Symbol::snapshot).toList(),
                    List.copyOf(scanner.declared));
        } catch (Exception | LinkageError unavailable) {
            // A host runtime without jdk.compiler still has semantic navigation through JDT LS.
            fileManager = null;
            return Parsed.EMPTY;
        }
    }

    private static final class Symbol {
        final String name;
        final Range declaration;
        final int startLine, endLine;
        final List<Range> uses = new ArrayList<>();
        Symbol(String name, Range declaration, int startLine, int endLine) {
            this.name = name; this.declaration = declaration;
            this.startLine = startLine; this.endLine = endLine;
        }
        Scope snapshot() { return new Scope(name, declaration, false, startLine, endLine, List.copyOf(uses)); }
    }

    private record Frame(Tree owner, Map<String, Symbol> bindings) {
        Frame(Tree owner) { this(owner, new HashMap<>()); }
    }

    private static final class Scanner extends TreeScanner<Void, Void> {
        final String code;
        final int[] lines;
        final CompilationUnitTree unit;
        final SourcePositions positions;
        final Deque<Frame> frames = new ArrayDeque<>();
        final List<Symbol> symbols = new ArrayList<>();
        final List<Declared> declared = new ArrayList<>();

        Scanner(String source, CompilationUnitTree unit, SourcePositions positions) {
            this.code = JavaLexicalSource.mask(source);
            this.lines = JavaLexicalSource.lineStarts(source);
            this.unit = unit; this.positions = positions;
        }
        int start(Tree tree) { return (int) positions.getStartPosition(unit, tree); }
        int end(Tree tree) { return (int) positions.getEndPosition(unit, tree); }
        Range range(int from, int to) { return JavaLexicalSource.rangeOf(lines, from, to); }

        @Override public Void visitClass(ClassTree tree, Void unused) {
            Frame frame = new Frame(tree);
            int classStart = JavaLexicalSource.lineOf(lines, Math.max(0, start(tree)));
            int classEnd = JavaLexicalSource.lineOf(lines, Math.max(0, end(tree) - 1));
            for (Tree member : tree.getMembers()) {
                if (member instanceof VariableTree field) {
                    frame.bindings.put(field.getName().toString(), null);
                    declared.add(new Declared(field.getName().toString(), Kind.FIELD,
                            classStart, classStart, classEnd));
                }
            }
            frames.push(frame);
            scan(tree.getMembers(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitMethod(MethodTree tree, Void unused) {
            frames.push(new Frame(tree));
            scan(tree.getParameters(), null); scan(tree.getBody(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitBlock(BlockTree tree, Void unused) {
            frames.push(new Frame(tree));
            scan(tree.getStatements(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitVariable(VariableTree tree, Void unused) {
            if (frames.isEmpty()) return null;
            Frame frame = frames.peek();
            if (!(frame.owner instanceof ClassTree)) {
                int from = tree.getType() == null ? start(tree) : end(tree.getType());
                int to = tree.getInitializer() == null ? end(tree) : start(tree.getInitializer());
                if (from < start(tree) || from >= to) from = start(tree);
                String name = tree.getName().toString();
                int declaration = -1;
                for (int[] span : JavaLexicalSource.occurrences(code, Set.of(name))) {
                    if (span[0] >= from && span[1] <= to) declaration = span[0];
                }
                if (declaration >= 0) {
                    Symbol symbol = new Symbol(name, range(declaration, declaration + name.length()),
                            JavaLexicalSource.lineOf(lines, Math.max(0, start(frame.owner))),
                            JavaLexicalSource.lineOf(lines, Math.max(0, end(frame.owner) - 1)));
                    frame.bindings.put(name, symbol);
                    symbols.add(symbol);
                    boolean parameter = frame.owner instanceof MethodTree
                            || frame.owner instanceof LambdaExpressionTree
                            || frame.owner instanceof CatchTree;
                    declared.add(new Declared(name, parameter ? Kind.PARAMETER : Kind.LOCAL,
                            symbol.declaration.start().line(), symbol.startLine, symbol.endLine));
                }
            }
            scan(tree.getInitializer(), null);
            return null;
        }
        @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
            String name = tree.getName().toString();
            for (Frame frame : frames) {
                if (frame.bindings.containsKey(name)) {
                    Symbol symbol = frame.bindings.get(name);
                    if (symbol != null && start(tree) >= 0) symbol.uses.add(range(start(tree), end(tree)));
                    break;
                }
                if (frame.owner instanceof ClassTree type
                        && (type.getExtendsClause() != null || type.getSimpleName().isEmpty())) break;
            }
            return null;
        }
        @Override public Void visitTypeCast(TypeCastTree tree, Void unused) {
            scan(tree.getExpression(), null);
            return null;
        }
        @Override public Void visitInstanceOf(InstanceOfTree tree, Void unused) {
            scan(tree.getExpression(), null);
            return null;
        }
        @Override public Void visitNewArray(NewArrayTree tree, Void unused) {
            scan(tree.getDimensions(), null); scan(tree.getInitializers(), null);
            return null;
        }
        @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            scan(tree.getExpression(), null);
            return null;
        }
        @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            if (tree.getMethodSelect() instanceof MemberSelectTree member) scan(member.getExpression(), null);
            scan(tree.getArguments(), null);
            return null;
        }
        @Override public Void visitNewClass(NewClassTree tree, Void unused) {
            scan(tree.getEnclosingExpression(), null); scan(tree.getArguments(), null);
            scan(tree.getClassBody(), null);
            return null;
        }
        @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            frames.push(new Frame(tree));
            scan(tree.getParameters(), null); scan(tree.getBody(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitForLoop(ForLoopTree tree, Void unused) {
            frames.push(new Frame(tree));
            scan(tree.getInitializer(), null); scan(tree.getCondition(), null);
            scan(tree.getUpdate(), null); scan(tree.getStatement(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
            scan(tree.getExpression(), null);
            frames.push(new Frame(tree));
            scan(tree.getVariable(), null); scan(tree.getStatement(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitCatch(CatchTree tree, Void unused) {
            frames.push(new Frame(tree));
            scan(tree.getParameter(), null); scan(tree.getBlock(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitTry(TryTree tree, Void unused) {
            frames.push(new Frame(tree));
            scan(tree.getResources(), null); scan(tree.getBlock(), null);
            frames.pop();
            scan(tree.getCatches(), null); scan(tree.getFinallyBlock(), null);
            return null;
        }
        @Override public Void visitSwitch(SwitchTree tree, Void unused) {
            scan(tree.getExpression(), null);
            frames.push(new Frame(tree));
            scan(tree.getCases(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
            scan(tree.getExpression(), null);
            frames.push(new Frame(tree));
            scan(tree.getCases(), null);
            frames.pop();
            return null;
        }
        @Override public Void visitCase(CaseTree tree, Void unused) {
            // Labels may contain type/pattern names, which are not local-variable uses.
            if (tree.getCaseKind() == CaseTree.CaseKind.RULE) frames.push(new Frame(tree));
            scan(tree.getStatements(), null);
            scan(tree.getBody(), null);
            if (tree.getCaseKind() == CaseTree.CaseKind.RULE) frames.pop();
            return null;
        }
        @Override public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            if (tree.getMode() != MemberReferenceTree.ReferenceMode.NEW) {
                scan(tree.getQualifierExpression(), null);
            }
            return null;
        }
        @Override public Void visitBindingPattern(BindingPatternTree tree, Void unused) {
            // Flow-dependent pattern bindings require JDT LS.
            return null;
        }
    }
}
