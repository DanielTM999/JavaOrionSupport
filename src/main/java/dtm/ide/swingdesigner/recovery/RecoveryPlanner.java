package dtm.ide.swingdesigner.recovery;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import dtm.ide.swingdesigner.source.JavaSourceTree;
import dtm.ide.swingdesigner.source.TypeNames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RecoveryPlanner {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private RecoveryPlanner() {
    }

    public record Plan(String method, int failureLine, List<ObjectNode> statements, List<ObjectNode> priorLocals) {

        public Plan {
            statements = List.copyOf(statements);
            priorLocals = List.copyOf(priorLocals);
        }

        public long executable() {
            return statements.stream().filter(statement -> !"unsupported".equals(statement.path("s").asText()))
                    .count();
        }

        public long unsupported() {
            return statements.size() - executable();
        }
    }

    public static Optional<Plan> plan(String source, String className, String method, int failureLine) {
        if (source == null || className == null || method == null || failureLine <= 0) {
            return Optional.empty();
        }
        Optional<JavaSourceTree.Unit> parsed = JavaSourceTree.parse(source);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        JavaSourceTree.Unit unit = parsed.get();
        List<String> names = simpleNames(className);
        Optional<ClassTree> type = JavaSourceTree.findClass(unit.tree(), names);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        Optional<MethodTree> target = JavaSourceTree.findMethod(unit, type.get(), method, failureLine);
        if (target.isEmpty() || target.get().getBody() == null) {
            return Optional.empty();
        }
        List<? extends StatementTree> statements = target.get().getBody().getStatements();
        int failing = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (unit.startLine(statements.get(i)) <= failureLine && failureLine <= unit.endLine(statements.get(i))) {
                failing = i;
                break;
            }
        }
        if (failing < 0) {
            return Optional.empty();
        }
        Translator translator = new Translator(unit, names);
        List<ObjectNode> prior = new ArrayList<>();
        for (VariableTree parameter : target.get().getParameters()) {
            prior.add(translator.prior(parameter));
        }
        for (int i = 0; i < failing; i++) {
            if (statements.get(i) instanceof VariableTree variable) {
                prior.add(translator.prior(variable));
            }
        }
        List<ObjectNode> plan = new ArrayList<>();
        if (statements.get(failing) instanceof VariableTree variable) {
            plan.add(translator.synthetic(variable));
        }
        for (int i = failing + 1; i < statements.size(); i++) {
            plan.add(translator.statement(statements.get(i)));
        }
        return Optional.of(new Plan(method, failureLine, plan, prior));
    }

    public static List<ObjectNode> translate(String fileSource, String className, List<String> statements,
                                             java.util.Collection<String> extraImports) {
        if (statements == null || statements.isEmpty()) {
            return List.of();
        }
        StringBuilder synthetic = new StringBuilder();
        if (fileSource != null) {
            for (String line : fileSource.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                    synthetic.append(trimmed).append('\n');
                }
            }
        }
        if (extraImports != null) {
            for (String imported : extraImports) {
                synthetic.append("import ").append(imported).append(";\n");
            }
        }
        synthetic.append("class LiveDesignerStatements {\nvoid run() {\n");
        for (String statement : statements) {
            synthetic.append(statement).append('\n');
        }
        synthetic.append("}\n}\n");
        Optional<JavaSourceTree.Unit> parsed = JavaSourceTree.parse(synthetic.toString());
        if (parsed.isEmpty()) {
            return List.of();
        }
        Optional<ClassTree> type = JavaSourceTree.findClass(parsed.get().tree(), List.of("LiveDesignerStatements"));
        if (type.isEmpty()) {
            return List.of();
        }
        Optional<MethodTree> method = JavaSourceTree.findMethod(parsed.get(), type.get(), "run", 1);
        if (method.isEmpty() || method.get().getBody() == null) {
            return List.of();
        }
        Translator translator = new Translator(parsed.get(), simpleNames(className));
        List<ObjectNode> translated = new ArrayList<>();
        for (StatementTree statement : method.get().getBody().getStatements()) {
            translated.add(translator.statement(statement));
        }
        return translated;
    }

    static List<String> simpleNames(String className) {
        return JavaSourceTree.simpleNames(className);
    }

    private static final class Translator {

        private final JavaSourceTree.Unit unit;
        private final TypeNames typeNames;
        private final Set<String> locals = new HashSet<>();

        Translator(JavaSourceTree.Unit unit, List<String> outerNames) {
            this.unit = unit;
            this.typeNames = new TypeNames(unit, outerNames);
        }

        ObjectNode prior(VariableTree variable) {
            locals.add(variable.getName().toString());
            ObjectNode node = NODES.objectNode();
            node.put("name", variable.getName().toString());
            node.set("type", types(variable.getType()));
            return node;
        }

        ObjectNode synthetic(VariableTree variable) {
            locals.add(variable.getName().toString());
            ObjectNode node = base("local", variable);
            node.put("name", variable.getName().toString());
            node.set("type", types(variable.getType()));
            node.put("synthetic", true);
            return node;
        }

        ObjectNode statement(StatementTree statement) {
            try {
                if (statement instanceof VariableTree variable) {
                    ObjectNode node = base("local", variable);
                    node.put("name", variable.getName().toString());
                    node.set("type", types(variable.getType()));
                    if (variable.getInitializer() != null) {
                        try {
                            node.set("init", expression(variable.getInitializer()));
                        } catch (Unsupported unsupported) {
                            node.put("synthetic", true);
                        }
                    }
                    locals.add(variable.getName().toString());
                    return node;
                }
                if (statement instanceof ExpressionStatementTree expressionStatement) {
                    ExpressionTree expression = expressionStatement.getExpression();
                    if (expression instanceof AssignmentTree assignment) {
                        ObjectNode node = base("assign", statement);
                        node.set("target", target(assignment.getVariable()));
                        node.set("value", expression(assignment.getExpression()));
                        return node;
                    }
                    if (expression instanceof MethodInvocationTree || expression instanceof NewClassTree) {
                        ObjectNode node = base("expr", statement);
                        node.set("expr", expression(expression));
                        return node;
                    }
                }
            } catch (Unsupported ignored) {
            }
            return base("unsupported", statement);
        }

        private ObjectNode base(String kind, Tree tree) {
            ObjectNode node = NODES.objectNode();
            node.put("s", kind);
            node.put("line", unit.startLine(tree));
            node.put("text", unit.snippet(tree));
            return node;
        }

        private ObjectNode target(ExpressionTree variable) {
            ObjectNode node = NODES.objectNode();
            if (variable instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                node.put(locals.contains(name) ? "local" : "field", name);
                return node;
            }
            if (variable instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree owner
                    && owner.getName().contentEquals("this")) {
                node.put("field", select.getIdentifier().toString());
                return node;
            }
            throw new Unsupported();
        }

        private ObjectNode expression(ExpressionTree tree) {
            ObjectNode node = NODES.objectNode();
            if (tree instanceof ParenthesizedTree parenthesized) {
                return expression(parenthesized.getExpression());
            }
            if (tree instanceof LiteralTree literal) {
                node.put("e", "lit");
                literal(node, literal);
                return node;
            }
            if (tree instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                if (name.equals("this")) {
                    node.put("e", "this");
                    return node;
                }
                if (name.equals("super")) {
                    throw new Unsupported();
                }
                node.put("e", "name");
                node.put("name", name);
                if (!locals.contains(name) && Character.isUpperCase(name.charAt(0))) {
                    node.set("classes", candidates(name));
                }
                return node;
            }
            if (tree instanceof MemberSelectTree select) {
                Optional<String> qualified = typeNames.qualifiedClass(select, locals);
                if (qualified.isPresent()) {
                    node.put("e", "name");
                    node.put("name", qualified.get());
                    ArrayNode classes = node.putArray("classes");
                    classes.add(qualified.get());
                    return node;
                }
                node.put("e", "select");
                node.set("target", expression(select.getExpression()));
                node.put("name", select.getIdentifier().toString());
                return node;
            }
            if (tree instanceof MethodInvocationTree invocation) {
                if (!invocation.getTypeArguments().isEmpty()) {
                    throw new Unsupported();
                }
                node.put("e", "call");
                ExpressionTree select = invocation.getMethodSelect();
                if (select instanceof IdentifierTree identifier) {
                    if (identifier.getName().contentEquals("super") || identifier.getName().contentEquals("this")) {
                        throw new Unsupported();
                    }
                    node.putNull("target");
                    node.put("name", identifier.getName().toString());
                } else if (select instanceof MemberSelectTree member) {
                    if (member.getExpression() instanceof IdentifierTree owner
                            && owner.getName().contentEquals("super")) {
                        throw new Unsupported();
                    }
                    node.set("target", expression(member.getExpression()));
                    node.put("name", member.getIdentifier().toString());
                } else {
                    throw new Unsupported();
                }
                ArrayNode arguments = node.putArray("args");
                for (ExpressionTree argument : invocation.getArguments()) {
                    arguments.add(expression(argument));
                }
                return node;
            }
            if (tree instanceof NewClassTree creation) {
                if (creation.getClassBody() != null || creation.getEnclosingExpression() != null) {
                    throw new Unsupported();
                }
                node.put("e", "new");
                node.set("type", types(creation.getIdentifier()));
                ArrayNode arguments = node.putArray("args");
                for (ExpressionTree argument : creation.getArguments()) {
                    arguments.add(expression(argument));
                }
                return node;
            }
            if (tree instanceof TypeCastTree cast) {
                node.put("e", "cast");
                node.set("x", expression(cast.getExpression()));
                return node;
            }
            if (tree instanceof UnaryTree unary) {
                String op = switch (unary.getKind()) {
                    case UNARY_MINUS -> "-";
                    case LOGICAL_COMPLEMENT -> "!";
                    case UNARY_PLUS -> null;
                    default -> throw new Unsupported();
                };
                if (op == null) {
                    return expression(unary.getExpression());
                }
                node.put("e", "unary");
                node.put("op", op);
                node.set("x", expression(unary.getExpression()));
                return node;
            }
            if (tree instanceof BinaryTree binary) {
                String op = switch (binary.getKind()) {
                    case PLUS -> "+";
                    case MINUS -> "-";
                    case MULTIPLY -> "*";
                    case DIVIDE -> "/";
                    default -> throw new Unsupported();
                };
                node.put("e", "binary");
                node.put("op", op);
                node.set("l", expression(binary.getLeftOperand()));
                node.set("r", expression(binary.getRightOperand()));
                return node;
            }
            throw new Unsupported();
        }

        private void literal(ObjectNode node, LiteralTree literal) {
            Object value = literal.getValue();
            switch (literal.getKind()) {
                case INT_LITERAL -> {
                    node.put("v", ((Number) value).intValue());
                    node.put("t", "int");
                }
                case LONG_LITERAL -> {
                    node.put("v", ((Number) value).longValue());
                    node.put("t", "long");
                }
                case FLOAT_LITERAL -> {
                    node.put("v", ((Number) value).doubleValue());
                    node.put("t", "float");
                }
                case DOUBLE_LITERAL -> {
                    node.put("v", ((Number) value).doubleValue());
                    node.put("t", "double");
                }
                case BOOLEAN_LITERAL -> node.put("v", (Boolean) value);
                case CHAR_LITERAL -> {
                    node.put("v", String.valueOf(value));
                    node.put("t", "char");
                }
                case NULL_LITERAL -> node.putNull("v");
                default -> node.put("v", String.valueOf(value));
            }
        }

        private ArrayNode types(Tree type) {
            ArrayNode array = NODES.arrayNode();
            typeNames.ofType(type).forEach(array::add);
            return array;
        }

        private ArrayNode candidates(String name) {
            ArrayNode array = NODES.arrayNode();
            typeNames.candidates(name).forEach(array::add);
            return array;
        }
    }

    private static final class Unsupported extends RuntimeException {
        Unsupported() {
            super(null, null, false, false);
        }
    }
}
