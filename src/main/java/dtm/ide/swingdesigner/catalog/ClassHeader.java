package dtm.ide.swingdesigner.catalog;

import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Objects;

public record ClassHeader(String name,
                          String superName,
                          List<String> interfaces,
                          int access,
                          boolean accessible,
                          int entryIndex) {

    public static final int JDK_ENTRY = -1;

    public ClassHeader {
        Objects.requireNonNull(name, "name");
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
    }

    public boolean isInterface() {
        return (access & AccessFlag.INTERFACE.mask()) != 0;
    }

    public boolean isAbstract() {
        return (access & AccessFlag.ABSTRACT.mask()) != 0;
    }

    public boolean isEnum() {
        return (access & AccessFlag.ENUM.mask()) != 0;
    }

    public boolean isAnnotation() {
        return (access & AccessFlag.ANNOTATION.mask()) != 0;
    }

    public boolean isJdk() {
        return entryIndex == JDK_ENTRY;
    }

    public boolean isConcreteClass() {
        return !isInterface() && !isAbstract() && !isEnum() && !isAnnotation();
    }

    ClassHeader withEntry(int index) {
        return new ClassHeader(name, superName, interfaces, access, accessible, index);
    }
}
