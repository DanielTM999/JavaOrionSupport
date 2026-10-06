package dtm.ide.swingdesigner.catalog;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ClassHeaders {

    private ClassHeaders() {
    }

    static Optional<ClassHeader> parse(byte[] bytes, int entryIndex) {
        try {
            return Optional.of(read(ClassFile.of().parse(bytes), entryIndex));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static ClassHeader read(ClassModel model, int entryIndex) {
        String internalName = model.thisClass().asInternalName();
        String superName = model.superclass().map(ClassEntry::asInternalName)
                .map(ClassHeaders::binaryName).orElse(null);
        List<String> interfaces = new ArrayList<>();
        for (ClassEntry entry : model.interfaces()) {
            interfaces.add(binaryName(entry.asInternalName()));
        }
        int access = model.flags().flagsMask();
        return new ClassHeader(binaryName(internalName), superName, interfaces, access,
                accessible(model, internalName, access), entryIndex);
    }

    static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    static String typeName(ClassDesc desc) {
        if (desc.isPrimitive()) {
            return desc.displayName();
        }
        if (desc.isArray()) {
            return typeName(desc.componentType()) + "[]";
        }
        String descriptor = desc.descriptorString();
        return binaryName(descriptor.substring(1, descriptor.length() - 1));
    }

    private static boolean accessible(ClassModel model, String internalName, int access) {
        boolean isPublic = (access & AccessFlag.PUBLIC.mask()) != 0;
        if (!isPublic) {
            return false;
        }
        if (internalName.indexOf('$') < 0) {
            return true;
        }
        Optional<InnerClassesAttribute> inner = model.findAttribute(Attributes.innerClasses());
        if (inner.isEmpty()) {
            return true;
        }
        for (InnerClassInfo info : inner.get().classes()) {
            if (!info.innerClass().asInternalName().equals(internalName)) {
                continue;
            }
            int flags = info.flagsMask();
            return info.outerClass().isPresent() && info.innerName().isPresent()
                    && (flags & AccessFlag.PUBLIC.mask()) != 0
                    && (flags & AccessFlag.STATIC.mask()) != 0;
        }
        return true;
    }
}
