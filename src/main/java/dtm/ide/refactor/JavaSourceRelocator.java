package dtm.ide.refactor;

import dtm.ide.editor.JavaImportInserter;
import dtm.ide.editor.JavaSourceText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaSourceRelocator {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^[ \\t\\uFEFF]*package\\s+([\\w$.]+)\\s*;[ \\t]*(\\r?\\n)?");

    private JavaSourceRelocator() {
    }

    public static String withPackage(String text, String newPackage) {
        String source = text == null ? "" : text;
        String target = newPackage == null ? "" : newPackage.strip();
        Matcher matcher = PACKAGE.matcher(structural(source));
        if (matcher.find()) {
            if (target.isEmpty()) {
                int end = matcher.end();
                while (end < source.length() && (source.charAt(end) == '\r' || source.charAt(end) == '\n')) {
                    end++;
                }
                int start = matcher.start();
                while (start < source.length() && source.charAt(start) == '﻿') {
                    start++;
                }
                return source.substring(0, start) + source.substring(end);
            }
            return source.substring(0, matcher.start(1)) + target + source.substring(matcher.end(1));
        }
        if (target.isEmpty()) {
            return source;
        }
        String lineBreak = lineBreakOf(source);
        int insertAt = source.startsWith("﻿") ? 1 : 0;
        return source.substring(0, insertAt) + "package " + target + ";" + lineBreak + lineBreak
                + source.substring(insertAt);
    }

    public static String renameType(String text, String oldType, String newType) {
        if (text == null || oldType == null || newType == null || oldType.equals(newType)) {
            return text;
        }
        return replaceMatches(text, Pattern.compile("(?<![\\w$.])" + Pattern.quote(oldType) + "(?![\\w$])"), newType);
    }

    public static String replaceQualifiedPrefix(String text, String oldPrefix, String newPrefix) {
        if (text == null || oldPrefix == null || oldPrefix.isBlank() || newPrefix == null
                || oldPrefix.equals(newPrefix)) {
            return text;
        }
        if (newPrefix.isBlank()) {
            return replaceMatches(text,
                    Pattern.compile("(?<![\\w$.])" + Pattern.quote(oldPrefix) + "\\.(?=[\\w$])"), "");
        }
        return replaceMatches(text, Pattern.compile("(?<![\\w$.])" + Pattern.quote(oldPrefix) + "(?![\\w$])"),
                newPrefix);
    }

    public static Set<String> referencedTypes(String text, String ownType, Collection<String> candidates) {
        Set<String> referenced = new LinkedHashSet<>();
        if (text == null || candidates == null) {
            return referenced;
        }
        String structural = structural(text);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.equals(ownType)) {
                continue;
            }
            Pattern pattern = Pattern.compile("(?<![\\w$.])" + Pattern.quote(candidate) + "(?![\\w$])");
            if (pattern.matcher(structural).find()) {
                referenced.add(candidate);
            }
        }
        return referenced;
    }

    public static String addImports(String text, Collection<String> qualifiedNames) {
        if (text == null || qualifiedNames == null || qualifiedNames.isEmpty()) {
            return text;
        }
        boolean crlf = text.contains("\r\n");
        String normalized = crlf ? text.replace("\r\n", "\n") : text;
        String updated = JavaImportInserter.insert(normalized, qualifiedNames).text();
        return crlf ? updated.replace("\n", "\r\n") : updated;
    }

    public static String relocateMovedType(String text, String newPackage, String oldType, String newType,
                                           String oldPackage, Collection<String> oldPackageTypes) {
        String result = withPackage(text, newPackage);
        result = renameType(result, oldType, newType);
        if (oldPackage != null && !oldPackage.isBlank() && !oldPackage.equals(newPackage)) {
            List<String> imports = new ArrayList<>();
            for (String type : referencedTypes(result, newType, oldPackageTypes)) {
                if (!type.equals(oldType)) {
                    imports.add(oldPackage + "." + type);
                }
            }
            result = addImports(result, imports);
        }
        return result;
    }

    static String structural(String text) {
        return JavaSourceText.blankStringContents(JavaSourceText.blankComments(text));
    }

    private static String replaceMatches(String text, Pattern pattern, String replacement) {
        String structural = structural(text);
        Matcher matcher = pattern.matcher(structural);
        List<int[]> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
        }
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            builder.replace(spans.get(i)[0], spans.get(i)[1], replacement);
        }
        return builder.toString();
    }

    private static String lineBreakOf(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }
}
