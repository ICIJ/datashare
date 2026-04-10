package org.icij.datashare.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Model.UsageMessageSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static picocli.CommandLine.Model.UsageMessageSpec.*;

/**
 * Configures picocli's help output with a yarn-inspired style:
 * two-column layout where the first column is sized to the widest option name,
 * so descriptions always appear on the same line.
 */
public final class DatashareHelpFactory {

    static final int HELP_WIDTH = 120;
    private static final int INDENT = 2;
    private static final int COLUMN_GAP = 2;

    private DatashareHelpFactory() {}

    /**
     * Applies help styling to cmd and all its subcommands recursively.
     * Returns cmd for fluent chaining.
     */
    public static CommandLine configure(CommandLine cmd) {
        apply(cmd, true);
        for (CommandLine sub : cmd.getSubcommands().values()) {
            configureSubtree(sub);
        }
        return cmd;
    }

    private static void configureSubtree(CommandLine cmd) {
        apply(cmd, false);
        for (CommandLine sub : cmd.getSubcommands().values()) {
            configureSubtree(sub);
        }
    }

    private static void apply(CommandLine cmd, boolean isRoot) {
        UsageMessageSpec usage = cmd.getCommandSpec().usageMessage();
        usage.abbreviateSynopsis(true);
        usage.sortOptions(false);
        usage.width(HELP_WIDTH);

        if (isRoot) {
            usage.footer("",
                    "  Run '@|italic datashare COMMAND --help|@' for more information on a command.",
                    "  Documentation and support: https://github.com/ICIJ/datashare");
        }

        Map<String, CommandLine.IHelpSectionRenderer> sections = cmd.getHelpSectionMap();
        // Headings are suppressed; they are emitted by the list renderers only when non-empty.
        // Bold markup (@|bold ...|@) is processed by picocli's ANSI pass at print time,
        // respecting --no-color / NO_COLOR automatically.
        for (String key : List.of(SECTION_KEY_OPTION_LIST_HEADING, SECTION_KEY_COMMAND_LIST_HEADING,
                                   SECTION_KEY_PARAMETER_LIST_HEADING, SECTION_KEY_DESCRIPTION_HEADING)) {
            sections.put(key, h -> "");
        }

        sections.put(SECTION_KEY_DESCRIPTION,    DatashareHelpFactory::renderDescription);
        sections.put(SECTION_KEY_OPTION_LIST,    h -> headedSection(h, "Options:",        ownOptionRows(h),    sharedFirstColWidth(h)));
        sections.put(SECTION_KEY_COMMAND_LIST,   h -> headedSection(h, "Commands:",       renderCommandList(h)));
        sections.put(SECTION_KEY_PARAMETER_LIST, h -> headedSection(h, "Arguments:",      renderParameterList(h)));
        sections.put("globalOptionList",         h -> headedSection(h, "Global Options:", globalOptionRows(h), sharedFirstColWidth(h)));

        List<String> keys = new ArrayList<>(usage.sectionKeys());
        int idx = keys.indexOf(SECTION_KEY_OPTION_LIST);
        if (idx >= 0 && !keys.contains("globalOptionList")) {
            keys.add(idx + 1, "globalOptionList");
            usage.sectionKeys(keys);
        }
    }

    static String renderOptionList(CommandLine.Help help) {
        List<String[]> rows = ownOptionRows(help);
        if (rows.isEmpty()) {
            return "";
        }
        return twoColumns(rows);
    }

    static String renderGlobalOptionList(CommandLine.Help help) {
        List<String[]> rows = globalOptionRows(help);
        if (rows.isEmpty()) {
            return "";
        }
        return twoColumns(rows);
    }

    private static List<String[]> ownOptionRows(CommandLine.Help help) {
        if (!help.commandSpec().subcommands().isEmpty()) {
            return List.of();
        }
        return optionRows(help.commandSpec().options().stream()
                .filter(o -> !o.inherited())
                .collect(Collectors.toList()));
    }

    private static List<String[]> globalOptionRows(CommandLine.Help help) {
        if (help.commandSpec().parent() == null || !help.commandSpec().subcommands().isEmpty()) {
            return List.of();
        }
        return optionRows(help.commandSpec().root().options());
    }

    private static String headedSection(CommandLine.Help h, String heading, String body) {
        if (body.isEmpty()) {
            return "";
        }
        return String.format("%n%s%n%n%s", bold(h, heading), body);
    }

    private static String headedSection(CommandLine.Help h, String heading, List<String[]> rows, int firstColWidth) {
        if (rows.isEmpty()) {
            return "";
        }
        return String.format("%n%s%n%n%s", bold(h, heading), twoColumns(rows, firstColWidth));
    }

    /** Filters, sorts, and maps a list of option specs to two-column row pairs. */
    private static List<String[]> optionRows(List<OptionSpec> options) {
        return options.stream()
                .filter(o -> !o.hidden())
                .sorted(Comparator.comparing(DatashareHelpFactory::optionSortKey))
                .map(o -> new String[]{ optionLabel(o), firstLine(o.description()) })
                .collect(Collectors.toList());
    }

