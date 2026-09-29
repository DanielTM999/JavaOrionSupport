package dtm.ide.ui;

import dtm.ide.spring.JavaSourceLexer;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaProjectTreeIcons {

    static final int MAX_SOURCE_BYTES = 64 * 1024;

    private static final Pattern ABSTRACT = Pattern.compile("\\babstract\\b");
    private static final Pattern EXTENDS = Pattern.compile("\\G\\s*(?:<[^{]*?>\\s*)?extends\\s+([\\w.]+)");
    private static final Pattern THROWABLE_NAME = Pattern.compile("\\w*Exception|\\w*Error|Throwable");
    private static final Map<Path, CachedKind> CACHE = new ConcurrentHashMap<>();

    enum Kind {
        CLASS, INTERFACE, ENUM, ABSTRACT, RECORD, EXCEPTION, NONE
    }

    private record CachedKind(long modified, long size, Kind kind) {
    }

    private JavaProjectTreeIcons() {
    }

    public static Icon iconOf(Path file, int size) {
        return iconFor(kindOf(file), size);
    }

    static Kind kindOf(Path file) {
        if (!isJava(file) || !Files.isRegularFile(file)) {
            return Kind.NONE;
        }
        try {
            file = file.toAbsolutePath().normalize();
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            CachedKind cached = CACHE.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.kind();
            }
            Kind kind = kindFor(file.getFileName().toString(), readHead(file));
            if (CACHE.size() >= 4096) {
                CACHE.clear();
            }
            CACHE.put(file, new CachedKind(modified, size, kind));
            return kind;
        } catch (IOException | SecurityException ignored) {
            return Kind.NONE;
        }
    }

    public static void invalidate(Path file) {
        if (file != null) {
            CACHE.remove(file.toAbsolutePath().normalize());
        }
    }

    public static boolean isJava(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().endsWith(".java");
    }

    static Icon iconFor(Kind kind, int size) {
        return switch (kind) {
            case CLASS -> JavaIcons.javaClass(size);
            case INTERFACE -> JavaIcons.javaInterface(size);
            case ENUM -> JavaIcons.javaEnum(size);
            case ABSTRACT -> JavaIcons.javaAbstract(size);
            case RECORD -> JavaIcons.javaRecord(size);
            case EXCEPTION -> JavaIcons.javaException(size);
            case NONE -> null;
        };
    }

    static Kind kindFor(String fileName, String source) {
        if (fileName == null || !fileName.endsWith(".java") || source == null) {
            return Kind.NONE;
        }
        String mainName = fileName.substring(0, fileName.length() - 5);
        String code = JavaSourceLexer.Source.of(source).structural();
        Matcher types = JavaSourceLexer.TYPE_DECLARATION.matcher(code);
        while (types.find()) {
            if (!mainName.equals(types.group(2))) {
                continue;
            }
            return switch (types.group(1)) {
                case "class" -> throwable(code, types.end()) ? Kind.EXCEPTION
                        : abstractClass(code, types.start()) ? Kind.ABSTRACT : Kind.CLASS;
                case "record" -> Kind.RECORD;
                case "interface" -> types.start() > 0 && code.charAt(types.start() - 1) == '@'
                        ? Kind.NONE : Kind.INTERFACE;
                case "enum" -> Kind.ENUM;
                default -> Kind.NONE;
            };
        }
        return Kind.NONE;
    }

    private static String readHead(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return new String(input.readNBytes(MAX_SOURCE_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static boolean throwable(String code, int nameEnd) {
        Matcher parent = EXTENDS.matcher(code);
        if (!parent.find(nameEnd)) {
            return false;
        }
        String name = parent.group(1);
        return THROWABLE_NAME.matcher(name.substring(name.lastIndexOf('.') + 1)).matches();
    }

    private static boolean abstractClass(String code, int declarationStart) {
        int boundary = Math.max(code.lastIndexOf(';', declarationStart),
                Math.max(code.lastIndexOf('{', declarationStart),
                        code.lastIndexOf('}', declarationStart)));
        return ABSTRACT.matcher(code.substring(boundary + 1, declarationStart)).find();
    }
}
