package dtm.ide.swingdesigner;

import dtm.ide.editor.JavaSourceText;
import dtm.ide.swingdesigner.catalog.ClasspathIndex;
import dtm.ide.swingdesigner.catalog.ComponentCatalog;
import dtm.ide.swingdesigner.catalog.JarHeaderCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SwingSourceDetector {

    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("\\bimport\\s+(static\\s+)?([\\w.]+)(\\.\\*)?\\s*;");
    private static final Pattern UI_REFERENCE = Pattern.compile("\\b(javax\\.swing|java\\.awt)\\b");
    private static final ClasspathIndex JDK = ClasspathIndex.build(List.of(), JarHeaderCache.memoryOnly());

    private SwingSourceDetector() {
    }

    public static boolean isDrawableSource(String className, String source, Optional<ComponentCatalog> catalog) {
        if (className == null || source == null) {
            return false;
        }
        if (catalog.isPresent() && catalog.get().index().contains(className)) {
            return catalog.get().isDrawable(className);
        }
        String code = JavaSourceText.blankStringContents(JavaSourceText.blankComments(source));
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        Optional<String> superName = superclassOf(code, simpleName);
        if (superName.isEmpty()) {
            return false;
        }
        for (String candidate : candidates(code, superName.get())) {
            if (catalog.isPresent() && catalog.get().index().contains(candidate)) {
                return catalog.get().isDrawable(candidate);
            }
            if (JDK.contains(candidate)) {
                return JDK.isDrawable(candidate);
            }
        }
        return UI_REFERENCE.matcher(code).find();
    }

    static Optional<String> superclassOf(String code, String simpleName) {
        Pattern declaration = Pattern.compile("\\bclass\\s+" + Pattern.quote(simpleName)
                + "\\b\\s*(<[^{]*?>)?\\s*extends\\s+([\\w.$]+)");
        Matcher matcher = declaration.matcher(code);
        return matcher.find() ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    static List<String> candidates(String code, String superName) {
        List<String> candidates = new ArrayList<>();
        int dot = superName.indexOf('.');
        String head = dot < 0 ? superName : superName.substring(0, dot);
        String tail = dot < 0 ? "" : superName.substring(dot).replace('.', '$');
        if (dot > 0 && Character.isLowerCase(superName.charAt(0))) {
            candidates.add(superName);
        }
        List<String> wildcards = new ArrayList<>();
        Matcher imports = IMPORT.matcher(code);
        while (imports.find()) {
            if (imports.group(1) != null) {
                continue;
            }
            String imported = imports.group(2);
            if (imports.group(3) != null) {
                wildcards.add(imported);
            } else if (imported.endsWith("." + head)) {
                candidates.add(imported + tail);
            }
        }
        Matcher packageMatcher = PACKAGE.matcher(code);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        candidates.add(packageName.isEmpty() ? head + tail : packageName + "." + head + tail);
        for (String wildcard : wildcards) {
            candidates.add(wildcard + "." + head + tail);
        }
        candidates.add("java.lang." + head + tail);
        return candidates;
    }
}
