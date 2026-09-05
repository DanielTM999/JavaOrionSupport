package dtm.ide.run.chain;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;

import java.util.List;

public interface RunChainHost {

    List<RunConfigurationData> configurations();

    RunProcessHandle execute(String configurationId, boolean debug);

    RunChainHost EMPTY = new RunChainHost() {

        @Override
        public List<RunConfigurationData> configurations() {
            return List.of();
        }

        @Override
        public RunProcessHandle execute(String configurationId, boolean debug) {
            return RunProcessHandle.empty();
        }
    };
}
