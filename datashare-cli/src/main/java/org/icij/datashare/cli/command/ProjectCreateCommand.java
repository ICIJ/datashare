package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_ALLOW_FROM_MASK_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_CREATION_DATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_CREATOR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_DESCRIPTION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LOGO_URL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_MAINTAINER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_NO_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_PUBLISHER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_PATH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_URL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_UPDATE_DATE_OPT;

@Command(name = "create", mixinStandardHelpOptions = true, description = {
        "Create a Datashare project.",
        "",
        "Examples:",
        "  datashare project create my-project",
        "  datashare project create my-project --label 'My Project' --description 'leak archive'",
        "  datashare project create my-project --source-path /data/my-project --allow-from-mask 10.0.0.0",
        "  datashare project create my-project --no-index --if-not-exists"
})
public class ProjectCreateCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String namePositional;

    @Option(names = "--name", description = "Project name (alternative to positional)")
    String nameFlag;

    @Option(names = "--label", description = "Display label (default: name)")
    String label;

    @Option(names = "--description", description = "Free-form description")
    String description;

    @Option(names = "--source-path", description = "Filesystem source path (default: /vault/<name>)")
    String sourcePath;

    @Option(names = "--allow-from-mask", defaultValue = "*.*.*.*",
            description = "IP mask for download access (default: *.*.*.*)")
    String allowFromMask;

    @Option(names = "--source-url", description = "URL of the data origin")
    String sourceUrl;

    @Option(names = "--maintainer-name", description = "Maintainer display name")
    String maintainerName;

    @Option(names = "--publisher-name", description = "Publisher display name")
    String publisherName;

    @Option(names = "--logo-url", description = "URL to the project logo")
    String logoUrl;

    @Option(names = "--creation-date", description = "Creation timestamp (ISO-8601, e.g. 2026-05-15T10:00:00Z). Defaults to now.")
    String creationDate;

    @Option(names = "--update-date", description = "Last-update timestamp (ISO-8601). Defaults to creation date.")
    String updateDate;

    @Option(names = "--creator",
            description = "Grant PROJECT_ADMIN on the new project to this user "
                    + "(default: defaultUserName in LOCAL/EMBEDDED mode)")
    String creator;

    @Option(names = "--no-index", description = "Skip Elasticsearch index creation")
    boolean noIndex;

    @Option(names = "--if-not-exists", description = "Idempotent: exit 0 if project exists")
    boolean ifNotExists;

    @Option(names = "--no-input", description = "Disable interactive prompts")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // Package-visible for test injection; when non-null the TTY check is skipped.
    Prompter prompterOverride;

    // Validated and (optionally) prompt-filled name, set by run() and read by
    // getSubcommandProperties(). null until run() completes successfully; the
    // dispatcher uses that nullability to decide whether to emit
    // PROJECT_CREATE_OPT or just the mode marker.
    private String validatedName;

    @Override
    public void run() {
        try {
            validateProvidedFlags();
            Prompter prompter = resolvePrompter();
            String name = resolveName(prompter);
            promptForUnsetFields(prompter, name);
            this.validatedName = name;
        } catch (InvalidValueException | Prompter.ValidationFailedException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    private String effectiveName() {
        return namePositional != null ? namePositional : nameFlag;
    }

    /**
     * Validates every supplied flag before checking for missing fields so an
     * invalid value exits 5, not 2.
     */
    private void validateProvidedFlags() {
        String name = effectiveName();
        if (name != null) Validators.projectName(name);
        if (allowFromMask != null) Validators.allowFromMask(allowFromMask);
        if (sourceUrl != null) Validators.uri(sourceUrl);
        if (logoUrl != null) Validators.uri(logoUrl);
        if (creator != null) Validators.login(creator);
        if (creationDate != null) Validators.iso8601(creationDate);
        if (updateDate != null) Validators.iso8601(updateDate);
    }

    /**
     * Resolves the prompter once. Returns {@code null} when {@code --no-input}
     * is set or no TTY is available; callers gate every interactive prompt on
     * a non-null prompter. A test-injected {@link #prompterOverride} short-
     * circuits the TTY check so unit tests can drive prompts deterministically.
     */
    private Prompter resolvePrompter() {
        if (noInput) return null;
        if (prompterOverride != null) return prompterOverride;
        Prompter prompter = new Prompter();
        return prompter.isInteractive() ? prompter : null;
    }

    /**
     * Returns the project name, prompting when {@code --name} was omitted.
     * Exits 2 if the name is missing and the operator has no way to provide
     * one (no TTY or {@code --no-input}).
     */
    private String resolveName(Prompter prompter) {
        String name = effectiveName();
        if (name != null) return name;
        if (prompter == null) {
            spec.commandLine().getErr().println(
                    "error: --name is required when --no-input is set or no TTY is available");
            throw new CliExitException(2);
        }
        return prompter.promptString("Project name", Validators::projectName);
    }

    /**
     * Prompts for every settable field that wasn't already passed as a flag.
     * Auto-derived fields (allowFromMask via picocli default, creationDate /
     * updateDate stamped by the service, creator from the launcher) are
     * intentionally NOT prompted. Blank submission returns {@code null}, which
     * the service treats as "use the default" for label / sourcePath and as
     * "leave null" for the rest. No-op when {@code prompter} is {@code null}.
     */
    private void promptForUnsetFields(Prompter prompter, String name) {
        if (prompter == null) return;
        if (label == null) label = promptOptional(prompter, "Label", name);
        if (description == null) description = promptOptional(prompter, "Description", null);
        if (sourcePath == null) sourcePath = promptOptionalPath(prompter, "Source path", "/vault/" + name);
        if (sourceUrl == null) sourceUrl = promptOptionalUri(prompter, "Source URL");
        if (maintainerName == null) maintainerName = promptOptional(prompter, "Maintainer name", null);
        if (publisherName == null) publisherName = promptOptional(prompter, "Publisher name", null);
        if (logoUrl == null) logoUrl = promptOptionalUri(prompter, "Logo URL");
    }

    /**
     * Prompts for an optional string field, showing the default in brackets
     * when present. Blank input (the operator pressed enter without typing)
     * returns {@code null} so the downstream service applies its own default.
     */
    private static String promptOptional(Prompter prompter, String label, String defaultValue) {
        String displayLabel = defaultValue == null ? label : label + " [" + defaultValue + "]";
        String line = prompter.promptString(displayLabel, s -> {});
        return line == null || line.isBlank() ? null : line.trim();
    }

    /**
     * Prompts for an optional URI field. Blank input returns {@code null};
     * non-blank input is validated against {@link Validators#uri(String)}.
     */
    private static String promptOptionalUri(Prompter prompter, String label) {
        String line = prompter.promptString(label, s -> {
            if (s != null && !s.isBlank()) Validators.uri(s);
        });
        return line == null || line.isBlank() ? null : line.trim();
    }

    /**
     * Prompts for an optional filesystem path. Same display semantics as
     * {@link #promptOptional} (default shown in brackets, blank returns
     * {@code null}); non-blank input is parsed with {@link Path#of} so
     * syntactic garbage (e.g. NUL byte) is caught before dispatch instead of
     * surfacing as a runtime error in the service.
     */
    private static String promptOptionalPath(Prompter prompter, String label, String defaultValue) {
        String displayLabel = defaultValue == null ? label : label + " [" + defaultValue + "]";
        String line = prompter.promptString(displayLabel, s -> {
            if (s != null && !s.isBlank()) {
                try {
                    Path.of(s.trim());
                } catch (InvalidPathException e) {
                    throw new InvalidValueException(
                            "sourcePath", "path is not valid: " + e.getMessage());
                }
            }
        });
        return line == null || line.isBlank() ? null : line.trim();
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (validatedName == null) {
            return props;
        }
        // The project name is the dispatch marker; siblings carry the remaining
        // fields as typed strings.
        DatashareOptions.put(props, PROJECT_CREATE_OPT, validatedName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_LABEL_OPT, label);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_DESCRIPTION_OPT, description);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_SOURCE_PATH_OPT, sourcePath);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_ALLOW_FROM_MASK_OPT, allowFromMask);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_SOURCE_URL_OPT, sourceUrl);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_MAINTAINER_NAME_OPT, maintainerName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_PUBLISHER_NAME_OPT, publisherName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_LOGO_URL_OPT, logoUrl);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_CREATION_DATE_OPT, creationDate);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_UPDATE_DATE_OPT, updateDate);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_CREATOR_OPT, creator);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_NO_INDEX_OPT, noIndex);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_IF_NOT_EXISTS_OPT, ifNotExists);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_JSON_OPT, json);
        return props;
    }
}
