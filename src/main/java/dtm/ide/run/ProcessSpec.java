package dtm.ide.run;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Especificacao de um processo local a ser iniciado por uma configuracao de execucao.
 *
 * <p>E a camada comum entre Aplicacao, Spring Boot, JAR, Maven, Gradle e Testes: todos
 * descrevem o que executar por meio deste registro e delegam o inicio ao
 * {@link ProcessLauncher}, que aplica PTY, streaming imediato e cancelamento em dois
 * estagios.</p>
 */
public record ProcessSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Termination termination
) {

    /** Politica de encerramento aplicada quando o usuario pressiona Stop. */
    public enum Termination {
        /** Pede a parada normal e, no segundo Stop, mata a arvore de processos. */
        TWO_STAGE_TREE,
        /** Encerra apenas o processo iniciado, sem tocar nos filhos. */
        PROCESS_ONLY
    }

    public ProcessSpec {
        command = command == null ? List.of() : List.copyOf(command);
        environment = environment == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(environment));
        termination = termination == null ? Termination.TWO_STAGE_TREE : termination;
    }

    public static ProcessSpec of(List<String> command, Path workingDirectory,
                                 Map<String, String> environment) {
        return new ProcessSpec(command, workingDirectory, environment,
                Termination.TWO_STAGE_TREE);
    }

    public ProcessSpec withTermination(Termination policy) {
        return new ProcessSpec(command, workingDirectory, environment, policy);
    }

    /** Argumentos adicionais anexados ao final do comando. */
    public ProcessSpec withArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return this;
        }
        List<String> merged = new java.util.ArrayList<>(command);
        merged.addAll(arguments);
        return new ProcessSpec(merged, workingDirectory, environment, termination);
    }

    /** Variaveis adicionadas ao ambiente, sobrescrevendo as existentes. */
    public ProcessSpec withEnvironment(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(environment);
        merged.putAll(extra);
        return new ProcessSpec(command, workingDirectory, merged, termination);
    }

    public boolean isEmpty() {
        return command.isEmpty();
    }

    /** Linha de comando exibida no console antes do inicio do processo. */
    public String display() {
        return String.join(" ", command);
    }
}
