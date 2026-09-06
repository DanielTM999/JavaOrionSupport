package dtm.ide.project;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class JdtOutputIsolation {

    public static final String PROFILE_ID = "orion-jdt-autobuild";
    public static final String BUILD_DIRECTORY = "target-orion";

    private static final Pattern PROFILE_PRESENT = Pattern.compile(
            "(?s)<id\\s*>\\s*" + Pattern.quote(PROFILE_ID) + "\\s*</id\\s*>");
    private static final Pattern PROFILES_OPEN = Pattern.compile("<profiles\\s*>");
    private static final Pattern PROJECT_CLOSE = Pattern.compile("</project\\s*>");

    private JdtOutputIsolation() {
    }

    public static boolean ensure(JavaProjectDescriptor descriptor) {
        if (descriptor == null || !descriptor.kind().isMaven() || descriptor.root() == null) {
            return false;
        }
        return ensureForPom(descriptor.root().resolve(JavaProjectConventions.POM_FILE));
    }

    public static boolean ensureForPom(Path pom) {
        if (pom == null || !Files.isRegularFile(pom)) {
            return false;
        }
        try {
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            String updated = inject(content);
            if (updated == null || updated.equals(content)) {
                return false;
            }
            Files.writeString(pom, updated, StandardCharsets.UTF_8);
            log.info("Profile {} adicionado a {}", PROFILE_ID, pom);
            return true;
        } catch (Exception e) {
            log.warn("Falha ao isolar a saida do autobuild em {}: {}", pom, e.getMessage());
            return false;
        }
    }

    static String inject(String pomXml) {
        if (pomXml == null || pomXml.isBlank() || PROFILE_PRESENT.matcher(pomXml).find()) {
            return pomXml;
        }
        Matcher profiles = PROFILES_OPEN.matcher(pomXml);
        if (profiles.find()) {
            int insertAt = profiles.end();
            return pomXml.substring(0, insertAt)
                    + System.lineSeparator() + profileBlock("        ")
                    + pomXml.substring(insertAt);
        }
        int insertAt = lastProjectClose(pomXml);
        if (insertAt < 0) {
            return pomXml;
        }
        String block = "    <profiles>" + System.lineSeparator()
                + profileBlock("        ")
                + "    </profiles>" + System.lineSeparator()
                + System.lineSeparator();
        return pomXml.substring(0, insertAt) + block + pomXml.substring(insertAt);
    }

    private static int lastProjectClose(String content) {
        Matcher matcher = PROJECT_CLOSE.matcher(content);
        int start = -1;
        while (matcher.find()) {
            start = matcher.start();
        }
        return start;
    }

    private static String profileBlock(String indent) {
        String nl = System.lineSeparator();
        String inner = indent + "    ";
        return indent + "<profile>" + nl
                + inner + "<id>" + PROFILE_ID + "</id>" + nl
                + inner + "<activation>" + nl
                + inner + "    <property>" + nl
                + inner + "        <name>m2e.version</name>" + nl
                + inner + "    </property>" + nl
                + inner + "</activation>" + nl
                + inner + "<build>" + nl
                + inner + "    <directory>${project.basedir}/" + BUILD_DIRECTORY + "</directory>" + nl
                + inner + "</build>" + nl
                + indent + "</profile>" + nl;
    }
}
