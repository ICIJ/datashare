package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.REPORT_DIAGNOSTIC_MAP_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.REPORT_DIAGNOSTIC_OUT_OPT;

@Command(name = "diagnostic", mixinStandardHelpOptions = true, description = {
        "Re-run extraction for every failed entry in an exported report map and capture the real cause.",
        "",
        "Writes diagnostic.jsonl (one record per failed file) and diagnostic-summary.txt (causes grouped",
        "by count) to the output directory. Must run where the source files referenced by the map are",
        "reachable, since each failed file is re-extracted.",
        "",
        "Examples:",
        "  datashare report diagnostic report-map.json",
        "  datashare report diagnostic report-map.json ./diagnostic-out"
})
public class ReportDiagnosticCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", description = "Path to the exported report-map JSON")
    String reportMap;

    @Parameters(index = "1", arity = "0..1", defaultValue = ".",
            description = "Output directory for the diagnostic report (default: current directory)")
    String outDir;

    @Override
    public void run() {
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        DatashareOptions.put(props, REPORT_DIAGNOSTIC_MAP_OPT, reportMap);
        DatashareOptions.put(props, REPORT_DIAGNOSTIC_OUT_OPT, outDir);
        return props;
    }
}
