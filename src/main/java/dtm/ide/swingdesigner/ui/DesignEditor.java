package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildResult;
import dtm.ide.swingdesigner.ModuleSession;
import dtm.ide.swingdesigner.SwingDesignerEnvironment;
import dtm.ide.swingdesigner.catalog.ComponentCatalog;
import dtm.ide.swingdesigner.catalog.ComponentDescriptor;
import dtm.ide.swingdesigner.catalog.ConstructorInfo;
import dtm.ide.swingdesigner.catalog.ContainerSpec;
import dtm.ide.swingdesigner.catalog.EventDescriptor;
import dtm.ide.swingdesigner.catalog.ParameterInfo;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
import dtm.ide.swingdesigner.form.DesignHistory;
import dtm.ide.swingdesigner.form.EventSpec;
import dtm.ide.swingdesigner.form.FormComponent;
import dtm.ide.swingdesigner.form.FormLinks;
import dtm.ide.swingdesigner.form.FormModel;
import dtm.ide.swingdesigner.form.FormNames;
import dtm.ide.swingdesigner.form.FormReader;
import dtm.ide.swingdesigner.form.JavaValueCodec;
import dtm.ide.swingdesigner.form.LayoutCode;
import dtm.ide.swingdesigner.catalog.LayoutDescriptor;
import dtm.ide.swingdesigner.form.SourceEditPlanner;
import dtm.ide.swingdesigner.recovery.RecoveryPlanner;
import dtm.ide.swingdesigner.runtime.SnapshotNode;
import dtm.ide.swingdesigner.runtime.SwingViewClient;
import dtm.ide.swingdesigner.runtime.ViewResult;
import dtm.ide.swingdesigner.runtime.ViewWarning;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class DesignEditor {

    record Applied(String label, ViewResult live, int focus, List<String> notes, Path file, String text) {
    }

    record Placement(String parentNodeId, int swingIndex, String constraints, Set<String> imports,
                     List<Map.Entry<String, String>> extraProperties) {

        Placement {
            imports = imports == null ? Set.of() : Set.copyOf(imports);
            extraProperties = extraProperties == null ? List.of() : List.copyOf(extraProperties);
        }
    }

    private final SwingDesignerEnvironment environment;
    private final ModuleSession session;
    private final Path file;
    private final String className;
    private final DesignHistory history = new DesignHistory();
    private volatile FormModel model;
    private volatile FormLinks links = FormLinks.EMPTY;
    private volatile String readProblem;

    DesignEditor(SwingDesignerEnvironment environment, ModuleSession session, Path file, String className) {
        this.environment = environment;
        this.session = session;
        this.file = file;
        this.className = className;
    }

    DesignHistory history() {
        return history;
    }

    Optional<FormModel> model() {
        return Optional.ofNullable(model);
    }

    FormLinks links() {
        return links;
    }

    String readProblem() {
        return readProblem;
    }

    boolean editable() {
        return model != null;
    }

    void refresh(SnapshotNode root) {
        String text = environment.sourceText(file);
        if (text == null) {
            model = null;
            links = FormLinks.EMPTY;
            readProblem = "Nao foi possivel ler " + file.getFileName();
            return;
        }
        ComponentCatalog catalog = session.catalog();
        Optional<ComponentDescriptor> descriptor = catalog.descriptor(className);
        boolean window = descriptor.map(ComponentDescriptor::isWindow).orElse(false)
                || catalog.index().superChain(className).contains("java.awt.Window");
        FormReader.Options options = new FormReader.Options(className,
                descriptor.map(ComponentDescriptor::designInitOrEmpty).orElse(List.of()), window,
                catalog::isDrawable, attachMethods(catalog));
        Optional<FormModel> read = FormReader.read(text, options);
        model = read.orElse(null);
        readProblem = read.isPresent() ? null : "O codigo de " + FormNames.simpleName(className)
                + " nao pode ser lido (erro de sintaxe?)";
        links = model == null || root == null ? FormLinks.EMPTY : FormLinks.link(root, model);
    }

    Optional<FormComponent> componentOf(String nodeId) {
        FormModel current = model;
        if (current == null) {
            return Optional.empty();
        }
        return links.componentOf(nodeId).flatMap(current::component);
    }

    Optional<String> nodeOf(String componentId) {
        return links.nodeOf(componentId);
    }

    Set<String> definedSetters(String nodeId) {
        Set<String> setters = new LinkedHashSet<>();
        componentOf(nodeId).ifPresent(component -> component.properties()
                .forEach(call -> setters.add(call.method())));
        return setters;
    }

    String lockReason(String nodeId) {
        if (model == null) {
            return readProblem;
        }
        Optional<FormComponent> component = componentOf(nodeId);
        if (component.isEmpty()) {
            return "Criado fora do codigo desta classe";
        }
        return component.get().lockReason();
    }

    Applied setProperty(String nodeId, PropertyDescriptor property, JsonNode value) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        Set<String> imports = new LinkedHashSet<>();
        String expression = JavaValueCodec.encode(value, property.type(), "enum".equals(property.editor()),
                name -> use(current, name, imports));
        return commit(property.label(), SourceEditPlanner.setProperty(current, component.id(), property.setter(),
                expression, imports));
    }

    Applied resetProperty(String nodeId, PropertyDescriptor property) {
        FormComponent component = component(nodeId);
        return commit("Restaurar " + property.label(),
                SourceEditPlanner.resetProperty(require(), component.id(), property.setter()));
    }

    Applied addComponent(String paletteClass, Placement placement, SnapshotNode root) {
        FormModel current = require();
        ComponentCatalog catalog = session.catalog();
        FormComponent parent = containerComponent(placement.parentNodeId());
        String name = FormNames.component(current, paletteClass);
        Set<String> imports = new LinkedHashSet<>(placement.imports());
        Optional<ComponentDescriptor> descriptor = catalog.descriptor(paletteClass);
        String arguments = constructorArguments(descriptor.orElse(null), current, imports);
        List<Map.Entry<String, String>> properties = new ArrayList<>();
        descriptor.ifPresent(found -> {
            PropertyDescriptor text = found.propertiesOrEmpty().get("text");
            boolean textInput = catalog.index().superChain(paletteClass).contains("javax.swing.text.JTextComponent");
            if (text != null && text.setter() != null && "java.lang.String".equals(text.type())
                    && arguments.isEmpty() && !textInput) {
                properties.add(Map.entry(text.setter(), JavaValueCodec.quote(FormNames.defaultText(paletteClass))));
            }
        });
        properties.addAll(placement.extraProperties());
        String attach = attachCall(catalog, parent, name, shorten(current, placement.constraints(), imports));
        int index = modelIndex(current, parent, placement, root);
        SourceEditPlanner.NewComponent spec = new SourceEditPlanner.NewComponent(parent.id(), paletteClass, name,
                arguments, properties, attach, index);
        return commit("Adicionar " + name, SourceEditPlanner.addComponent(current, spec, imports));
    }

    Applied removeComponent(String nodeId, SnapshotNode root) {
        FormComponent component = component(nodeId);
        SourceEditPlanner.Result result = SourceEditPlanner.removeComponent(require(), component.id());
        Map<String, String> bindings = new LinkedHashMap<>();
        List<String> live = new ArrayList<>();
        root.parentOf(nodeId).ifPresent(parent -> {
            bindings.put("designerParent", parent.id());
            bindings.put("designerChild", nodeId);
            live.add("designerParent.remove(designerChild);");
            live.add("designerParent.revalidate();");
            live.add("designerParent.repaint();");
        });
        return commit("Remover " + component.name(), result, live, bindings);
    }

    Applied moveComponent(String nodeId, Placement placement, SnapshotNode root) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        FormComponent parent = containerComponent(placement.parentNodeId());
        Set<String> imports = new LinkedHashSet<>(placement.imports());
        String attach = attachCall(session.catalog(), parent, component.name(),
                shorten(current, placement.constraints(), imports));
        int index = modelIndex(current, parent, placement, root);
        return commit("Mover " + component.name(), SourceEditPlanner.moveComponent(current, component.id(),
                parent.id(), index, attach, imports));
    }

    Applied setBounds(String nodeId, java.awt.Rectangle bounds) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        Set<String> imports = new LinkedHashSet<>();
        String expression = bounds.x + ", " + bounds.y + ", " + bounds.width + ", " + bounds.height;
        return commit("Posicionar " + component.name(), SourceEditPlanner.setProperty(current, component.id(),
                "setBounds", expression, imports));
    }

    Applied setPreferredSize(String nodeId, java.awt.Dimension size) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        Set<String> imports = new LinkedHashSet<>();
        String expression = "new " + use(current, "java.awt.Dimension", imports) + "(" + size.width + ", "
                + size.height + ")";
        return commit("Redimensionar " + component.name(), SourceEditPlanner.setProperty(current, component.id(),
                "setPreferredSize", expression, imports));
    }

    Applied setLayout(String nodeId, String layoutExpression, Map<String, String> childConstraints,
                      Set<String> imports) {
        FormModel current = require();
        FormComponent container = containerComponent(nodeId);
        Map<String, String> constraints = new LinkedHashMap<>();
        childConstraints.forEach((childNode, value) -> componentOf(childNode)
                .filter(child -> child.attach() != null && "add".equals(child.attach().method()))
                .ifPresent(child -> constraints.put(child.id(), value)));
        return commit("Layout de " + container.name(), SourceEditPlanner.setLayout(current, container.id(),
                layoutExpression, constraints, imports));
    }

    Applied setConstraints(String nodeId, String constraints) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        Set<String> imports = new LinkedHashSet<>();
        return commit("Posicao de " + component.name(), SourceEditPlanner.setConstraints(current, component.id(),
                shorten(current, constraints, imports), imports));
    }

    Applied setLayoutChoice(String nodeId, String layoutClass, Map<String, String> values, SnapshotNode root) {
        FormModel current = require();
        FormComponent container = containerComponent(nodeId);
        Map<String, LayoutDescriptor> layouts = session.catalog().layouts();
        Set<String> imports = new LinkedHashSet<>();
        String target = switch (container.kind()) {
            case ROOT -> "this";
            case CONTENT -> "getContentPane()";
            default -> container.name();
        };
        String expression = LayoutCode.ABSOLUTE.equals(layoutClass) ? "null"
                : LayoutCode.constructor(layouts.get(layoutClass), values, target, name -> use(current, name, imports));
        Optional<SnapshotNode> containerNode = links.nodeOf(container.id()).flatMap(root::find);
        String previous = containerNode.map(SnapshotNode::layoutClass).orElse(null);
        Map<String, String> constraints = new LinkedHashMap<>();
        Map<String, String> bounds = new LinkedHashMap<>();
        if (containerNode.isPresent() && !layoutClass.equals(previous)) {
            Rectangle base = containerNode.get().bounds();
            for (SnapshotNode child : containerNode.get().children()) {
                Optional<FormComponent> linked = links.componentOf(child.id()).flatMap(current::component);
                if (linked.isEmpty() || linked.get().attach() == null
                        || !"add".equals(linked.get().attach().method())) {
                    continue;
                }
                String value = switch (layoutClass) {
                    case LayoutCode.GRID_BAG -> "new " + use(current, "java.awt.GridBagConstraints", imports) + "()";
                    case "java.awt.CardLayout" -> JavaValueCodec.quote(linked.get().name());
                    default -> "";
                };
                constraints.put(linked.get().id(), value);
                if (LayoutCode.ABSOLUTE.equals(layoutClass)) {
                    Rectangle area = child.bounds();
                    bounds.put(linked.get().id(), (area.x - base.x) + ", " + (area.y - base.y) + ", "
                            + Math.max(1, area.width) + ", " + Math.max(1, area.height));
                }
            }
        }
        return commit("Layout de " + container.name(), SourceEditPlanner.setLayout(current, container.id(),
                expression, constraints, bounds, imports));
    }

    private static String shorten(FormModel model, String expression, Set<String> imports) {
        if (expression == null || expression.isBlank()) {
            return expression;
        }
        return LayoutCode.shorten(expression, name -> use(model, name, imports));
    }

    Applied rename(String nodeId, String newName) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        if (!FormNames.isIdentifier(newName)) {
            throw new SourceEditPlanner.Rejected("Nome invalido: " + newName);
        }
        if (current.hasMember(newName)) {
            throw new SourceEditPlanner.Rejected("Ja existe um membro chamado " + newName);
        }
        int offset = component.field().exists() ? nameOffset(current.source(), component.field(), component.name())
                : component.declaration().exists()
                ? nameOffset(current.source(), component.declaration(), component.name()) : -1;
        if (offset < 0) {
            throw new SourceEditPlanner.Rejected("Declaracao de " + component.name() + " nao encontrada");
        }
        String before = current.source();
        if (!environment.renameSymbol(file, before, offset, newName)) {
            throw new SourceEditPlanner.Rejected("O rename nao pode ser feito pelo JDT-LS agora");
        }
        String after = environment.sourceText(file);
        history.record("Renomear " + component.name(), before, after);
        return new Applied("Renomear " + component.name(), null, -1, List.of(), file, after);
    }

    Applied addHandler(String nodeId, EventDescriptor event, EventDescriptor.EventMethod method, String handler) {
        FormModel current = require();
        FormComponent component = componentForEvents(nodeId);
        if (!FormNames.isIdentifier(handler)) {
            throw new SourceEditPlanner.Rejected("Nome de metodo invalido: " + handler);
        }
        return commit("Evento " + method.name(), SourceEditPlanner.addHandler(current, component.id(),
                spec(event, method), handler));
    }

    Applied removeHandler(String nodeId, EventDescriptor event, EventDescriptor.EventMethod method,
                          boolean deleteMethod) {
        FormComponent component = componentForEvents(nodeId);
        return commit("Remover evento " + method.name(), SourceEditPlanner.removeHandler(require(), component.id(),
                spec(event, method), deleteMethod));
    }

    Optional<String> handlerOf(String nodeId, EventDescriptor event, EventDescriptor.EventMethod method) {
        Optional<FormComponent> component = componentOf(nodeId);
        if (component.isEmpty()) {
            return Optional.empty();
        }
        for (var listener : component.get().listeners(event.addMethod())) {
            if (listener.handles(method.name(), event.functional())) {
                Optional<String> handler = listener.handlerFor(method.name());
                if (handler.isPresent()) {
                    return handler;
                }
                return Optional.of(listener.style() == dtm.ide.swingdesigner.form.FormListener.Style.LAMBDA
                        ? "(lambda)" : "(codigo)");
            }
        }
        return Optional.empty();
    }

    Optional<Integer> handlerLine(String handler) {
        FormModel current = model;
        if (current == null) {
            return Optional.empty();
        }
        return current.method(handler).map(method -> current.line(method.bodyStart()));
    }

    Optional<Integer> declarationLine(String nodeId) {
        FormModel current = model;
        Optional<FormComponent> component = componentOf(nodeId);
        if (current == null || component.isEmpty()) {
            return Optional.empty();
        }
        FormComponent found = component.get();
        int offset = found.creation().exists() ? found.creation().start()
                : found.field().exists() ? found.field().start()
                : found.declaration().exists() ? found.declaration().start() : -1;
        return offset < 0 ? Optional.empty() : Optional.of(current.line(offset));
    }

    String suggestHandler(String nodeId, EventDescriptor.EventMethod method) {
        FormModel current = require();
        return FormNames.handler(current, componentOf(nodeId).orElse(current.root()), method.name());
    }

    Applied undo() {
        String current = environment.sourceText(file);
        DesignHistory.Step step = history.undo(current)
                .orElseThrow(() -> new SourceEditPlanner.Rejected("Nada para desfazer no designer"));
        if (!environment.applySource(file, step.after(), step.before())) {
            history.restore(step, true);
            throw new SourceEditPlanner.Rejected("O arquivo mudou; use o desfazer do editor");
        }
        return new Applied("Desfazer " + step.label(), null, -1, List.of(), file, step.before());
    }

    Applied redo() {
        String current = environment.sourceText(file);
        DesignHistory.Step step = history.redo(current)
                .orElseThrow(() -> new SourceEditPlanner.Rejected("Nada para refazer no designer"));
        if (!environment.applySource(file, step.before(), step.after())) {
            history.restore(step, false);
            throw new SourceEditPlanner.Rejected("O arquivo mudou; use o refazer do editor");
        }
        return new Applied("Refazer " + step.label(), null, -1, List.of(), file, step.after());
    }

    BuildResult compileShadow() {
        String text = environment.sourceText(file);
        if (text == null) {
            return BuildResult.failed("javac", "Codigo indisponivel");
        }
        return session.compileShadow(className, text);
    }

    static List<ViewWarning> diagnostics(BuildResult build) {
        List<ViewWarning> warnings = new ArrayList<>();
        for (BuildDiagnostic diagnostic : build.errors()) {
            List<ViewWarning.Frame> frames = new ArrayList<>();
            if (diagnostic.hasLocation()) {
                String name = diagnostic.file().getFileName().toString();
                frames.add(new ViewWarning.Frame("", "", name, diagnostic.line()));
            }
            warnings.add(new ViewWarning(ViewWarning.ERROR, "compile", null, "Erro de compilacao: "
                    + diagnostic.message(), null, "O canvas mostra o ultimo estado que compilou.", frames, null));
        }
        if (warnings.isEmpty()) {
            warnings.add(new ViewWarning(ViewWarning.ERROR, "compile", null, "A compilacao do designer falhou: "
                    + build.summary(), null, null, List.of(), null));
        }
        return warnings;
    }

    private Applied commit(String label, SourceEditPlanner.Result result) {
        return commit(label, result, result.live(), Map.of());
    }

    private Applied commit(String label, SourceEditPlanner.Result result, List<String> live,
                           Map<String, String> extraBindings) {
        FormModel current = require();
        String before = current.source();
        if (!environment.applySource(file, before, result.text())) {
            throw new SourceEditPlanner.Rejected("O arquivo mudou enquanto a alteracao era preparada; tente de novo");
        }
        history.record(label, before, result.text());
        ViewResult view = null;
        if (!live.isEmpty()) {
            try {
                List<ObjectNode> statements = RecoveryPlanner.translate(result.text(), className, live,
                        result.imports());
                Map<String, String> bindings = new LinkedHashMap<>(bindings(current));
                bindings.putAll(extraBindings);
                SwingViewClient client = session.client();
                view = client.interpret(statements, List.of(), bindings,
                        session.catalog().options().stubsEnabled()).view();
            } catch (RuntimeException ignored) {
                view = null;
            }
        }
        return new Applied(label, view, result.focus(), result.notes(), file, result.text());
    }

    private Map<String, String> bindings(FormModel current) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (FormComponent component : current.components()) {
            if (component.kind() == FormComponent.Kind.FIELD || component.kind() == FormComponent.Kind.LOCAL) {
                links.nodeOf(component.id()).ifPresent(node -> bindings.putIfAbsent(component.name(), node));
            }
        }
        return bindings;
    }

    private FormModel require() {
        FormModel current = model;
        if (current == null) {
            throw new SourceEditPlanner.Rejected(readProblem == null ? "Codigo indisponivel" : readProblem);
        }
        return current;
    }

    private FormComponent component(String nodeId) {
        return componentOf(nodeId).orElseThrow(() -> new SourceEditPlanner.Rejected(
                "Este componente foi criado fora do codigo desta classe"));
    }

    private FormComponent componentForEvents(String nodeId) {
        FormModel current = require();
        return componentOf(nodeId).orElseGet(() -> {
            if (links.componentOf(nodeId).isEmpty() && "0".equals(nodeId)) {
                return current.root();
            }
            throw new SourceEditPlanner.Rejected("Este componente foi criado fora do codigo desta classe");
        });
    }

    private FormComponent containerComponent(String nodeId) {
        FormModel current = require();
        FormComponent component = component(nodeId);
        if (component.isRoot()) {
            String target = current.containerFor(FormModel.ROOT);
            return current.component(target).orElse(component);
        }
        return component;
    }

    private int modelIndex(FormModel current, FormComponent parent, Placement placement, SnapshotNode root) {
        if (placement.swingIndex() < 0 || root == null) {
            return -1;
        }
        Optional<SnapshotNode> parentNode = links.nodeOf(parent.id()).flatMap(root::find);
        if (parentNode.isEmpty()) {
            return -1;
        }
        SnapshotNode target = null;
        for (SnapshotNode child : parentNode.get().children()) {
            if (child.index() == placement.swingIndex()) {
                target = child;
                break;
            }
        }
        if (target == null) {
            return -1;
        }
        Optional<String> sibling = links.componentOf(target.id());
        if (sibling.isEmpty()) {
            return -1;
        }
        List<FormComponent> siblings = current.children(parent.id()).stream()
                .filter(child -> child.kind() != FormComponent.Kind.CONTENT).toList();
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).id().equals(sibling.get())) {
                return i;
            }
        }
        return -1;
    }

    private String attachCall(ComponentCatalog catalog, FormComponent parent, String child, String constraints) {
        String strategy = parent.className() == null ? null : catalog.descriptor(parent.className())
                .map(ComponentDescriptor::container)
                .map(ContainerSpec::effectiveChildStrategy)
                .orElse(null);
        if (strategy == null || parent.kind() == FormComponent.Kind.CONTENT) {
            strategy = ContainerSpec.DEFAULT_LAYOUT_STRATEGY;
        }
        String resolved = constraints == null ? null
                : constraints.replace("${child.name}", JavaValueCodec.quote(child));
        constraints = resolved;
        String call = strategy.replace("${child}", child).replace("${child.id}", JavaValueCodec.quote(child));
        call = call.replaceAll("\\$\\{child\\.prop\\.[\\w]+}", JavaValueCodec.quote(child));
        if (constraints == null || constraints.isBlank()) {
            call = call.replace(", ${constraints}", "").replace("${constraints}, ", "")
                    .replace("${constraints}", "");
        } else {
            call = call.replace("${constraints}", constraints);
        }
        int dot = call.indexOf('(');
        String method = dot < 0 ? call : call.substring(0, dot);
        if (method.contains(".")) {
            call = call.substring(method.lastIndexOf('.') + 1);
        }
        return call;
    }

    private String constructorArguments(ComponentDescriptor descriptor, FormModel current, Set<String> imports) {
        if (descriptor == null) {
            return "";
        }
        List<ConstructorInfo> constructors = descriptor.constructorsOrEmpty().stream()
                .filter(info -> !info.isFactory()).toList();
        if (constructors.isEmpty() || constructors.stream().anyMatch(ConstructorInfo::isNoArg)) {
            return "";
        }
        ConstructorInfo chosen = descriptor.preferredConstructor() != null
                && !descriptor.preferredConstructor().isFactory() ? descriptor.preferredConstructor()
                : constructors.stream().min(java.util.Comparator.comparingInt(info -> info.parameters().size()))
                .orElseThrow();
        List<String> arguments = new ArrayList<>();
        for (ParameterInfo parameter : chosen.parameters()) {
            arguments.add(defaultArgument(parameter, current, imports));
        }
        return String.join(", ", arguments);
    }

    private String defaultArgument(ParameterInfo parameter, FormModel current, Set<String> imports) {
        String type = parameter.type();
        return switch (type) {
            case "java.lang.String" -> JavaValueCodec.quote(FormNames.capitalize(parameter.name()));
            case "boolean" -> "false";
            case "char" -> "' '";
            case "byte", "short", "int", "long" -> "0";
            case "float" -> "0f";
            case "double" -> "0.0";
            default -> {
                List<String> constants = session.catalog().enumConstants(type);
                yield constants.isEmpty() ? "null" : use(current, type, imports) + "." + constants.getFirst();
            }
        };
    }

    private static Set<String> attachMethods(ComponentCatalog catalog) {
        return Set.of();
    }

    private static EventSpec spec(EventDescriptor event, EventDescriptor.EventMethod method) {
        List<EventSpec.MethodSig> signatures = new ArrayList<>();
        for (EventDescriptor.EventMethod item : event.methods()) {
            signatures.add(new EventSpec.MethodSig(item.name(), item.parameterTypes(), item.returnType()));
        }
        return new EventSpec(event.addMethod(), event.listenerType(), method.name(), event.functional(),
                event.adapterType(), signatures);
    }

    static String use(FormModel model, String binaryName, Set<String> imports) {
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
        imports.add(top);
        return simple + nested;
    }

    private static int nameOffset(String source, dtm.ide.swingdesigner.form.Span span, String name) {
        String text = span.text(source);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name)
                + "\\b").matcher(text);
        return matcher.find() ? span.start() + matcher.start() : -1;
    }
}
