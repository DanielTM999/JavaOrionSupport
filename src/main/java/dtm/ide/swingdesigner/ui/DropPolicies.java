package dtm.ide.swingdesigner.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dtm.ide.swingdesigner.form.LayoutCode;
import dtm.ide.swingdesigner.runtime.SnapshotNode;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

final class DropPolicies {

    static final String ABSOLUTE = "absolute";
    static final String BORDER = "border";
    static final String GRID_BAG = "gridbag";
    static final String BOX = "box";
    static final String CARD = "card";
    static final String FLOW = "flow";

    record Preview(String parentNodeId, String policy, int swingIndex, String constraints, Rectangle indicator,
                   boolean line, Rectangle absoluteBounds, String hint) {
    }

    private DropPolicies() {
    }

    static Optional<Preview> resolve(SnapshotNode root, Point point, Set<String> excluded,
                                     Function<String, String> policyOfLayout, Dimension size) {
        if (root == null || point == null) {
            return Optional.empty();
        }
        List<SnapshotNode> path = new ArrayList<>();
        collectPath(root, point, excluded, path);
        SnapshotNode container = null;
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).hasLayout()) {
                container = path.get(i);
                break;
            }
        }
        if (container == null) {
            return Optional.empty();
        }
        String layoutClass = container.layoutClass();
        String policy = LayoutCode.ABSOLUTE.equals(layoutClass) ? ABSOLUTE
                : Optional.ofNullable(policyOfLayout.apply(layoutClass)).orElse(FLOW);
        Rectangle bounds = container.bounds();
        List<SnapshotNode> children = new ArrayList<>();
        for (SnapshotNode child : container.children()) {
            if (child.visible() && !excluded.contains(child.id())) {
                children.add(child);
            }
        }
        return Optional.of(switch (policy) {
            case BORDER -> border(container, bounds, point);
            case ABSOLUTE -> absolute(container, bounds, point, size);
            case GRID_BAG -> gridBag(container, bounds, point);
            case BOX -> sequence(container, children, point, isVertical(container.layout()), policy);
            default -> sequence(container, children, point, false, policy);
        });
    }

    private static void collectPath(SnapshotNode node, Point point, Set<String> excluded, List<SnapshotNode> path) {
        if (!node.visible() || excluded.contains(node.id()) || !node.bounds().contains(point)) {
            return;
        }
        path.add(node);
        for (int i = node.children().size() - 1; i >= 0; i--) {
            SnapshotNode child = node.children().get(i);
            if (child.visible() && !excluded.contains(child.id()) && child.bounds().contains(point)) {
                collectPath(child, point, excluded, path);
                return;
            }
        }
    }

    private static Preview border(SnapshotNode container, Rectangle bounds, Point point) {
        double fx = (point.x - bounds.x) / (double) Math.max(1, bounds.width);
        double fy = (point.y - bounds.y) / (double) Math.max(1, bounds.height);
        String region;
        Rectangle area;
        int quarterWidth = Math.max(8, bounds.width / 4);
        int quarterHeight = Math.max(8, bounds.height / 4);
        if (fy < 0.25) {
            region = "NORTH";
            area = new Rectangle(bounds.x, bounds.y, bounds.width, quarterHeight);
        } else if (fy > 0.75) {
            region = "SOUTH";
            area = new Rectangle(bounds.x, bounds.y + bounds.height - quarterHeight, bounds.width, quarterHeight);
        } else if (fx < 0.25) {
            region = "WEST";
            area = new Rectangle(bounds.x, bounds.y + quarterHeight, quarterWidth, bounds.height - quarterHeight * 2);
        } else if (fx > 0.75) {
            region = "EAST";
            area = new Rectangle(bounds.x + bounds.width - quarterWidth, bounds.y + quarterHeight, quarterWidth,
                    bounds.height - quarterHeight * 2);
        } else {
            region = "CENTER";
            area = new Rectangle(bounds.x + quarterWidth, bounds.y + quarterHeight, bounds.width - quarterWidth * 2,
                    bounds.height - quarterHeight * 2);
        }
        return new Preview(container.id(), BORDER, -1, "java.awt.BorderLayout." + region, area, false, null,
                region);
    }

    private static Preview absolute(SnapshotNode container, Rectangle bounds, Point point, Dimension size) {
        int width = size == null || size.width <= 0 ? 100 : size.width;
        int height = size == null || size.height <= 0 ? 24 : size.height;
        Rectangle relative = new Rectangle(point.x - bounds.x, point.y - bounds.y, width, height);
        Rectangle indicator = new Rectangle(point.x, point.y, width, height);
        return new Preview(container.id(), ABSOLUTE, -1, null, indicator, false, relative,
                relative.x + ", " + relative.y);
    }

    private static Preview gridBag(SnapshotNode container, Rectangle bounds, Point point) {
        JsonNode layout = container.layout();
        int originX = layout.path("originX").asInt(bounds.x);
        int originY = layout.path("originY").asInt(bounds.y);
        List<Integer> columns = numbers(layout.path("columnWidths"));
        List<Integer> rows = numbers(layout.path("rowHeights"));
        int column = cell(columns, point.x - originX);
        int row = cell(rows, point.y - originY);
        int x = originX + sum(columns, column);
        int y = originY + sum(rows, row);
        int width = column < columns.size() ? columns.get(column) : Math.max(24, bounds.x + bounds.width - x);
        int height = row < rows.size() ? rows.get(row) : Math.max(24, bounds.y + bounds.height - y);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("gridx", String.valueOf(column));
        values.put("gridy", String.valueOf(row));
        String constraints = LayoutCode.gridBag(values, name -> name);
        return new Preview(container.id(), GRID_BAG, -1, constraints, new Rectangle(x, y, width, height), false,
                null, "coluna " + column + ", linha " + row);
    }

    private static Preview sequence(SnapshotNode container, List<SnapshotNode> children, Point point,
                                    boolean vertical, String policy) {
        int index = -1;
        SnapshotNode before = null;
        for (SnapshotNode child : children) {
            Rectangle area = child.bounds();
            boolean precedes;
            if (vertical) {
                precedes = point.y < area.y + area.height / 2;
            } else {
                boolean above = point.y < area.y;
                boolean sameRow = point.y >= area.y && point.y <= area.y + area.height;
                precedes = above || sameRow && point.x < area.x + area.width / 2;
            }
            if (precedes) {
                index = child.index();
                before = child;
                break;
            }
        }
        Rectangle indicator;
        if (before != null) {
            Rectangle area = before.bounds();
            indicator = vertical ? new Rectangle(area.x, area.y - 1, area.width, 2)
                    : new Rectangle(area.x - 1, area.y, 2, area.height);
        } else if (!children.isEmpty()) {
            Rectangle area = children.getLast().bounds();
            indicator = vertical ? new Rectangle(area.x, area.y + area.height - 1, area.width, 2)
                    : new Rectangle(area.x + area.width - 1, area.y, 2, area.height);
        } else {
            Rectangle bounds = container.bounds();
            indicator = new Rectangle(bounds.x + 2, bounds.y + 2, Math.max(4, bounds.width - 4),
                    Math.max(4, bounds.height - 4));
        }
        boolean line = !children.isEmpty();
        String constraints = CARD.equals(policy) ? "${child.name}" : null;
        return new Preview(container.id(), policy, index, constraints, indicator, line, null,
                index < 0 ? "no fim" : "posicao " + index);
    }

    private static boolean isVertical(JsonNode layout) {
        int axis = layout.path("axis").asInt(1);
        return axis == 1 || axis == 3;
    }

    private static List<Integer> numbers(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asInt());
        }
        return values;
    }

    private static int cell(List<Integer> sizes, int offset) {
        int position = 0;
        for (int i = 0; i < sizes.size(); i++) {
            position += sizes.get(i);
            if (offset < position) {
                return Math.max(0, i);
            }
        }
        return sizes.size();
    }

    private static int sum(List<Integer> sizes, int count) {
        int total = 0;
        for (int i = 0; i < count && i < sizes.size(); i++) {
            total += sizes.get(i);
        }
        return total;
    }
}
