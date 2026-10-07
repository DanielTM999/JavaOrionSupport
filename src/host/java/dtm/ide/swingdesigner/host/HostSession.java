package dtm.ide.swingdesigner.host;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Window;
import java.beans.Beans;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

final class HostSession {

    private static final int DEFAULT_WIDTH = 400;
    private static final int DEFAULT_HEIGHT = 300;

    private URLClassLoader libraries;
    private URLClassLoader workspace;
    private List<String> workspacePaths = new ArrayList<String>();
    private String lookAndFeel;

    private final Snapshots snapshots = new Snapshots();
    private Object viewInstance;
    private Component renderRoot;
    private Window ownedWindow;
    private Window previewWindow;
    private byte[] lastImage;

    boolean hasVisibleWindows() {
        Window preview = previewWindow;
        return preview != null && preview.isDisplayable();
    }

    Map<String, Object> handle(String op, Map<String, Object> request, byte[][] blob) throws Exception {
        if ("init".equals(op)) {
            return init(request);
        }
        if ("reload".equals(op)) {
            return reload(request);
        }
        if ("view".equals(op)) {
            return view(request, blob);
        }
        if ("render".equals(op)) {
            return render(blob);
        }
        if ("inspect".equals(op)) {
            return inspect(request);
        }
        if ("setProperty".equals(op)) {
            return setProperty(request, blob);
        }
        if ("preview".equals(op)) {
            return preview(request);
        }
        if ("interpret".equals(op)) {
            return interpret(request, blob);
        }
        if ("closePreview".equals(op)) {
            closePreview();
            return new LinkedHashMap<String, Object>();
        }
        throw new IllegalArgumentException("Operacao desconhecida: " + op);
    }

