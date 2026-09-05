package dtm.ide.build;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comando montado por um {@link BuildSystem} sem ser executado.
 *
 * <p>Permite que as configuracoes de execucao Maven, Gradle e Testes reaproveitem exatamente
 * a mesma montagem de comando usada pelos servicos de build -- wrapper, ferramenta
 * provisionada, selecao de modulo, profiles e {@code JAVA_HOME} -- e iniciem o processo por
 * conta propria, com PTY e cancelamento.</p>
 */
public record BuildCommand(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment
) {

    public BuildCommand {
        command = command == null ? List.of() : List.copyOf(command);
        environment = environment == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(environment));
    }

    public String display() {
        return String.join(" ", command);
    }

    /** Opcoes de invocacao aplicadas a um comando de ferramenta. */
    public record Options(
            List<String> profiles,
            List<String> extraArguments,
            boolean offline,
            Map<String, String> environment
    ) {

        public Options {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
            environment = environment == null ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(environment));
        }

        public static Options none() {
            return new Options(List.of(), List.of(), false, Map.of());
        }

        public Options withExtraArguments(List<String> arguments) {
            return new Options(profiles, arguments, offline, environment);
        }
    }
}
