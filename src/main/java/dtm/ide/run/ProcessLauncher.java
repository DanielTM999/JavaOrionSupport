package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunProcessHandle;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Inicia um {@link ProcessSpec} sob PTY, com streaming imediato da saida e cancelamento em
 * dois estagios. Todos os lancamentos locais do plugin passam por aqui.
 */
@Slf4j
public final class ProcessLauncher {

    private ProcessLauncher() {
    }

    public static RunProcessHandle launch(ProcessSpec spec) throws Exception {
        PtyLauncher.Result result = PtyLauncher.launch(
                spec.command(), spec.workingDirectory(), spec.environment());
        Process process = result.process();

        if (spec.termination() == ProcessSpec.Termination.PROCESS_ONLY) {
            return RunProcessHandle.builder()
                    .process(process)
                    .alive(process::isAlive)
                    .terminate(process::destroy)
                    .ptyBacked(result.pty())
                    .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                    .build();
        }
        TwoStageProcessTerminator terminator = new TwoStageProcessTerminator(process);
        return RunProcessHandle.builder()
                .process(process)
                .alive(terminator::isAlive)
                .terminate(terminator::terminate)
                .ptyBacked(result.pty())
                .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                .build();
    }

    /** Handle somente de saida usado para reportar erros de validacao e de preparacao. */
    public static RunProcessHandle message(String text) {
        String content = (text == null ? "" : text) + System.lineSeparator();
        return RunProcessHandle.outputOnly(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
