package dtm.ide.ui;

import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringEndpoint;
import dtm.ide.spring.SpringStereotype;
import dtm.ide.spring.live.SpringActuatorClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SpringExplorerModel {

    static final String FRAMEWORK_GROUP = "framework";

    record ControllerEndpoints(String handlerType, String displayName,
                               List<SpringEndpoint> endpoints) {
    }

    record MappingGroup(String key, String displayName,
                        List<SpringActuatorClient.LiveMapping> mappings) {
    }

    private SpringExplorerModel() {
    }

    static Map<SpringStereotype, List<SpringBean>> beansByStereotype(
            List<SpringBean> beans, String query) {
        Map<SpringStereotype, List<SpringBean>> groups = new LinkedHashMap<>();
        List<SpringBean> source = beans == null ? List.of() : beans;
        for (SpringStereotype stereotype : SpringStereotype.values()) {
            List<SpringBean> matching = source.stream()
                    .filter(bean -> bean.stereotype() == stereotype)
                    .filter(bean -> matchesBean(bean, query))
                    .sorted(Comparator.comparing(SpringBean::simpleName,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (!matching.isEmpty()) {
                groups.put(stereotype, matching);
            }
        }
        return groups;
    }

    static List<ControllerEndpoints> endpointsByController(
            List<SpringEndpoint> endpoints, String query) {
        Map<String, List<SpringEndpoint>> grouped = new LinkedHashMap<>();
        List<SpringEndpoint> source = endpoints == null ? List.of() : endpoints;
        source.stream().filter(endpoint -> matchesEndpoint(endpoint, query))
                .forEach(endpoint -> grouped.computeIfAbsent(endpoint.handlerType(),
                        ignored -> new ArrayList<>()).add(endpoint));

        Comparator<SpringEndpoint> endpointOrder = Comparator
                .comparing(SpringEndpoint::path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SpringEndpoint::method, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SpringEndpoint::handlerName, String.CASE_INSENSITIVE_ORDER);
        return grouped.entrySet().stream()
                .map(entry -> new ControllerEndpoints(entry.getKey(),
                        SpringBean.simpleNameOf(entry.getKey()),
                        entry.getValue().stream().sorted(endpointOrder).toList()))
                .sorted(Comparator.comparing(ControllerEndpoints::displayName,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ControllerEndpoints::handlerType,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static List<SpringActuatorClient.LiveBean> filterLiveBeans(
            List<SpringActuatorClient.LiveBean> beans, String query) {
        String needle = normalize(query);
        List<SpringActuatorClient.LiveBean> source = beans == null ? List.of() : beans;
        return source.stream().filter(bean -> needle.isEmpty()
                        || contains(bean.name(), needle)
                        || contains(bean.type(), needle)
                        || contains(bean.scope(), needle)
                        || bean.dependencies().stream().anyMatch(value -> contains(value, needle)))
                .sorted(Comparator.comparing(SpringActuatorClient.LiveBean::name,
                        String.CASE_INSENSITIVE_ORDER)).toList();
    }

    static List<SpringActuatorClient.LiveProperty> filterLiveProperties(
            List<SpringActuatorClient.LiveProperty> properties, String query) {
        String needle = normalize(query);
        List<SpringActuatorClient.LiveProperty> source =
                properties == null ? List.of() : properties;
        return source.stream().filter(property -> needle.isEmpty()
                        || contains(property.key(), needle)
                        || contains(property.value(), needle)
                        || contains(property.source(), needle))
                .sorted(Comparator.comparing(SpringActuatorClient.LiveProperty::key,
                        String.CASE_INSENSITIVE_ORDER)).toList();
    }

    static List<MappingGroup> mappingsByController(
            List<SpringActuatorClient.LiveMapping> mappings, String query,
            String frameworkLabel) {
        Map<String, List<SpringActuatorClient.LiveMapping>> grouped = new LinkedHashMap<>();
        List<SpringActuatorClient.LiveMapping> source = mappings == null ? List.of() : mappings;
        source.stream().filter(mapping -> matchesMapping(mapping, query)).forEach(mapping -> {
            String controller = mappingController(mapping.handler());
            grouped.computeIfAbsent(controller, ignored -> new ArrayList<>()).add(mapping);
        });
        Comparator<SpringActuatorClient.LiveMapping> mappingOrder = Comparator
                .comparing(SpringActuatorClient.LiveMapping::path,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SpringActuatorClient.LiveMapping::method,
                        String.CASE_INSENSITIVE_ORDER);
        return grouped.entrySet().stream().map(entry -> {
                    String display = FRAMEWORK_GROUP.equals(entry.getKey())
                            ? frameworkLabel : SpringBean.simpleNameOf(entry.getKey());
                    return new MappingGroup(entry.getKey(), display,
                            entry.getValue().stream().sorted(mappingOrder).toList());
                })
                .sorted(Comparator.comparing(
                                (MappingGroup group) -> FRAMEWORK_GROUP.equals(group.key()))
                        .thenComparing(MappingGroup::displayName,
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static String mappingController(String handler) {
        if (handler == null || handler.isBlank()) {
            return FRAMEWORK_GROUP;
        }
        String value = handler.trim();
        int separator = value.indexOf('#');
        if (separator <= 0) {
            return FRAMEWORK_GROUP;
        }
        String controller = value.substring(0, separator).trim();
        return controller.isBlank() ? FRAMEWORK_GROUP : controller;
    }

    private static boolean matchesBean(SpringBean bean, String query) {
        String needle = normalize(query);
        return needle.isEmpty()
                || contains(bean.name(), needle)
                || contains(bean.type(), needle)
                || contains(bean.simpleName(), needle)
                || contains(bean.stereotype().displayName(), needle)
                || contains(bean.qualifier(), needle)
                || bean.profiles().stream().anyMatch(value -> contains(value, needle));
    }

    private static boolean matchesEndpoint(SpringEndpoint endpoint, String query) {
        String needle = normalize(query);
        return needle.isEmpty()
                || contains(endpoint.method(), needle)
                || contains(endpoint.path(), needle)
                || contains(endpoint.handlerType(), needle)
                || contains(endpoint.handlerName(), needle)
                || endpoint.produces().stream().anyMatch(value -> contains(value, needle));
    }

    private static boolean matchesMapping(SpringActuatorClient.LiveMapping mapping, String query) {
        String needle = normalize(query);
        return needle.isEmpty()
                || contains(mapping.method(), needle)
                || contains(mapping.path(), needle)
                || contains(mapping.handler(), needle);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }
}
