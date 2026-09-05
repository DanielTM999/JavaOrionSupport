package dtm.ide.run;

import dtm.ide.sdk.JdkDetector;
import dtm.ide.sdk.JdkInstallation;
import dtm.stools.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolucao da JDK usada por uma configuracao de execucao.
 *
 * <p>Sem {@code jdkHome} a configuracao usa a JDK do projeto. Com {@code jdkHome} preenchido
 * a instalacao apontada e obrigatoria: se ela desapareceu, o lancamento falha com uma
 * mensagem clara em vez de cair silenciosamente na JDK do projeto.</p>
 */
public final class RunJdkResolver {

    private static String text(String key, String fallback) {
        return I18n.getText(RunJdkResolver.class, key, fallback);
    }

    private RunJdkResolver() {
    }

    /** Valor de {@code jdkHome} que representa "usar a JDK do projeto". */
    public static final String PROJECT_JDK = "";

    public static JdkInstallation resolve(Map<String, Object> properties,
                                          Supplier<JdkInstallation> projectJdk) {
        String home = JavaRunValidation.value(properties, JavaRunTypes.JDK_HOME);
        if (home.isBlank()) {
            JdkInstallation project = projectJdk == null ? null : projectJdk.get();
            if (project == null) {
                throw new IllegalStateException(text("error.noProjectJdk",
                        "Nenhuma JDK encontrada. Instale uma pelo JDK Manager."));
            }
            return project;
        }
        return explicit(home);
    }

    /** Carrega a instalacao apontada por {@code home}, exigindo que ela exista. */
    public static JdkInstallation explicit(String home) {
        Path path;
        try {
            path = Path.of(home.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            throw new IllegalStateException(text("error.jdkPathInvalid",
                    "O caminho da JDK selecionada e invalido:") + " " + home);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(text("error.jdkMissing",
                    "A JDK selecionada nao esta mais disponivel:") + " " + path);
        }
        return JdkDetector.inspect(path, JdkInstallation.JdkOrigin.SYSTEM)
                .filter(JdkInstallation::isUsable)
                .orElseThrow(() -> new IllegalStateException(text("error.jdkUnusable",
                        "A JDK selecionada nao possui um executavel java utilizavel:")
                        + " " + path));
    }
}
