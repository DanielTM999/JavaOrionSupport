package dtm.ide.spring;

import java.util.Locale;
import java.util.Map;

public enum SpringStereotype {

    COMPONENT("Component"),
    SERVICE("Service"),
    REPOSITORY("Repository"),
    CONTROLLER("Controller"),
    REST_CONTROLLER("RestController"),
    CONFIGURATION("Configuration"),
    CONFIGURATION_PROPERTIES("ConfigurationProperties"),
    BOOT_APPLICATION("SpringBootApplication"),
    BEAN_METHOD("Bean");

    private static final Map<String, SpringStereotype> BY_ANNOTATION = Map.ofEntries(
            Map.entry("component", COMPONENT),
            Map.entry("service", SERVICE),
            Map.entry("repository", REPOSITORY),
            Map.entry("controller", CONTROLLER),
            Map.entry("restcontroller", REST_CONTROLLER),
            Map.entry("configuration", CONFIGURATION),
            Map.entry("configurationproperties", CONFIGURATION_PROPERTIES),
            Map.entry("springbootapplication", BOOT_APPLICATION),
            Map.entry("bean", BEAN_METHOD),
            Map.entry("controlleradvice", CONTROLLER),
            Map.entry("restcontrolleradvice", REST_CONTROLLER),
            Map.entry("feignclient", COMPONENT),
            Map.entry("enableconfigurationproperties", CONFIGURATION));

    private final String annotation;

    SpringStereotype(String annotation) {
        this.annotation = annotation;
    }

    public String annotation() {
        return annotation;
    }

    public String displayName() {
        return "@" + annotation;
    }

    public boolean isWebController() {
        return this == CONTROLLER || this == REST_CONTROLLER;
    }

    public boolean declaresBeans() {
        return this == CONFIGURATION || this == BOOT_APPLICATION;
    }

    public static SpringStereotype fromAnnotation(String rawAnnotation) {
        if (rawAnnotation == null || rawAnnotation.isBlank()) {
            return null;
        }
        String name = rawAnnotation.trim();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            name = name.substring(lastDot + 1);
        }
        return BY_ANNOTATION.get(name.toLowerCase(Locale.ROOT));
    }
}