    void dispose() {
        try {
            onEdt(new Callable<Object>() {
                @Override
                public Object call() {
                    disposeView();
                    closePreview();
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> init(Map<String, Object> request) throws Exception {
        List<URL> libraryUrls = urls(Json.array(request.get("libraries")));
        libraries = new URLClassLoader(libraryUrls.toArray(new URL[0]),
                ClassLoader.getSystemClassLoader().getParent());
        workspacePaths = strings(Json.array(request.get("workspace")));
        workspace = newWorkspaceLoader();
        lookAndFeel = Json.string(request, "lookAndFeel");
        final String requestedLaf = lookAndFeel;
        String applied = onEdt(new Callable<String>() {
            @Override
            public String call() {
                Thread.currentThread().setContextClassLoader(workspace);
                return applyLookAndFeel(requestedLaf);
            }
        });
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("lookAndFeel", applied);
        result.put("javaVersion", System.getProperty("java.version"));
        return result;
    }

    private Map<String, Object> reload(Map<String, Object> request) throws Exception {
        if (request.containsKey("workspace")) {
            workspacePaths = strings(Json.array(request.get("workspace")));
        }
        final URLClassLoader previous = workspace;
        workspace = newWorkspaceLoader();
        onEdt(new Callable<Object>() {
            @Override
            public Object call() {
                disposeView();
                Thread.currentThread().setContextClassLoader(workspace);
                return null;
            }
        });
        closeQuietly(previous);
        return new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> view(final Map<String, Object> request, byte[][] blob) throws Exception {
        requireInit();
        final String className = Json.string(request, "className");
        final Class<?> type = Class.forName(className, false, workspace);
        final Map<String, Object> constructor = Json.object(request.get("constructor"));
        final List<String> designInit = strings(Json.array(request.get("designInit")));
        final int width = Json.integer(request, "width", -1);
        final int height = Json.integer(request, "height", -1);
        final boolean stubs = Json.bool(request, "stubs", true);
        final DesignInjector injector = injector(request, stubs);
        final Map<String, Object> response = new LinkedHashMap<String, Object>();
        final List<Object> warnings = new ArrayList<Object>();
        response.put("warnings", warnings);
        final Object instance = onEdt(new Callable<Object>() {
            @Override
            public Object call() {
                disposeView();
                Thread.currentThread().setContextClassLoader(workspace);
                Beans.setDesignTime(true);
                try {
                    Instantiator.Result created = instantiate(type, constructor, stubs);
                    response.put("constructor", constructorInfo(created));
                    response.put("attempts", created.attempts);
                    if (created.instance == null) {
                        response.put("error", created.failure == null ? "Nao foi possivel instanciar "
                                + className : Instantiator.describe(created.failure));
                        response.put("stackTrace", stackTrace(created.failure));
                        return null;
                    }
                    if (!(created.instance instanceof Component)) {
                        response.put("error", className + " nao e um java.awt.Component");
                        return null;
                    }
                    injector.apply(created.instance, warnings);
                    Lifecycle.run(created.instance, designInit, warnings);
                    return created.instance;
                } finally {
                    Beans.setDesignTime(false);
                }
            }
        });
        if (instance == null) {
            blob[0] = null;
            return response;
        }
        onEdt(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                viewInstance = instance;
                renderRoot = stage((Component) instance, width, height);
                response.put("root", snapshots.describe(renderRoot, viewInstance));
                response.put("width", renderRoot.getWidth());
                response.put("height", renderRoot.getHeight());
                response.put("window", instance instanceof Window);
                String title = windowTitle(instance);
                if (title != null) {
                    response.put("title", title);
                }
                if (Lifecycle.looksEmpty(renderRoot) && designInit.isEmpty()) {
                    warnings.add(Warnings.info("Nenhum componente foi montado pelo construtor. Se a tela e"
                            + " criada em um metodo de ciclo de vida (init, onCreate, onDrawing...), declare-o em"
                            + " \"designInit\" no .orion/swing-components.json do projeto."));
                }
                lastImage = Snapshots.render(renderRoot);
                return null;
            }
        });
        blob[0] = lastImage;
        return response;
    }

    private Map<String, Object> render(byte[][] blob) throws Exception {
        if (renderRoot == null) {
            throw new IllegalStateException("Nada para renderizar");
        }
        Map<String, Object> result = onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                if (ownedWindow != null) {
                    ownedWindow.validate();
                }
                renderRoot.validate();
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("root", snapshots.describe(renderRoot, viewInstance));
                response.put("width", renderRoot.getWidth());
                response.put("height", renderRoot.getHeight());
                lastImage = Snapshots.render(renderRoot);
                return response;
            }
        });
        blob[0] = lastImage;
        return result;
    }

    private Map<String, Object> inspect(Map<String, Object> request) throws Exception {
        final Component node = node(Json.string(request, "node"));
        final List<Object> properties = Json.array(request.get("properties"));
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() {
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                Map<String, Object> errors = new LinkedHashMap<String, Object>();
                for (Object entry : properties) {
                    Map<String, Object> property = Json.object(entry);
                    String name = Json.string(property, "name");
                    String getter = Json.string(property, "getter");
                    if (name == null || getter == null) {
                        continue;
                    }
                    try {
                        Method method = node.getClass().getMethod(getter);
                        values.put(name, Values.toJson(method.invoke(node)));
                    } catch (Throwable error) {
                        errors.put(name, Instantiator.describe(error));
                    }
                }
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("className", node.getClass().getName());
                response.put("values", values);
                if (!errors.isEmpty()) {
                    response.put("errors", errors);
                }
                return response;
            }
        });
    }

    private Map<String, Object> setProperty(final Map<String, Object> request, byte[][] blob)
            throws Exception {
        final Component node = node(Json.string(request, "node"));
        final String setter = Json.string(request, "setter");
        final List<Object> types = Json.array(request.get("parameterTypes"));
        final List<Object> values = Json.array(request.get("values"));
        onEdt(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Class<?>[] parameterTypes = new Class<?>[types.size()];
                Object[] arguments = new Object[types.size()];
                for (int i = 0; i < parameterTypes.length; i++) {
                    parameterTypes[i] = Values.type(String.valueOf(types.get(i)), workspace);
                    arguments[i] = Values.toJava(i < values.size() ? values.get(i) : null,
                            parameterTypes[i], workspace);
                }
                Method method = node.getClass().getMethod(setter, parameterTypes);
                try {
                    method.invoke(node, arguments);
                } catch (InvocationTargetException error) {
                    throw new IllegalStateException(Instantiator.describe(error), error.getCause());
                }
                node.invalidate();
                Container top = renderRoot instanceof Container ? (Container) renderRoot : null;
                if (top != null) {
                    top.validate();
                }
                node.repaint();
                return null;
            }
        });
        return render(blob);
    }

    private Map<String, Object> preview(final Map<String, Object> request) throws Exception {
        requireInit();
        final String className = Json.string(request, "className");
        final Class<?> type = Class.forName(className, false, workspace);
        final Map<String, Object> constructor = Json.object(request.get("constructor"));
        final boolean stubs = Json.bool(request, "stubs", true);
        final DesignInjector injector = injector(request, stubs);
        return onEdt(new Callable<Map<String, Object>>() {
            @Override
            public Map<String, Object> call() throws Exception {
                closePreview();
                Thread.currentThread().setContextClassLoader(workspace);
                Instantiator.Result created = instantiate(type, constructor, stubs);
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("attempts", created.attempts);
                if (!(created.instance instanceof Component)) {
                    response.put("error", created.failure == null ? "Nao foi possivel instanciar "
                            + className : Instantiator.describe(created.failure));
                    return response;
                }
                List<Object> warnings = new ArrayList<Object>();
                injector.apply(created.instance, warnings);
                Lifecycle.run(created.instance, strings(Json.array(request.get("designInit"))), warnings);
                response.put("warnings", warnings);
                Window window;
                if (created.instance instanceof Window) {
                    window = (Window) created.instance;
                    if (window instanceof JFrame
                            && ((JFrame) window).getDefaultCloseOperation() == WindowConstants.EXIT_ON_CLOSE) {
                        ((JFrame) window).setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    }
                    if (window.getWidth() <= 0 || window.getHeight() <= 0) {
                        window.pack();
                    }
                } else {
                    JFrame frame = new JFrame("Preview - " + type.getSimpleName());
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    frame.getContentPane().add((Component) created.instance, BorderLayout.CENTER);
                    frame.pack();
                    window = frame;
                }
                window.setLocationRelativeTo(null);
                window.setVisible(true);
                window.toFront();
                previewWindow = window;
                return response;
            }
        });
    }

    private Map<String, Object> interpret(final Map<String, Object> request, byte[][] blob) throws Exception {
        if (viewInstance == null || renderRoot == null) {
            throw new IllegalStateException("Nenhuma tela carregada para recuperar");
        }
        final List<Object> statements = Json.array(request.get("statements"));
        final List<Object> prior = Json.array(request.get("priorLocals"));
        final Map<String, Object> bindings = Json.object(request.get("bindings"));
        final boolean stubs = Json.bool(request, "stubs", true);
        final List<Object> warnings = new ArrayList<Object>();
        final List<Object> results = new ArrayList<Object>();
        final List<String> synthesized = new ArrayList<String>();
        onEdt(new Callable<Object>() {
            @Override
            public Object call() {
                Thread.currentThread().setContextClassLoader(workspace);
                Beans.setDesignTime(true);
                try {
                    Interpreter interpreter = new Interpreter(viewInstance, workspace, stubs, warnings);
                    for (Map.Entry<String, Object> binding : bindings.entrySet()) {
                        Component bound = snapshots.nodes().get(String.valueOf(binding.getValue()));
                        if (bound != null) {
                            interpreter.bind(binding.getKey(), bound);
                        }
                    }
                    for (Object entry : prior) {
                        Map<String, Object> local = Json.object(entry);
                        interpreter.declarePrior(Json.string(local, "name"), Json.array(local.get("type")));
                    }
                    for (Object entry : statements) {
                        results.add(interpreter.run(Json.object(entry)));
                    }
                    synthesized.addAll(interpreter.synthesized());
                } finally {
                    Beans.setDesignTime(false);
                }
                return null;
            }
        });
        Map<String, Object> response = render(blob);
        response.put("results", results);
        response.put("synthesized", synthesized);
        response.put("warnings", warnings);
        return response;
    }

    private DesignInjector injector(Map<String, Object> request, boolean stubs) {
        return new DesignInjector(Json.array(request.get("injections")),
                Json.object(request.get("designValues")), stubs, workspace);
    }

    private Instantiator.Result instantiate(Class<?> type, Map<String, Object> constructor, boolean stubs) {
        List<Object> types = constructor.containsKey("types") ? Json.array(constructor.get("types")) : null;
        List<Object> values = constructor.containsKey("values") ? Json.array(constructor.get("values")) : null;
        String factory = Json.string(constructor, "factory");
        List<String> typeNames = types == null ? null : strings(types);
        return new Instantiator(workspace, stubs).instantiate(type, typeNames, values, factory);
    }

    private Component stage(Component component, int width, int height) {
        if (component instanceof Window) {
            Window window = (Window) component;
            ownedWindow = window;
            if (window.isVisible()) {
                window.setVisible(false);
            }
            if (width > 0 && height > 0) {
                window.setSize(width, height);
                window.addNotify();
            } else if (window.getWidth() <= 0 || window.getHeight() <= 0) {
                window.pack();
                if (window.getWidth() < 50 || window.getHeight() < 50) {
                    window.setSize(Math.max(window.getWidth(), DEFAULT_WIDTH),
                            Math.max(window.getHeight(), DEFAULT_HEIGHT));
                }
            } else {
                window.addNotify();
            }
            window.validate();
            if (window instanceof RootPaneContainer) {
                return ((RootPaneContainer) window).getRootPane();
            }
            return window;
        }
        JFrame stage = new JFrame();
        stage.setUndecorated(true);
        stage.setFocusableWindowState(false);
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(component, BorderLayout.CENTER);
        stage.setContentPane(holder);
        Dimension preferred = component.getPreferredSize();
        int targetWidth = width > 0 ? width : preferred == null ? DEFAULT_WIDTH : preferred.width;
        int targetHeight = height > 0 ? height : preferred == null ? DEFAULT_HEIGHT : preferred.height;
        if (targetWidth <= 1 || targetHeight <= 1) {
            targetWidth = DEFAULT_WIDTH;
            targetHeight = DEFAULT_HEIGHT;
        }
        stage.pack();
        stage.setSize(targetWidth, targetHeight);
        stage.validate();
        ownedWindow = stage;
        return component;
    }

    private void disposeView() {
        Window window = ownedWindow;
        ownedWindow = null;
        viewInstance = null;
        renderRoot = null;
        snapshots.nodes().clear();
        if (window != null) {
            try {
                window.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    private void closePreview() {
        Window window = previewWindow;
        previewWindow = null;
        if (window != null) {
            try {
                window.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    private Component node(String id) {
        Component component = id == null ? null : snapshots.nodes().get(id);
        if (component == null) {
            throw new IllegalArgumentException("Componente nao encontrado: " + id);
        }
        return component;
    }

    private static String windowTitle(Object instance) {
        if (instance instanceof java.awt.Frame) {
            return ((java.awt.Frame) instance).getTitle();
        }
        if (instance instanceof java.awt.Dialog) {
            return ((java.awt.Dialog) instance).getTitle();
        }
        return null;
    }

    private static Map<String, Object> constructorInfo(Instantiator.Result created) {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("types", created.parameterTypes);
        info.put("values", created.argumentValues);
        if (created.factory != null) {
            info.put("factory", created.factory);
        }
        return info;
    }

    private String applyLookAndFeel(String requested) {
        if (requested != null && !requested.trim().isEmpty()) {
            try {
                UIManager.setLookAndFeel(requested.trim());
                UIManager.getDefaults().put("ClassLoader", workspace);
            } catch (Throwable error) {
                System.err.println("Look and feel indisponivel " + requested + ": "
                        + Instantiator.describe(error));
            }
        }
        return UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
    }

    private URLClassLoader newWorkspaceLoader() throws Exception {
        return new URLClassLoader(urls(new ArrayList<Object>(workspacePaths)).toArray(new URL[0]),
                libraries);
    }

    private void requireInit() {
        if (workspace == null) {
            throw new IllegalStateException("Host nao inicializado");
        }
    }

    private static List<URL> urls(List<Object> paths) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        for (Object path : paths) {
            if (path != null) {
                urls.add(new File(String.valueOf(path)).toURI().toURL());
            }
        }
        return urls;
    }

    private static List<String> strings(List<Object> values) {
        List<String> strings = new ArrayList<String>();
        for (Object value : values) {
            strings.add(String.valueOf(value));
        }
        return strings;
    }

    private static String stackTrace(Throwable error) {
        if (error == null) {
            return null;
        }
        java.io.StringWriter out = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (Exception ignored) {
        }
    }

    static <T> T onEdt(final Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(work.call());
                } catch (Exception error) {
                    failure.set(error);
                } catch (Throwable error) {
                    failure.set(new IllegalStateException(Instantiator.describe(error), error));
                }
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
