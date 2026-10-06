package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunProcessHandle;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public final class ProcessLauncher {

    private ProcessLauncher() {
    }

    public static RunProcessHandle launch(ProcessSpec spec) throws Exception {
        return launch(spec, List.of());
    }

    public static RunProcessHandle launch(ProcessSpec spec, List<String> notices) throws Exception {
        long launchGeneration = OwnedRunProcesses.launchGeneration();
        PtyLauncher.Result result = PtyLauncher.launch(
                spec.command(), spec.workingDirectory(), spec.environment());
        Process process = result.process();
        OwnedRunProcesses.register(process, launchGeneration);

        if (spec.termination() == ProcessSpec.Termination.PROCESS_ONLY) {
            return RunProcessHandle.builder()
                    .process(process)
                    .output(withNotices(notices, process.getInputStream()))
                    .alive(process::isAlive)
                    .terminate(process::destroy)
                    .ptyBacked(result.pty())
                    .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                    .build();
        }
        TwoStageProcessTerminator terminator = new TwoStageProcessTerminator(process);
        return RunProcessHandle.builder()
                .process(process)
                .output(withNotices(notices, process.getInputStream()))
                .alive(terminator::isAlive)
                .terminate(terminator::terminate)
                .ptyBacked(result.pty())
                .stdinMode(RunProcessHandle.StdinMode.TERMINAL)
                .build();
    }

    private static InputStream withNotices(List<String> notices, InputStream output) {
        if (notices == null || notices.isEmpty()) {
            return output;
        }
        StringBuilder text = new StringBuilder();
        for (String notice : notices) {
            text.append(notice).append(System.lineSeparator());
        }
        return new SequenceInputStream(
                new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)), output);
    }

    public static RunProcessHandle message(String text) {
        String content = (text == null ? "" : text) + System.lineSeparator();
        return RunProcessHandle.outputOnly(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
