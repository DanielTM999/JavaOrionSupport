package dtm.ide.swingdesigner.form;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import dtm.ide.swingdesigner.source.JavaSourceTree;
import dtm.ide.swingdesigner.source.TypeNames;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class FormReader {

    public static final String CONTENT_ID = "#content";

    private static final Set<String> SLOT_SETTERS = Set.of("setViewportView", "setLeftComponent",
            "setRightComponent", "setTopComponent", "setBottomComponent", "setContentPane", "setJMenuBar",
            "setRowHeaderView", "setColumnHeaderView");
    private static final Set<String> CONTENT_FORWARDS = Set.of("add", "setLayout", "remove");

    public record Options(String className, List<String> designInit, boolean window, Predicate<String> drawable,
                          Set<String> attachMethods) {

        public Options {
            designInit = designInit == null ? List.of() : List.copyOf(designInit);
            drawable = drawable == null ? name -> false : drawable;
            attachMethods = attachMethods == null ? Set.of() : Set.copyOf(attachMethods);
        }

        public static Options of(String className, boolean window, Predicate<String> drawable) {
            return new Options(className, List.of(), window, drawable, Set.of());
        }

        public Options withDesignInit(List<String> hooks) {
            return new Options(className, hooks, window, drawable, attachMethods);
        }

        public Options withAttachMethods(Set<String> methods) {
            return new Options(className, designInit, window, drawable, methods);
        }
    }

    private FormReader() {
    }

    public static Optional<FormModel> read(String source, Options options) {
        if (source == null || options == null || options.className() == null) {
            return Optional.empty();
        }
        Optional<JavaSourceTree.Unit> unit = JavaSourceTree.parse(source);
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = JavaSourceTree.simpleNames(options.className());
        Optional<ClassTree> type = JavaSourceTree.findClass(unit.get().tree(), names);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Reader(unit.get(), type.get(), options, names).read());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final class Draft {
        final String id;
        final FormComponent.Kind kind;
        final String name;
        final String className;
        Span field = Span.NONE;
        Span declaration = Span.NONE;
        Span creation = Span.NONE;
        String creationText;
        String creationMethod;
        final List<FormCall> properties = new ArrayList<>();
        FormCall layout;
        String parentId;
        FormCall attach;
        int order = Integer.MAX_VALUE;
        final List<FormListener> listeners = new ArrayList<>();
        final List<FormCall> others = new ArrayList<>();
        int anchor = -1;
        String anchorMethod;
        String lockReason;

        Draft(String id, FormComponent.Kind kind, String name, String className) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.className = className;
        }

        FormComponent build() {
            return new FormComponent(id, kind, name, className, field, declaration, creation, creationText,
                    creationMethod, properties, layout, parentId, attach, order, listeners, others, anchor,
                    anchorMethod, lockReason);
        }
    }

    private static final class Reader {

        private final JavaSourceTree.Unit unit;
        private final ClassTree type;
        private final Options options;
        private final TypeNames typeNames;
        private final String source;
        private final Map<String, Draft> drafts = new LinkedHashMap<>();
        private final Map<String, String> fieldIds = new HashMap<>();
        private final List<FormModel.MethodInfo> methods = new ArrayList<>();
        private final Map<MethodTree, String> methodNames = new IdentityHashMap<>();
        private final Set<MethodTree> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<String> buildOrder = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private String contentOverride;
        private int order;
        private int thisReferences;
        private int plainReferences;
        private String indentUnit = "    ";

        Reader(JavaSourceTree.Unit unit, ClassTree type, Options options, List<String> names) {
            this.unit = unit;
            this.type = type;
            this.options = options;
            this.source = unit.source();
            this.typeNames = new TypeNames(unit, names);
        }

        FormModel read() {
            int classOpen = classOpen();
            int classBodyEnd = unit.end(type) - 1;
            String classIndent = indentAt(unit.start(type));
            String memberIndent = null;
            int fieldInsert = -1;
            Draft root = new Draft(FormModel.ROOT, FormComponent.Kind.ROOT, type.getSimpleName().toString(),
                    options.className());
            drafts.put(root.id, root);
            if (options.window()) {
                contentTarget();
            }
            for (Tree member : type.getMembers()) {
                if (unit.start(member) > unit.start(type)) {
                    memberIndent = indentAt(unit.start(member));
                    break;
                }
            }
            if (memberIndent == null || memberIndent.length() <= classIndent.length()) {
                memberIndent = classIndent + "    ";
            }
            if (memberIndent.startsWith(classIndent) && memberIndent.length() > classIndent.length()) {
                indentUnit = memberIndent.substring(classIndent.length());
            }
            for (Tree member : type.getMembers()) {
                if (member instanceof VariableTree variable) {
                    fieldInsert = Math.max(fieldInsert, unit.end(variable));
                    field(variable);
                } else if (member instanceof MethodTree method) {
                    methodInfo(method);
                }
            }
            if (fieldInsert < 0) {
                fieldInsert = classOpen + 1;
            }
            for (Tree member : type.getMembers()) {
                if (member instanceof MethodTree method && methodNames.get(method).equals("<init>")) {
                    process(method);
                }
            }
            for (String hook : options.designInit()) {
                for (Tree member : type.getMembers()) {
                    if (member instanceof MethodTree method && method.getName().contentEquals(hook)) {
                        process(method);
                    }
                }
            }
            Map<String, FormComponent> components = new LinkedHashMap<>();
            for (Draft draft : drafts.values()) {
                if (draft.kind == FormComponent.Kind.FIELD && !draft.creation.exists() && draft.lockReason == null) {
                    draft.lockReason = "Criado fora dos metodos de montagem";
                }
                if (draft.kind == FormComponent.Kind.LOCAL && !draft.creation.exists() && draft.lockReason == null) {
                    draft.lockReason = "Criacao nao reconhecida";
                }
                components.put(draft.id, draft.build());
            }
            return new FormModel(source, options.className(), options.window(), components, methods, buildOrder,
                    classOpen, classBodyEnd, fieldInsert, memberIndent, indentUnit,
                    thisReferences > plainReferences, typeNames, notes);
        }

        private int classOpen() {
            List<? extends Tree> members = type.getMembers();
            for (Tree member : members) {
                int start = unit.start(member);
                if (start > unit.start(type)) {
                    return source.lastIndexOf('{', start);
                }
            }
            return source.lastIndexOf('{', unit.end(type) - 2);
        }

        private void field(VariableTree variable) {
            if (variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                return;
            }
            String name = variable.getName().toString();
            String className = drawable(typeNames.ofType(variable.getType()));
            if (className == null) {
                return;
            }
            Draft draft = new Draft(name, FormComponent.Kind.FIELD, name, className);
            draft.field = span(variable);
            ExpressionTree initializer = variable.getInitializer();
            if (initializer instanceof NewClassTree creation && creation.getClassBody() == null) {
                draft.creation = span(variable);
                draft.creationText = unit.text(creation);
            } else if (initializer != null) {
                draft.lockReason = "Inicializado por " + unit.snippet(initializer);
            }
            drafts.put(name, draft);
            fieldIds.put(name, name);
        }

        private void methodInfo(MethodTree method) {
            String name = method.getName().toString();
            methodNames.put(method, name);
            List<String> parameters = new ArrayList<>();
            for (VariableTree parameter : method.getParameters()) {
                parameters.add(unit.text(parameter.getType()));
            }
            BlockTree body = method.getBody();
            int bodyStart = body == null ? -1 : unit.start(body) + 1;
            int bodyEnd = body == null ? -1 : unit.end(body) - 1;
            int last = bodyStart;
            String indent = null;
            if (body != null) {
                for (StatementTree statement : body.getStatements()) {
                    if (indent == null) {
                        indent = indentAt(unit.start(statement));
                    }
                    last = Math.max(last, unit.end(statement));
                }
            }
            if (indent == null) {
                indent = indentAt(unit.start(method)) + indentUnit;
            }
            methods.add(new FormModel.MethodInfo(name, parameters, span(method), bodyStart, bodyEnd,
                    name.equals("<init>"), last, indent));
        }

        private void process(MethodTree method) {
            if (method.getBody() == null || !visited.add(method)) {
                return;
            }
            String name = methodNames.get(method);
            buildOrder.add(name);
            Map<String, String> scope = new HashMap<>();
            for (VariableTree parameter : method.getParameters()) {
                scope.put(parameter.getName().toString(), null);
            }
            for (StatementTree statement : method.getBody().getStatements()) {
                statement(statement, name, scope);
            }
        }

        private void statement(StatementTree statement, String method, Map<String, String> scope) {
            if (statement instanceof VariableTree variable) {
                local(variable, method, scope);
                return;
            }
            if (statement instanceof ExpressionStatementTree expressionStatement) {
                ExpressionTree expression = expressionStatement.getExpression();
                if (expression instanceof AssignmentTree assignment) {
                    assignment(assignment, statement, method, scope);
                    return;
                }
                if (expression instanceof MethodInvocationTree invocation) {
                    invocation(invocation, statement, method, scope);
                }
                return;
            }
            nested(statement, scope);
        }

        private void local(VariableTree variable, String method, Map<String, String> scope) {
            String name = variable.getName().toString();
            ExpressionTree initializer = variable.getInitializer();
            List<String> candidates = typeNames.ofType(variable.getType());
            if (candidates.isEmpty() && initializer instanceof NewClassTree creation) {
                candidates = typeNames.ofType(creation.getIdentifier());
            }
            String className = drawable(candidates);
            if (className == null) {
                scope.put(name, null);
                return;
            }
            String id = uniqueId(name, method);
            Draft draft = new Draft(id, FormComponent.Kind.LOCAL, name, className);
            draft.declaration = span(variable);
            if (initializer instanceof NewClassTree creation && creation.getClassBody() == null) {
                draft.creation = span(variable);
                draft.creationText = unit.text(creation);
                draft.creationMethod = method;
            } else if (initializer != null) {
                draft.lockReason = "Criado por " + unit.snippet(initializer);
            }
            touch(draft, variable, method);
            drafts.put(id, draft);
            scope.put(name, id);
        }

        private void assignment(AssignmentTree assignment, StatementTree statement, String method,
                                Map<String, String> scope) {
            String id = reference(assignment.getVariable(), scope);
            if (id == null) {
                return;
            }
            Draft draft = drafts.get(id);
            ExpressionTree value = assignment.getExpression();
            if (value instanceof NewClassTree creation && creation.getClassBody() == null) {
                draft.creation = span(statement);
                draft.creationText = unit.text(creation);
                draft.creationMethod = method;
                draft.lockReason = null;
            } else {
                draft.lockReason = "Atribuido por " + unit.snippet(value);
            }
            touch(draft, statement, method);
        }

        private void invocation(MethodInvocationTree invocation, StatementTree statement, String method,
                                Map<String, String> scope) {
            List<MethodInvocationTree> links = new ArrayList<>();
            ExpressionTree current = invocation;
            while (current instanceof MethodInvocationTree call) {
                links.addFirst(call);
                if (call.getMethodSelect() instanceof MemberSelectTree select) {
                    current = select.getExpression();
                } else {
                    current = null;
                    break;
                }
            }
            if (links.isEmpty()) {
                return;
            }
            MethodInvocationTree first = links.getFirst();
            String firstName = name(first);
            boolean implicit = current == null;
            if (implicit && links.size() == 1 && firstName.equals("this")) {
                constructor(first.getArguments().size()).ifPresent(this::process);
                return;
            }
            if (implicit && firstName.equals("super")) {
                return;
            }
            if (links.size() == 1 && (implicit || isThis(current))) {
                Optional<MethodTree> declared = declared(firstName, first.getArguments().size());
                if (declared.isPresent()) {
                    process(declared.get());
                    return;
                }
            }
            String receiver;
            int startLink = 0;
            if (implicit || isThis(current)) {
                receiver = FormModel.ROOT;
                if (options.window() && firstName.equals("getContentPane") && first.getArguments().isEmpty()) {
                    receiver = contentTarget();
                    startLink = 1;
                }
            } else {
                receiver = reference(current, scope);
                if (receiver == null) {
                    return;
                }
            }
            for (int i = startLink; i < links.size(); i++) {
                link(links, i, receiver, statement, method, scope);
            }
        }

        private void link(List<MethodInvocationTree> links, int index, String receiver, StatementTree statement,
                          String method, Map<String, String> scope) {
            MethodInvocationTree call = links.get(index);
            String name = name(call);
            String target = receiver;
            if (FormModel.ROOT.equals(receiver) && options.window() && CONTENT_FORWARDS.contains(name)) {
                target = contentTarget();
            }
            Draft owner = drafts.get(target);
            FormCall formCall = formCall(call, links.size(), statement, method);
            touch(owner, statement, method);
            if (isAttach(name)) {
                for (ExpressionTree argument : call.getArguments()) {
                    String child = reference(argument, scope);
                    if (child != null && !child.equals(target)) {
                        Draft attached = drafts.get(child);
                        attached.parentId = target;
                        attached.attach = formCall;
                        attached.order = order++;
                        touch(attached, statement, method);
                        if (name.equals("setContentPane") && FormModel.ROOT.equals(target)) {
                            contentOverride = child;
                        }
                        return;
                    }
                }
            }
            if (name.equals("setLayout")) {
                owner.layout = formCall;
            } else if (name.startsWith("add") && name.endsWith("Listener") && call.getArguments().size() == 1) {
                owner.listeners.add(listener(call, formCall, name));
            } else if (name.startsWith("set") && name.length() > 3) {
                owner.properties.add(formCall);
            } else {
                owner.others.add(formCall);
            }
        }

        private FormCall formCall(MethodInvocationTree call, int chainLength, StatementTree statement, String method) {
            int selectEnd = unit.end(call.getMethodSelect());
            int open = source.indexOf('(', selectEnd);
            int close = unit.end(call) - 1;
            List<String> arguments = new ArrayList<>();
            for (ExpressionTree argument : call.getArguments()) {
                arguments.add(unit.text(argument));
            }
            Span removal;
            if (chainLength == 1) {
                removal = span(statement);
            } else if (call.getMethodSelect() instanceof MemberSelectTree select) {
                removal = new Span(unit.end(select.getExpression()), unit.end(call));
            } else {
                removal = Span.NONE;
            }
            return new FormCall(name(call), arguments, span(call), new Span(open + 1, close), span(statement),
                    removal, method);
        }

        private FormListener listener(MethodInvocationTree call, FormCall formCall, String addMethod) {
            ExpressionTree argument = call.getArguments().getFirst();
            if (argument instanceof MemberReferenceTree reference) {
                if (isThis(reference.getQualifierExpression())) {
                    return new FormListener(addMethod, FormListener.Style.METHOD_REF, reference.getName().toString(),
                            null, List.of(), span(reference), formCall, -1);
                }
                return new FormListener(addMethod, FormListener.Style.OTHER, null, null, List.of(), span(reference),
                        formCall, -1);
            }
            if (argument instanceof LambdaExpressionTree lambda) {
                return new FormListener(addMethod, FormListener.Style.LAMBDA, delegate(lambda.getBody()), null,
                        List.of(), span(lambda.getBody()), formCall, -1);
            }
            if (argument instanceof NewClassTree creation && creation.getClassBody() != null) {
                List<FormListener.AnonymousMethod> anonymous = new ArrayList<>();
                for (Tree member : creation.getClassBody().getMembers()) {
                    if (member instanceof MethodTree method && !method.getName().contentEquals("<init>")) {
                        anonymous.add(new FormListener.AnonymousMethod(method.getName().toString(),
                                delegate(method.getBody()), span(method)));
                    }
                }
                int end = source.lastIndexOf('}', unit.end(creation) - 1);
                return new FormListener(addMethod, FormListener.Style.ANONYMOUS, null,
                        unit.text(creation.getIdentifier()), anonymous, span(creation), formCall, end);
            }
            return new FormListener(addMethod, FormListener.Style.OTHER, null, null, List.of(), span(argument),
                    formCall, -1);
        }

        private String delegate(Tree body) {
            Tree single = body;
            if (body instanceof BlockTree block) {
                if (block.getStatements().size() != 1) {
                    return null;
                }
                single = block.getStatements().getFirst();
            }
            if (single instanceof ExpressionStatementTree statement) {
                single = statement.getExpression();
            }
            if (single instanceof MethodInvocationTree invocation) {
                ExpressionTree select = invocation.getMethodSelect();
                if (select instanceof IdentifierTree identifier && !identifier.getName().contentEquals("super")) {
                    return identifier.getName().toString();
                }
                if (select instanceof MemberSelectTree member && isThis(member.getExpression())) {
                    return member.getIdentifier().toString();
                }
            }
            return null;
        }

        private void nested(StatementTree statement, Map<String, String> scope) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitAssignment(AssignmentTree assignment, Void unused) {
                    String id = reference(assignment.getVariable(), scope);
                    if (id != null && assignment.getExpression() instanceof NewClassTree) {
                        Draft draft = drafts.get(id);
                        if (!draft.creation.exists()) {
                            draft.lockReason = "Criado dentro de um bloco (" + unit.snippet(statement) + ")";
                        }
                    }
                    return super.visitAssignment(assignment, unused);
                }
            }.scan(statement, null);
        }

        private String reference(ExpressionTree expression, Map<String, String> scope) {
            if (expression instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                if (scope.containsKey(name)) {
                    return scope.get(name);
                }
                String id = fieldIds.get(name);
                if (id != null) {
                    plainReferences++;
                }
                return id;
            }
            if (expression instanceof MemberSelectTree select && isThis(select.getExpression())) {
                String id = fieldIds.get(select.getIdentifier().toString());
                if (id != null) {
                    thisReferences++;
                }
                return id;
            }
            return null;
        }

        private String contentTarget() {
            if (contentOverride != null) {
                return contentOverride;
            }
            Draft content = drafts.get(CONTENT_ID);
            if (content == null) {
                content = new Draft(CONTENT_ID, FormComponent.Kind.CONTENT, "contentPane", "java.awt.Container");
                content.parentId = FormModel.ROOT;
                content.order = -1;
                drafts.put(CONTENT_ID, content);
            }
            return CONTENT_ID;
        }

        private boolean isAttach(String name) {
            return name.equals("add") || name.equals("addTab") || name.equals("insertTab")
                    || SLOT_SETTERS.contains(name) || options.attachMethods().contains(name)
                    || (name.startsWith("add") && !name.endsWith("Listener"));
        }

        private Optional<MethodTree> declared(String name, int arguments) {
            for (Tree member : type.getMembers()) {
                if (member instanceof MethodTree method && method.getName().contentEquals(name)
                        && method.getParameters().size() == arguments && method.getBody() != null) {
                    return Optional.of(method);
                }
            }
            return Optional.empty();
        }

        private Optional<MethodTree> constructor(int arguments) {
            return declared("<init>", arguments);
        }

        private String uniqueId(String name, String method) {
            if (!drafts.containsKey(name) && !fieldIds.containsKey(name)) {
                return name;
            }
            String base = name + "@" + method;
            String id = base;
            int counter = 2;
            while (drafts.containsKey(id)) {
                id = base + counter++;
            }
            return id;
        }

        private String drawable(List<String> candidates) {
            for (String candidate : candidates) {
                if (options.drawable().test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private void touch(Draft draft, Tree tree, String method) {
            if (draft == null) {
                return;
            }
            draft.anchor = unit.end(tree);
            draft.anchorMethod = method;
        }

        private Span span(Tree tree) {
            return new Span(unit.start(tree), unit.end(tree));
        }

        private String indentAt(int offset) {
            int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
            int cursor = lineStart;
            while (cursor < source.length() && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) {
                cursor++;
            }
            return source.substring(lineStart, cursor);
        }

        private static boolean isThis(ExpressionTree expression) {
            return expression instanceof IdentifierTree identifier && identifier.getName().contentEquals("this");
        }

        private static String name(MethodInvocationTree call) {
            ExpressionTree select = call.getMethodSelect();
            if (select instanceof IdentifierTree identifier) {
                return identifier.getName().toString();
            }
            if (select instanceof MemberSelectTree member) {
                return member.getIdentifier().toString();
            }
            return "";
        }
    }
}