    /**
     * Returns the first-column width sized to the widest option label across both the
     * command's own options and the root's global options, so both sections align.
     */
    private static int sharedFirstColWidth(CommandLine.Help help) {
        return Stream.concat(
                help.commandSpec().options().stream().filter(o -> !o.inherited()),
                help.commandSpec().root().options().stream()
        ).filter(o -> !o.hidden())
         .mapToInt(o -> optionLabel(o).length())
         .max()
         .orElse(0);
    }

    static String renderCommandList(CommandLine.Help help) {
        Map<String, CommandLine> subs = help.commandSpec().subcommands();
        if (subs.isEmpty()) {
            return "";
        }
        List<String[]> rows = subs.entrySet().stream()
                .map(e -> new String[]{ e.getKey(),
                        firstLine(e.getValue().getCommandSpec().usageMessage().description()) })
                .collect(Collectors.toList());
        return twoColumns(rows);
    }

    static String renderParameterList(CommandLine.Help help) {
        List<PositionalParamSpec> params = help.commandSpec().positionalParameters().stream()
                .filter(p -> !p.hidden())
                .collect(Collectors.toList());
        if (params.isEmpty()) {
            return "";
        }
        List<String[]> rows = params.stream()
                .map(p -> new String[]{ p.paramLabel(), firstLine(p.description()) })
                .collect(Collectors.toList());
        return twoColumns(rows);
    }

    /**
     * Renders the command description, automatically bolding lines that look like section
     * headings (no leading whitespace, ends with :), e.g. "Examples:".
     * Uses the command's color scheme so bold is stripped when ANSI is disabled.
     */
    static String renderDescription(CommandLine.Help help) {
        String[] desc = help.commandSpec().usageMessage().description();
        if (desc == null || desc.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n");
        for (String line : desc) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.endsWith(":")) {
                sb.append(bold(help, line)).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /** Renders text in bold using the command's color scheme. */
    private static String bold(CommandLine.Help help, String text) {
        return help.colorScheme().ansi().string("@|bold " + text + "|@");
    }

    /** Returns the sort key for an option: the long name (or first name) stripped of leading dashes, lowercased. */
    static String optionSortKey(OptionSpec option) {
        return Arrays.stream(option.names())
                .filter(n -> n.startsWith("--"))
                .findFirst()
                .orElse(option.names()[0])
                .replaceFirst("^-+", "")
                .toLowerCase();
    }

    /** Formats option names + param label: e.g. -b, --bind=<host> */
    static String optionLabel(OptionSpec option) {
        StringBuilder sb = new StringBuilder();
        String[] names = option.names();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names[i]);
        }
        if (option.arity().max() > 0) {
            sb.append("=").append(semanticLabel(option));
        }
        return sb.toString();
    }

    /**
     * Returns a human-readable type label for the option value,
     * derived from the option name and its Java type.
     */
    private static String semanticLabel(OptionSpec option) {
        // Use explicitly-set paramLabels (not the picocli-generated <fieldName> default or
        // the programmatic-builder default "PARAM")
        String configured = option.paramLabel();
        if (!configured.equals("PARAM") && !(configured.startsWith("<") && configured.endsWith(">"))) {
            return configured;
        }

        Class<?> type = option.type();

        // Numeric Java types
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return "<number>";
        }

        // File type
        if (type == java.io.File.class) {
            return "<path>";
        }

        // Name-based hints for String options: use the longest --name, lowercased
        String name = Arrays.stream(option.names())
                .filter(n -> n.startsWith("--"))
                .findFirst()
                .orElse(option.names()[option.names().length - 1])
                .replaceFirst("^-+", "")
                .toLowerCase();

        if (name.endsWith("url") || name.endsWith("address") || name.endsWith("uri")) {
            return "<url>";
        }
        if (name.endsWith("dir") || name.endsWith("path") || name.endsWith("settings")) {
            return "<path>";
        }
        if (name.endsWith("host") || name.equals("bind")) {
            return "<host>";
        }
        if (name.endsWith("provider") || name.endsWith("filter")) {
            return "<class>";
        }

        return "<value>";
    }

    /** Returns the first non-blank description line, or empty string. */
    private static String firstLine(String[] lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        return lines[0];
    }

    /** Renders rows as a two-column table, left column sized to the widest entry. */
    private static String twoColumns(List<String[]> rows) {
        return twoColumns(rows, rows.stream().mapToInt(r -> r[0].length()).max().orElse(0));
    }

    /** Renders rows as a two-column table with an explicit first-column width. */
    private static String twoColumns(List<String[]> rows, int firstColWidth) {
        String prefix = " ".repeat(INDENT);
        String fmt = prefix + "%-" + firstColWidth + "s" + " ".repeat(COLUMN_GAP) + "%s%n";
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(String.format(fmt, row[0], row[1]));
        }
        return sb.toString();
    }
}
