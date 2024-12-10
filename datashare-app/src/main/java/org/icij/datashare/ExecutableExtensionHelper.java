package org.icij.datashare;

import static org.icij.datashare.Extension.extractIdVersion;

import com.google.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutableExtensionHelper {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExtensionService extensionService;
    private final String extensionId;
    private final String extensionPattern;

    @Inject
    public ExecutableExtensionHelper(ExtensionService extensionService, String extensionId) {
        this.extensionService = extensionService;
        this.extensionId = extensionId;
        this.extensionPattern = fileNamePattern();
    }

    public ProcessBuilder buildProcess(String... args) {
        Path executablePath = locateExtension();
        List<String> cmd = Stream.concat(Stream.of(executablePath.toString()), Arrays.stream(args)).toList();
        return new ProcessBuilder(cmd);
    }

    public String getPidFilePattern() {
        return extensionPattern.substring(0, extensionPattern.length() - 1) + "\\.pid";
    }

    private Path locateExtension() {
        Set<File> extensionBins = extensionService.listInstalled(extensionPattern);
        return switch (extensionBins.size()) {
            case 0 -> throw new RuntimeException("Couldn't find any executable for extension " + extensionId + " (matching " + extensionPattern
                + "), extension must be installed !");
            case 1 -> extensionBins.iterator().next().toPath();
            default -> throw new RuntimeException("Found several executable extension " + extensionId + " (matching " + extensionPattern
                + "), extension must be installed !");
        };
    }

    private String fileNamePattern() {
        Map.Entry<String, String> res = extractIdVersion(((Extension) this.extensionService.list().stream().filter(ext -> ext.getId().equals(this.extensionId)).findAny()
            .orElseThrow(() -> new RuntimeException("couldn't find any extension matching " + extensionId)).reference())
            .url);
        String filename = res.getKey();
        return "^" + Pattern.quote(filename) + ".*$";
    }

}
