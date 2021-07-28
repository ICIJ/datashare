package org.icij.datashare;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) throws Exception {
        DatashareCli cli = new DatashareCli().parseArguments(args);
        LOGGER.info("Running datashare " + (cli.isWebServer() ? "web server" : ""));

        if (cli.isWebServer()) {
            WebApp.start(cli.properties);
        } else if (cli.mode() == Mode.BATCH_SEARCH) {
            BatchSearchApp.start(cli.properties);
        } else if (cli.mode() == Mode.BATCH_DOWNLOAD) {
            BatchDownloadApp.start(cli.properties);
        } else {
            CliApp.start(cli.properties);
        }
    }
}
