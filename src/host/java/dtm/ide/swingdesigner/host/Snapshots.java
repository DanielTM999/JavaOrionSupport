package dtm.ide.swingdesigner.host;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JToolTip;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Snapshots {

    private static final Class<?>[] LEAF_TYPES = {
            AbstractButton.class, JComboBox.class, JSpinner.class, JFileChooser.class,
            JColorChooser.class, JTable.class, JTree.class, JList.class, JTextComponent.class,
            JScrollBar.class, JSlider.class, JProgressBar.class, JLabel.class, JToolTip.class,
            JTableHeader.class
    };

    private final Map<String, Component> nodes = new LinkedHashMap<String, Component>();
    private final Map<Component, String> fieldNames = new IdentityHashMap<Component, String>();
    private final Map<Component, Boolean> ownFields = new IdentityHashMap<Component, Boolean>();
    private final Map<Component, String> roles = new IdentityHashMap<Component, String>();
    private Object owner;

    Map<String, Component> nodes() {
        return nodes;
    }

    Map<String, Object> describe(Component root, Object owner) {
        nodes.clear();
        fieldNames.clear();
        ownFields.clear();
        roles.clear();
        this.owner = owner;
        collectFields(owner);
        Map<String, Object> node = node(root, root, "0");
        if (owner instanceof Component && owner != root) {
            nodes.put("0", (Component) owner);
            node.put("className", owner.getClass().getName());
            node.put("renderedAs", root.getClass().getName());
        }
        return node;
    }

    static byte[] render(Component root) throws IOException {
        int width = Math.max(1, root.getWidth());
        int height = Math.max(1, root.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color background = root.getBackground();
            if (background != null) {
                graphics.setColor(background);
                graphics.fillRect(0, 0, width, height);
            }
            root.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Map<String, Object> node(Component component, Component root, String id) {
        nodes.put(id, component);
        if (!isJdk(component.getClass())) {
            collectFields(component);
        }
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", id);
        node.put("className", component.getClass().getName());
        String field = fieldNames.get(component);
        if (field != null) {
            node.put("field", field);
            if (Boolean.TRUE.equals(ownFields.get(component))) {
                node.put("ownField", Boolean.TRUE);
            }
        }
        if (component instanceof JRootPane) {
            JRootPane rootPane = (JRootPane) component;
            if (rootPane.getJMenuBar() != null) {
                roles.put(rootPane.getJMenuBar(), "menuBar");
            }
            roles.put(rootPane.getContentPane(), "contentPane");
        }
        String role = roles.get(component);
        if (role != null) {
            node.put("role", role);
        }
        String name = component.getName();
        if (name != null && !name.startsWith("null.")) {
            node.put("name", name);
        }
        Rectangle bounds = component == root ? new Rectangle(0, 0, root.getWidth(), root.getHeight())
                : SwingUtilities.convertRectangle(component.getParent(),
                component.getBounds(), root);
        node.put("x", bounds.x);
        node.put("y", bounds.y);
        node.put("width", bounds.width);
        node.put("height", bounds.height);
        node.put("visible", component.isVisible());
        Container parent = component.getParent();
        if (component != root && parent != null) {
            node.put("index", indexIn(parent, component));
            Object constraints = constraints(parent, component);
            if (constraints != null) {
                node.put("constraints", constraints);
            }
        }
        Dimension preferred = safePreferred(component);
        if (preferred != null) {
            node.put("prefWidth", preferred.width);
            node.put("prefHeight", preferred.height);
        }
        if (component instanceof Container && !(component instanceof JRootPane) && !isLeaf(component)) {
            Map<String, Object> layout = layout((Container) component, root);
            if (layout != null) {
                node.put("layout", layout);
            }
        }
        List<Object> children = new ArrayList<Object>();
        int index = 0;
        for (Component child : children(component)) {
            if (child == null) {
                continue;
            }
            children.add(node(child, root, id + "/" + index++));
        }
        if (!children.isEmpty()) {
            node.put("children", children);
        }
        return node;
    }

    private static List<Component> children(Component component) {
        List<Component> result = new ArrayList<Component>();
        if (component instanceof JRootPane) {
            JRootPane rootPane = (JRootPane) component;
            if (rootPane.getJMenuBar() != null) {
                result.add(rootPane.getJMenuBar());
            }
            result.add(rootPane.getContentPane());
            return result;
        }
        if (component instanceof JScrollPane) {
            JViewport viewport = ((JScrollPane) component).getViewport();
            if (viewport != null && viewport.getView() != null) {
                result.add(viewport.getView());
            }
            return result;
        }
        if (component instanceof JViewport) {
            Component view = ((JViewport) component).getView();
            if (view != null) {
                result.add(view);
            }
            return result;
        }
        if (!(component instanceof Container) || isLeaf(component)) {
            return result;
        }
        for (Component child : ((Container) component).getComponents()) {
            if (child.getClass().getName().startsWith("javax.swing.plaf.")
                    || child.getClass().getName().startsWith("com.sun.")
                    || child.getClass().getName().startsWith("sun.")) {
                continue;
            }
            result.add(child);
        }
        return result;
    }

    private static int indexIn(Container parent, Component child) {
        Component[] siblings = parent.getComponents();
        for (int i = 0; i < siblings.length; i++) {
            if (siblings[i] == child) {
                return i;
            }
        }
        return -1;
    }

    private static Dimension safePreferred(Component component) {
        try {
            return component.getPreferredSize();
        } catch (Throwable error) {
            return null;
        }
    }

    static Object constraints(Container parent, Component child) {
        LayoutManager layout = parent.getLayout();
        try {
            if (layout instanceof BorderLayout) {
                Object value = ((BorderLayout) layout).getConstraints(child);
                return value == null ? null : String.valueOf(value);
            }
            if (layout instanceof GridBagLayout) {
                GridBagConstraints value = ((GridBagLayout) layout).getConstraints(child);
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("gridx", value.gridx);
                result.put("gridy", value.gridy);
                result.put("gridwidth", value.gridwidth);
                result.put("gridheight", value.gridheight);
                result.put("weightx", value.weightx);
                result.put("weighty", value.weighty);
                result.put("anchor", value.anchor);
                result.put("fill", value.fill);
                Insets insets = value.insets;
                if (insets != null) {
                    Map<String, Object> margin = new LinkedHashMap<String, Object>();
                    margin.put("top", insets.top);
                    margin.put("left", insets.left);
                    margin.put("bottom", insets.bottom);
                    margin.put("right", insets.right);
                    result.put("insets", margin);
                }
                result.put("ipadx", value.ipadx);
                result.put("ipady", value.ipady);
                return result;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final String[] LAYOUT_GETTERS = {"getHgap", "getVgap", "getRows", "getColumns",
            "getAlignment", "getAxis"};

    private static Map<String, Object> layout(Container container, Component root) {
        LayoutManager layout = container.getLayout();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (layout == null) {
            result.put("className", "null");
            return result;
        }
        result.put("className", layout.getClass().getName());
        for (String getter : LAYOUT_GETTERS) {
            try {
                Method method = layout.getClass().getMethod(getter);
                if (method.getReturnType() == int.class) {
                    String name = Character.toLowerCase(getter.charAt(3)) + getter.substring(4);
                    result.put(name, method.invoke(layout));
                }
            } catch (Throwable ignored) {
            }
        }
        if (layout instanceof GridBagLayout) {
            try {
                GridBagLayout bag = (GridBagLayout) layout;
                int[][] dimensions = bag.getLayoutDimensions();
                Point origin = bag.getLayoutOrigin();
                Point absolute = container == root ? origin
                        : SwingUtilities.convertPoint(container, origin, root);
                result.put("originX", absolute.x);
                result.put("originY", absolute.y);
                result.put("columnWidths", toList(dimensions[0]));
                result.put("rowHeights", toList(dimensions[1]));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static List<Object> toList(int[] values) {
        List<Object> list = new ArrayList<Object>();
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private static boolean isLeaf(Component component) {
        Class<?> type = component.getClass();
        for (Class<?> leaf : LEAF_TYPES) {
            if (leaf.isInstance(component)) {
                return isJdk(type) || !hasUserChildren(component);
            }
        }
        return false;
    }

    private static boolean hasUserChildren(Component component) {
        if (!(component instanceof Container)) {
            return false;
        }
        for (Component child : ((Container) component).getComponents()) {
            if (!isJdk(child.getClass())) {
                return true;
            }
        }
        return false;
    }

    private void collectFields(Object owner) {
        if (owner == null) {
            return;
        }
        Class<?> type = owner.getClass();
        while (type != null && !isJdk(type)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !Component.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value instanceof Component && !fieldNames.containsKey(value)) {
                        fieldNames.put((Component) value, field.getName());
                        if (owner == this.owner) {
                            ownFields.put((Component) value, Boolean.TRUE);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    static boolean isJdk(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("jdk.");
    }
}
