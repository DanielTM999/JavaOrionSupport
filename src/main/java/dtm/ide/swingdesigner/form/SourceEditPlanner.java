package dtm.ide.swingdesigner.form;

import dtm.ide.editor.JavaImportInserter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SourceEditPlanner {

    public record Result(String text, int focus, List<String> notes, List<String> live, Set<String> imports) {

        public Result {
            notes = notes == null ? List.of() : List.copyOf(notes);
            live = live == null ? List.of() : List.copyOf(live);
            imports = imports == null ? Set.of() : Set.copyOf(imports);
        }
    }

    public record NewComponent(String parentId,
                               String className,
                               String name,
                               String arguments,
                               List<Map.Entry<String, String>> properties,
                               String attachCall,
                               int index) {

        public NewComponent {
            properties = properties == null ? List.of() : List.copyOf(properties);
        }
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    private record Edit(int start, int end, String text, int focus) {
    }

    private final FormModel model;
    private final String source;
    private final String nl;
    private final List<Edit> edits = new ArrayList<>();
    private final Set<String> imports = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> pendingInit = new ArrayList<>();
    private final List<String> live = new ArrayList<>();
    private int pendingFocus = -1;

    private SourceEditPlanner(FormModel model) {
        this.model = model;
        this.source = model.source();
        this.nl = source.contains("\r\n") ? "\r\n" : "\n";
    }

    public static Result setProperty(FormModel model, String componentId, String setter, String expression,
                                     Set<String> imports) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        planner.imports.addAll(imports);
        FormComponent component = planner.editable(componentId);
        Optional<FormCall> existing = component.property(setter);
        if (existing.isPresent()) {
            Span arguments = existing.get().argumentsSpan();
            planner.edits.add(new Edit(arguments.start(), arguments.end(), expression, 0));
        } else {
            planner.appendStatements(component, List.of(planner.ref(component) + setter + "(" + expression + ");"));
        }
        planner.live.add(planner.ref(component) + setter + "(" + expression + ");");
        return planner.finish();
    }

    public static Result resetProperty(FormModel model, String componentId, String setter) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        FormComponent component = planner.editable(componentId);
        for (FormCall call : component.properties()) {
            if (call.method().equals(setter)) {
                planner.removeCall(call);
            }
        }
        if (planner.edits.isEmpty()) {
            throw new Rejected("A propriedade nao esta definida no codigo");
        }
        return planner.finish();
    }

    public static Result addComponent(FormModel model, NewComponent spec, Set<String> imports) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        planner.imports.addAll(imports);
        if (model.hasMember(spec.name())) {
            throw new Rejected("Ja existe um membro chamado " + spec.name());
        }
        FormComponent parent = model.component(spec.parentId())
                .orElseThrow(() -> new Rejected("Container nao encontrado no codigo"));
        if (parent.locked() && parent.kind() == FormComponent.Kind.LOCAL) {
            throw new Rejected("O container foi criado por codigo que o designer nao edita: " + parent.lockReason());
        }
        String type = planner.use(spec.className());
        String self = model.thisPrefix() ? "this." + spec.name() : spec.name();
        planner.edits.add(new Edit(model.fieldInsert(), model.fieldInsert(),
                planner.nl + model.memberIndent() + "private " + type + " " + spec.name() + ";", -1));
        List<String> lines = new ArrayList<>();
        lines.add(self + " = new " + type + "(" + (spec.arguments() == null ? "" : spec.arguments()) + ");");
        for (Map.Entry<String, String> property : spec.properties()) {
            lines.add(self + "." + property.getKey() + "(" + property.getValue() + ");");
        }
        lines.add(planner.ref(parent) + spec.attachCall() + ";");
        planner.placeChildStatements(parent, null, spec.index(), lines, true);
        for (String line : lines) {
            planner.live.add(line.startsWith("this." + spec.name()) ? line.substring(5) : line);
        }
        return planner.finish();
    }

    public static Result removeComponent(FormModel model, String componentId) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        FormComponent component = planner.editable(componentId);
        if (component.isRoot() || component.kind() == FormComponent.Kind.CONTENT) {
            throw new Rejected("A raiz da tela nao pode ser removida");
        }
        List<FormComponent> removed = new ArrayList<>();
        planner.collect(component, removed);
        List<Span> spans = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (FormComponent item : removed) {
            names.add(item.name());
            if (item.field().exists()) {
                if (item.field().text(planner.source).trim().endsWith(";")
                        && countDeclared(planner.source, item.field()) == 1) {
                    spans.add(planner.lines(item.field()));
                } else {
                    planner.notes.add("Declaracao compartilhada de " + item.name() + " mantida");
                }
            }
            if (item.declaration().exists()) {
                spans.add(planner.lines(item.declaration()));
            }
            if (item.creation().exists()) {
                spans.add(planner.lines(item.creation()));
            }
            for (FormCall call : item.properties()) {
                planner.callSpan(call).ifPresent(spans::add);
            }
            if (item.layout() != null) {
                planner.callSpan(item.layout()).ifPresent(spans::add);
            }
            if (item.attach() != null) {
                planner.callSpan(item.attach()).ifPresent(spans::add);
            }
            for (FormListener listener : item.listeners()) {
                planner.callSpan(listener.call()).ifPresent(spans::add);
                listener.handlerFor(listener.methods().isEmpty() ? "" : listener.methods().getFirst().name())
                        .ifPresent(handler -> planner.notes.add("Metodo " + handler + " mantido"));
                if (listener.style() == FormListener.Style.METHOD_REF && listener.reference() != null) {
                    planner.notes.add("Metodo " + listener.reference() + " mantido");
                }
            }
            for (FormCall call : item.others()) {
                planner.callSpan(call).ifPresent(spans::add);
            }
        }
        for (FormComponent other : model.components()) {
            if (removed.contains(other)) {
                continue;
            }
            for (FormCall call : other.properties()) {
                if (mentions(call, names)) {
                    planner.callSpan(call).ifPresent(spans::add);
                }
            }
            for (FormCall call : other.others()) {
                if (mentions(call, names)) {
                    planner.callSpan(call).ifPresent(spans::add);
                }
            }
        }
        for (Span span : merge(spans)) {
            planner.edits.add(new Edit(span.start(), span.end(), "", -1));
        }
        return planner.finish();
    }

    public static Result moveComponent(FormModel model, String componentId, String parentId, int index,
                                       String attachCall, Set<String> imports) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        planner.imports.addAll(imports);
        FormComponent component = planner.editable(componentId);
        FormComponent parent = model.component(parentId)
                .orElseThrow(() -> new Rejected("Container nao encontrado no codigo"));
        if (component.attach() == null || component.attach().chained()) {
            throw new Rejected("A inclusao de " + component.name() + " nao esta num comando proprio");
        }
        if (isAncestor(model, componentId, parentId)) {
            throw new Rejected("Um componente nao pode ir para dentro dele mesmo");
        }
        Span old = planner.lines(component.attach().statement());
        planner.edits.add(new Edit(old.start(), old.end(), "", -1));
        planner.placeChildStatements(parent, component, index, List.of(planner.ref(parent) + attachCall + ";"),
                true);
        planner.live.add(planner.ref(parent) + attachCall + ";");
        return planner.finish();
    }

    public static Result setConstraints(FormModel model, String componentId, String constraints,
                                        Set<String> imports) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        planner.imports.addAll(imports);
        FormComponent component = planner.editable(componentId);
        planner.rewriteConstraints(component, constraints);
        return planner.finish();
    }

    public static Result setLayout(FormModel model, String containerId, String layout,
                                   Map<String, String> childConstraints, Set<String> imports) {
        return setLayout(model, containerId, layout, childConstraints, Map.of(), imports);
    }

    public static Result setLayout(FormModel model, String containerId, String layout,
                                   Map<String, String> childConstraints, Map<String, String> childBounds,
                                   Set<String> imports) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        planner.imports.addAll(imports);
        FormComponent container = planner.editable(containerId);
        planner.live.add(planner.ref(container) + "setLayout(" + layout + ");");
        if (container.layout() != null) {
            Span arguments = container.layout().argumentsSpan();
            planner.edits.add(new Edit(arguments.start(), arguments.end(), layout, 0));
        } else {
            String statement = planner.ref(container) + "setLayout(" + layout + ");";
            Optional<FormComponent> first = model.children(containerId).stream()
                    .filter(child -> child.attach() != null)
                    .min(Comparator.comparingInt(child -> child.attach().statement().start()));
            if (first.isPresent()) {
                planner.insertBefore(first.get().attach().statement().start(), List.of(statement), true);
            } else {
                planner.appendStatements(container, List.of(statement));
            }
        }
        if (childConstraints != null) {
            for (Map.Entry<String, String> entry : childConstraints.entrySet()) {
                model.component(entry.getKey()).ifPresent(child -> planner.rewriteConstraints(child, entry.getValue()));
            }
        }
        if (childBounds != null) {
            for (Map.Entry<String, String> entry : childBounds.entrySet()) {
                model.component(entry.getKey()).filter(child -> !child.locked()).ifPresent(child -> {
                    Optional<FormCall> existing = child.property("setBounds");
                    if (existing.isPresent()) {
                        Span arguments = existing.get().argumentsSpan();
                        planner.edits.add(new Edit(arguments.start(), arguments.end(), entry.getValue(), -1));
                    } else {
                        planner.appendStatements(child, List.of(planner.ref(child) + "setBounds(" + entry.getValue()
                                + ");"));
                    }
                    planner.live.add(planner.ref(child) + "setBounds(" + entry.getValue() + ");");
                });
            }
        }
        return planner.finish();
    }

    public static Result addHandler(FormModel model, String componentId, EventSpec spec, String handler) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        FormComponent component = model.component(componentId)
                .orElseThrow(() -> new Rejected("Componente nao encontrado no codigo"));
        if (component.locked() && component.kind() == FormComponent.Kind.LOCAL) {
            throw new Rejected("O componente foi criado por codigo que o designer nao edita: "
                    + component.lockReason());
        }
        EventSpec.MethodSig signature = spec.signature();
        Optional<FormListener> anonymous = component.listeners(spec.addMethod()).stream()
                .filter(listener -> listener.style() == FormListener.Style.ANONYMOUS)
                .filter(listener -> listener.method(spec.method()).isEmpty())
                .filter(listener -> spec.adapterType() != null
                        && simple(spec.adapterType()).equals(simple(listener.anonymousType())))
                .findFirst();
        if (anonymous.isPresent()) {
            planner.insertIntoAnonymous(anonymous.get(), signature, handler);
        } else {
            planner.appendStatements(component, planner.registration(component, spec, handler));
        }
        Optional<FormModel.MethodInfo> existing = model.method(handler);
        if (existing.isPresent()) {
            planner.edits.add(new Edit(existing.get().bodyStart(), existing.get().bodyStart(), "", 0));
        } else {
            planner.handlerMethod(signature, handler);
        }
        return planner.finish();
    }

    public static Result removeHandler(FormModel model, String componentId, EventSpec spec, boolean deleteMethod) {
        SourceEditPlanner planner = new SourceEditPlanner(model);
        FormComponent component = model.component(componentId)
                .orElseThrow(() -> new Rejected("Componente nao encontrado no codigo"));
        String handler = null;
        boolean done = false;
        for (FormListener listener : component.listeners(spec.addMethod())) {
            if (listener.style() == FormListener.Style.ANONYMOUS) {
                Optional<FormListener.AnonymousMethod> method = listener.method(spec.method());
                if (method.isEmpty()) {
                    continue;
                }
                handler = method.get().delegate();
                long active = listener.methods().stream().filter(item -> item.delegate() != null).count();
                boolean adapter = spec.adapterType() != null
                        && simple(spec.adapterType()).equals(simple(listener.anonymousType()));
                if (active <= 1) {
                    planner.callSpan(listener.call()).ifPresent(span ->
                            planner.edits.add(new Edit(span.start(), span.end(), "", -1)));
                } else if (adapter) {
                    Span lines = planner.lines(method.get().declaration());
                    planner.edits.add(new Edit(lines.start(), lines.end(), "", -1));
                } else {
                    planner.emptyBody(method.get().declaration());
                }
                done = true;
                break;
            }
            if (listener.handles(spec.method(), spec.functional())) {
                handler = listener.reference();
                planner.callSpan(listener.call()).ifPresent(span ->
                        planner.edits.add(new Edit(span.start(), span.end(), "", -1)));
                done = true;
                break;
            }
        }
        if (!done) {
            throw new Rejected("Nenhum handler ligado a " + spec.method());
        }
        if (deleteMethod && handler != null) {
            Optional<FormModel.MethodInfo> method = model.method(handler);
            if (method.isPresent()) {
                Span lines = planner.lines(method.get().declaration());
                planner.edits.add(new Edit(lines.start(), lines.end(), "", -1));
            }
        }
        return planner.finish();
    }

    private FormComponent editable(String componentId) {
        FormComponent component = model.component(componentId)
                .orElseThrow(() -> new Rejected("Componente nao encontrado no codigo"));
        if (component.locked()) {
            throw new Rejected("Somente leitura: " + component.lockReason());
        }
        return component;
    }

    private String ref(FormComponent component) {
        return switch (component.kind()) {
            case ROOT -> "";
            case CONTENT -> "getContentPane().";
            case FIELD -> (model.thisPrefix() ? "this." : "") + component.name() + ".";
            case LOCAL -> component.name() + ".";
        };
    }

    private String refExpression(FormComponent component) {
        return switch (component.kind()) {
            case ROOT -> "this";
            case CONTENT -> "getContentPane()";
            case FIELD -> (model.thisPrefix() ? "this." : "") + component.name();
            case LOCAL -> component.name();
        };
    }

    private void appendStatements(FormComponent component, List<String> lines) {
        if (component.anchor() >= 0 && component.anchorMethod() != null) {
            insertAfter(component.anchor(), lines, true);
            return;
        }
        Optional<FormModel.MethodInfo> primary = primaryMethod();
        if (primary.isPresent()) {
            insertAtEnd(primary.get(), lines, true);
            return;
        }
        if (pendingFocus < 0) {
            pendingFocus = pendingInit.size();
        }
        pendingInit.addAll(lines);
    }

    private void placeChildStatements(FormComponent parent, FormComponent moving, int index, List<String> lines,
                                      boolean focus) {
        List<FormComponent> siblings = model.children(parent.id()).stream()
                .filter(child -> child.kind() != FormComponent.Kind.CONTENT)
                .filter(child -> moving == null || !child.id().equals(moving.id()))
                .toList();
        int createdAt = moving == null || !moving.creation().exists() ? -1 : moving.creation().end();
        if (index >= 0 && index < siblings.size()) {
            FormCall attach = siblings.get(index).attach();
            if (attach != null && !attach.chained() && attach.statement().start() > createdAt) {
                insertBefore(attach.statement().start(), lines, focus);
                return;
            }
        }
        int after = -1;
        for (FormComponent sibling : siblings) {
            if (sibling.attach() != null) {
                after = Math.max(after, sibling.attach().statement().end());
            }
        }
        if (moving != null) {
            after = Math.max(after, moving.creation().exists() && moving.creationMethod() != null
                    ? moving.creation().end() : -1);
        }
        if (after < 0 && parent.creation().exists() && parent.creationMethod() != null) {
            after = parent.anchor();
        }
        if (after < 0 && parent.anchor() >= 0 && parent.anchorMethod() != null) {
            after = parent.anchor();
        }
        if (after >= 0) {
            insertAfter(after, lines, focus);
            return;
        }
        Optional<FormModel.MethodInfo> primary = primaryMethod();
        if (primary.isPresent()) {
            insertAtEnd(primary.get(), lines, focus);
            return;
        }
        if (focus && pendingFocus < 0) {
            pendingFocus = pendingInit.size();
        }
        pendingInit.addAll(lines);
    }

    private Optional<FormModel.MethodInfo> primaryMethod() {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (FormComponent component : model.components()) {
            if (component.creationMethod() != null) {
                counts.merge(component.creationMethod(), 1, Integer::sum);
            }
            if (component.attach() != null) {
                counts.merge(component.attach().buildMethod(), 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (String method : model.buildMethods()) {
            int count = counts.getOrDefault(method, 0);
            if (count > bestCount) {
                best = method;
                bestCount = count;
            }
        }
        if (best != null) {
            return method(best);
        }
        for (String method : model.buildMethods()) {
            Optional<FormModel.MethodInfo> info = method(method);
            if (info.isPresent() && info.get().hasBody()) {
                return info;
            }
        }
        return Optional.empty();
    }

    private Optional<FormModel.MethodInfo> method(String name) {
        return model.methods().stream().filter(method -> method.name().equals(name) && method.hasBody())
                .findFirst();
    }

    private void insertAfter(int offset, List<String> lines, boolean focus) {
        String indent = statementIndent(offset);
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(nl).append(indent).append(indented(line, indent));
        }
        edits.add(new Edit(offset, offset, text.toString(), focus ? nl.length() + indent.length() : -1));
    }

    private void insertBefore(int statementStart, List<String> lines, boolean focus) {
        int lineStart = lineStart(statementStart);
        String indent = model.indentAt(statementStart);
        if (!source.substring(lineStart, statementStart).isBlank()) {
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(indented(line, indent)).append(nl).append(indent);
            }
            edits.add(new Edit(statementStart, statementStart, text.toString(), focus ? 0 : -1));
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(indent).append(indented(line, indent)).append(nl);
        }
        edits.add(new Edit(lineStart, lineStart, text.toString(), focus ? indent.length() : -1));
    }

    private void insertAtEnd(FormModel.MethodInfo method, List<String> lines, boolean focus) {
        int offset = method.lastStatementEnd();
        String indent = method.statementIndent();
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(nl).append(indent).append(indented(line, indent));
        }
        edits.add(new Edit(offset, offset, text.toString(), focus ? nl.length() + indent.length() : -1));
    }

    private String indented(String text, String indent) {
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            builder.append(nl);
            if (!lines[i].isBlank()) {
                builder.append(indent).append(lines[i]);
            }
        }
        return builder.toString();
    }

    private String statementIndent(int statementEnd) {
        int lineStart = lineStart(Math.max(0, statementEnd - 1));
        int statementLine = lineStart;
        String line = source.substring(lineStart, Math.min(source.length(), statementEnd)).trim();
        if (line.startsWith("}") || line.startsWith(")")) {
            int depth = 0;
            for (int i = statementEnd - 1; i >= 0; i--) {
                char ch = source.charAt(i);
                if (ch == '}' || ch == ')') {
                    depth++;
                } else if (ch == '{' || ch == '(') {
                    depth--;
                    if (depth == 0) {
                        statementLine = lineStart(i);
                        break;
                    }
                }
            }
        }
        return model.indentAt(statementLine);
    }

    private void rewriteConstraints(FormComponent component, String constraints) {
        FormCall attach = component.attach();
        if (attach == null) {
            throw new Rejected(component.name() + " nao foi adicionado por codigo reconhecido");
        }
        if (!attach.method().equals("add")) {
            throw new Rejected("Constraints so podem ser trocadas em add(...)");
        }
        String child = null;
        for (String argument : attach.arguments()) {
            String trimmed = argument.trim();
            if (trimmed.equals(component.name()) || trimmed.equals("this." + component.name())) {
                child = trimmed;
            }
        }
        if (child == null) {
            throw new Rejected("Argumento de " + component.name() + " nao encontrado em add(...)");
        }
        String replacement = constraints == null || constraints.isBlank() ? child : child + ", " + constraints;
        Span arguments = attach.argumentsSpan();
        edits.add(new Edit(arguments.start(), arguments.end(), replacement, -1));
        model.component(component.parentId()).ifPresent(parent ->
                live.add(ref(parent) + "add(" + replacement + ");"));
    }

    private List<String> registration(FormComponent component, EventSpec spec, String handler) {
        String target = refExpression(component);
        EventSpec.MethodSig signature = spec.signature();
        if (spec.functional()) {
            return List.of(target + "." + spec.addMethod() + "(this::" + handler + ");");
        }
        String unit = model.indentUnit();
        StringBuilder text = new StringBuilder();
        if (spec.adapterType() != null) {
            text.append(target).append('.').append(spec.addMethod()).append("(new ").append(use(spec.adapterType()))
                    .append("() {\n");
            text.append(listenerMethod(signature, handler, unit));
            text.append("});");
            return List.of(text.toString());
        }
        text.append(target).append('.').append(spec.addMethod()).append("(new ").append(use(spec.listenerType()))
                .append("() {\n");
        List<EventSpec.MethodSig> methods = spec.listenerMethods().isEmpty() ? List.of(signature)
                : spec.listenerMethods();
        for (int i = 0; i < methods.size(); i++) {
            EventSpec.MethodSig method = methods.get(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append(listenerMethod(method, method.name().equals(spec.method()) ? handler : null, unit));
        }
        text.append("});");
        return List.of(text.toString());
    }

    private String listenerMethod(EventSpec.MethodSig signature, String handler, String indent) {
        String inner = indent + model.indentUnit();
        StringBuilder text = new StringBuilder();
        text.append(indent).append("@Override\n");
        text.append(indent).append("public ").append(typeName(signature.returnType())).append(' ')
                .append(signature.name()).append('(').append(parameters(signature)).append(") {\n");
        String call = handler == null ? null : handler + "(" + String.join(", ", parameterNames(signature)) + ")";
        if (!"void".equals(signature.returnType())) {
            text.append(inner).append("return ").append(call == null ? defaultValue(signature.returnType()) : call)
                    .append(";\n");
        } else if (call != null) {
            text.append(inner).append(call).append(";\n");
        }
        text.append(indent).append("}\n");
        return text.toString();
    }

    private void insertIntoAnonymous(FormListener listener, EventSpec.MethodSig signature, String handler) {
        int brace = listener.anonymousEnd();
        int lineStart = lineStart(brace);
        String closingIndent = model.indentAt(brace);
        String indent = closingIndent + model.indentUnit();
        String body = listenerMethod(signature, handler, indent).replace("\n", nl);
        if (source.substring(lineStart, brace).isBlank()) {
            edits.add(new Edit(lineStart, lineStart, nl + body, -1));
        } else {
            edits.add(new Edit(brace, brace, nl + body + closingIndent, -1));
        }
    }

    private void handlerMethod(EventSpec.MethodSig signature, String handler) {
        int brace = model.classBodyEnd();
        int lineStart = lineStart(brace);
        String indent = model.memberIndent();
        String inner = indent + model.indentUnit();
        StringBuilder text = new StringBuilder();
        text.append(nl).append(indent).append("private ").append(typeName(signature.returnType())).append(' ')
                .append(handler).append('(').append(parameters(signature)).append(") {").append(nl);
        int focus = text.length() + inner.length();
        if (!"void".equals(signature.returnType())) {
            text.append(inner).append("return ").append(defaultValue(signature.returnType())).append(';').append(nl);
            focus = text.length() - nl.length() - 1;
        } else {
            text.append(inner).append(nl);
        }
        text.append(indent).append('}').append(nl);
        if (source.substring(lineStart, brace).isBlank()) {
            edits.add(new Edit(lineStart, lineStart, text.toString(), focus));
        } else {
            edits.add(new Edit(brace, brace, text + model.indentAt(brace), focus));
        }
    }

    private void emptyBody(Span declaration) {
        String text = declaration.text(source);
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return;
        }
        edits.add(new Edit(declaration.start() + open + 1, declaration.start() + close, nl
                + model.indentAt(declaration.start()), -1));
    }

    private String parameters(EventSpec.MethodSig signature) {
        List<String> names = parameterNames(signature);
        List<String> parameters = new ArrayList<>();
        for (int i = 0; i < signature.parameterTypes().size(); i++) {
            parameters.add(typeName(signature.parameterTypes().get(i)) + " " + names.get(i));
        }
        return String.join(", ", parameters);
    }

    private static List<String> parameterNames(EventSpec.MethodSig signature) {
        int count = signature.parameterTypes().size();
        if (count == 1) {
            return List.of("e");
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("arg" + i);
        }
        return names;
    }

    private String typeName(String type) {
        if (type == null || type.isBlank()) {
            return "void";
        }
        if (type.endsWith("[]")) {
            return typeName(type.substring(0, type.length() - 2)) + "[]";
        }
        if (!type.contains(".")) {
            return type;
        }
        return use(type);
    }

    private static String defaultValue(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            case "byte", "short", "int", "long", "float", "double" -> "0";
            default -> "null";
        };
    }

    public String use(String binaryName) {
        String top = binaryName.contains("$") ? binaryName.substring(0, binaryName.indexOf('$')) : binaryName;
        String nested = binaryName.substring(top.length()).replace('$', '.');
        int dot = top.lastIndexOf('.');
        String simple = dot < 0 ? top : top.substring(dot + 1);
        String owner = dot < 0 ? "" : top.substring(0, dot);
        if (owner.isEmpty() || owner.equals("java.lang") || owner.equals(model.typeNames().packageName())) {
            return simple + nested;
        }
        for (String imported : model.typeNames().explicitImports()) {
            if (imported.endsWith("." + simple) && !imported.equals(top)) {
                return top + nested;
            }
        }
        for (String pending : imports) {
            if (pending.endsWith("." + simple) && !pending.equals(top)) {
                return top + nested;
            }
        }
        imports.add(top);
        return simple + nested;
    }

    private void collect(FormComponent component, List<FormComponent> into) {
        into.add(component);
        for (FormComponent child : model.children(component.id())) {
            if (child.locked()) {
                throw new Rejected("Filho " + child.name() + " e somente leitura: " + child.lockReason());
            }
            collect(child, into);
        }
    }

    private Optional<Span> callSpan(FormCall call) {
        if (call == null || !call.removal().exists()) {
            return Optional.empty();
        }
        if (call.chained()) {
            return Optional.of(call.removal());
        }
        return Optional.of(lines(call.removal()));
    }

    private void removeCall(FormCall call) {
        Optional<Span> span = callSpan(call);
        if (span.isEmpty()) {
            throw new Rejected("Comando encadeado sem receptor explicito: " + call.method());
        }
        edits.add(new Edit(span.get().start(), span.get().end(), "", -1));
    }

    private Span lines(Span span) {
        int start = span.start();
        int lineStart = lineStart(start);
        boolean ownLine = source.substring(lineStart, start).isBlank();
        if (ownLine) {
            start = lineStart;
        }
        int end = span.end();
        int lineEnd = source.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        if (ownLine && source.substring(end, lineEnd).isBlank()) {
            end = Math.min(source.length(), lineEnd + 1);
        }
        return new Span(start, end);
    }

    private int lineStart(int offset) {
        return source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
    }

    private Result finish() {
        if (!pendingInit.isEmpty()) {
            emitInitMethod();
        }
        if (edits.isEmpty()) {
            throw new Rejected("Nada para alterar");
        }
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        StringBuilder text = new StringBuilder();
        int cursor = 0;
        int focus = -1;
        for (Edit edit : ordered) {
            if (edit.start() < cursor) {
                throw new Rejected("Alteracoes sobrepostas no codigo");
            }
            text.append(source, cursor, edit.start());
            if (edit.focus() >= 0 && focus < 0) {
                focus = text.length() + edit.focus();
            }
            text.append(edit.text());
            cursor = edit.end();
        }
        text.append(source.substring(cursor));
        String body = text.toString();
        if (imports.isEmpty()) {
            return new Result(body, focus, notes, live, imports);
        }
        JavaImportInserter.Result withImports = JavaImportInserter.insert(body, imports);
        int shift = withImports.text().length() - body.length();
        return new Result(withImports.text(), focus < 0 ? -1 : focus + shift, notes, live, imports);
    }

    private void emitInitMethod() {
        String name = "initComponents";
        int suffix = 2;
        while (model.hasMember(name)) {
            name = "initComponents" + suffix++;
        }
        String indent = model.memberIndent();
        String inner = indent + model.indentUnit();
        StringBuilder method = new StringBuilder();
        method.append(nl).append(indent).append("private void ").append(name).append("() {");
        int focus = -1;
        for (int i = 0; i < pendingInit.size(); i++) {
            method.append(nl).append(inner);
            if (i == pendingFocus) {
                focus = method.length();
            }
            method.append(indented(pendingInit.get(i), inner));
        }
        method.append(nl).append(indent).append('}').append(nl);
        Optional<FormModel.MethodInfo> constructor = model.constructors().stream()
                .filter(FormModel.MethodInfo::hasBody).findFirst();
        if (constructor.isPresent()) {
            FormModel.MethodInfo info = constructor.get();
            edits.add(new Edit(info.lastStatementEnd(), info.lastStatementEnd(),
                    nl + info.statementIndent() + name + "();", -1));
        } else {
            String simple = model.root().name();
            StringBuilder ctor = new StringBuilder();
            ctor.append(nl).append(indent).append("public ").append(simple).append("() {").append(nl)
                    .append(inner).append(name).append("();").append(nl).append(indent).append('}').append(nl);
            method.insert(0, ctor);
            focus = focus < 0 ? -1 : focus + ctor.length();
        }
        int brace = model.classBodyEnd();
        int lineStart = lineStart(brace);
        if (source.substring(lineStart, brace).isBlank()) {
            edits.add(new Edit(lineStart, lineStart, method.toString(), focus));
        } else {
            edits.add(new Edit(brace, brace, method + model.indentAt(brace), focus));
        }
        pendingInit.clear();
    }

    private static boolean isAncestor(FormModel model, String ancestor, String id) {
        String current = id;
        int guard = 0;
        while (current != null && guard++ < 1000) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = model.component(current).map(FormComponent::parentId).orElse(null);
        }
        return false;
    }

    private static boolean mentions(FormCall call, Set<String> names) {
        for (String argument : call.arguments()) {
            for (String name : names) {
                if (name != null && Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(argument).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countDeclared(String source, Span field) {
        String text = field.text(source);
        int depth = 0;
        int commas = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '(' || ch == '<' || ch == '{' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == '>' || ch == '}' || ch == ']') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
    }

    private static List<Span> merge(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::end).reversed()));
        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            if (!merged.isEmpty() && span.start() < merged.getLast().end()) {
                Span last = merged.getLast();
                merged.set(merged.size() - 1, new Span(last.start(), Math.max(last.end(), span.end())));
                continue;
            }
            merged.add(span);
        }
        return merged;
    }

    private static String simple(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.replaceAll("<.*>", "").replace('$', '.');
        int dot = clean.lastIndexOf('.');
        return dot < 0 ? clean : clean.substring(dot + 1);
    }
}
