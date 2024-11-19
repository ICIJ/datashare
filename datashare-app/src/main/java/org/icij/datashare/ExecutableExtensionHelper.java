package org.icij.datashare;

import static org.icij.datashare.cli.DatashareCliOptions.NLP_PARALLELISM_OPT;

import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutableExtensionHelper {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExtensionService extensionService;
    private final String extensionPattern;
    private final int numWorkers;

    @Inject
    public ExecutableExtensionHelper(
        PropertiesProvider propertiesProvider, ExtensionService extensionService, String extensionPattern
    ) {
        this.extensionService = extensionService;
        this.extensionPattern = extensionPattern;
        this.numWorkers = propertiesProvider.get(NLP_PARALLELISM_OPT).map(Integer::parseInt).orElse(1);
    }


    public Process executeExtension(boolean inheritIO, String... args) throws IOException {
        logger.info("starting " + numWorkers + " workers...");
        Path executablePath = locateExtension();
        List<String> cmd = Stream.concat(Stream.of(executablePath.toString()), Arrays.stream(args)).toList();
        // TODO: better handle process output redirection
        ProcessBuilder builder = new ProcessBuilder(cmd);
        if (inheritIO) {
            // This could be configurable, here ^C the Java process will also kill the child process which might be
            // unwanted, logging could be improved too
            builder.inheritIO();
        }
        return builder.start();
    }

    String getPidFilePattern() {
        String pattern = extensionPattern;
        if (pattern.endsWith("$")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }
        pattern += ".*\\.pid";
        return pattern;
    }

    private Path locateExtension() {
        Set<File> extensionBins = extensionService.listInstalled(extensionPattern);
        return switch (extensionBins.size()) {
            case 0 -> throw new RuntimeException("Couldn't find any executable extension matching " + extensionPattern
                + ", extension must be installed !");
            case 1 -> extensionBins.iterator().next().toPath();
            default -> throw new RuntimeException("Found several executable extension matching " + extensionPattern
                + ", extension directory must be cleaned up !");
        };
    }
}
