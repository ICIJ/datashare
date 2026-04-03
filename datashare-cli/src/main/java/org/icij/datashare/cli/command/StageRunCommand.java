package org.icij.datashare.cli.command;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

@Command(name = "run", mixinStandardHelpOptions = true, description = {
        "Run one or more document processing pipeline stages.",
        "",
        "Examples:",
        "  datashare stage run --stages SCAN,INDEX",
        "  datashare stage run --stages SCAN,INDEX,NLP --nlpPipeline OPENNLP",
        "  datashare stage run --stages INDEX --resume",
        "  datashare --dataDir /data/docs -P my-project stage run --stages SCAN,INDEX,NLP"
})
public class StageRunCommand implements Runnable, DatashareSubcommand {

    @Option(names = {"--stages"}, required = true, description = "Comma-separated list of stages to run (e.g. SCAN,INDEX,NLP)")
    String stages;

    @Mixin
    PipelineOptions pipelineOptions = new PipelineOptions();

    @Override
    public void run() {
        // Properties will be collected by DatashareCommand
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = pipelineOptions.toProperties();
        props.setProperty(MODE_OPT, Mode.CLI.name());
        props.setProperty(PipelineHelper.STAGES_OPT, stages);
        return props;
    }
}
