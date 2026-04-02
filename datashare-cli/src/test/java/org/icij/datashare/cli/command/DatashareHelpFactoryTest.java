package org.icij.datashare.cli.command;

import org.junit.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.command.DatashareHelpFactory.HELP_WIDTH;
import static org.icij.datashare.cli.command.DatashareHelpFactory.optionLabel;
import static org.icij.datashare.cli.command.DatashareHelpFactory.renderCommandList;
import static org.icij.datashare.cli.command.DatashareHelpFactory.renderDescription;
import static org.icij.datashare.cli.command.DatashareHelpFactory.renderGlobalOptionList;
import static org.icij.datashare.cli.command.DatashareHelpFactory.renderOptionList;

public class DatashareHelpFactoryTest {

    // ===================================================================
    // optionLabel
    // ===================================================================

    @Test
    public void test_option_label_string_uses_value() {
        OptionSpec opt = OptionSpec.builder("--cors").type(String.class).build();
        assertThat(optionLabel(opt)).isEqualTo("--cors=<value>");
    }

    @Test
    public void test_option_label_url_suffix_uses_url() {
        OptionSpec opt = OptionSpec.builder("--elasticsearchAddress").type(String.class).build();
        assertThat(optionLabel(opt)).isEqualTo("--elasticsearchAddress=<url>");
    }

    @Test
    public void test_option_label_dir_suffix_uses_path() {
        OptionSpec opt = OptionSpec.builder("--batchDownloadDir").type(String.class).build();
        assertThat(optionLabel(opt)).isEqualTo("--batchDownloadDir=<path>");
    }

    @Test
    public void test_option_label_bind_uses_host() {
        OptionSpec opt = OptionSpec.builder("-b", "--bind").type(String.class).build();
        assertThat(optionLabel(opt)).isEqualTo("-b, --bind=<host>");
    }

    @Test
    public void test_option_label_int_uses_number() {
        OptionSpec opt = OptionSpec.builder("--port").type(int.class).build();
        assertThat(optionLabel(opt)).isEqualTo("--port=<number>");
    }

    @Test
    public void test_option_label_boolean_no_equals() {
        OptionSpec opt = OptionSpec.builder("--ocr").type(boolean.class).build();
        assertThat(optionLabel(opt)).isEqualTo("--ocr");
    }

    // ===================================================================
    // renderDescription: auto-bold section headings
    // ===================================================================

    @Test
    public void test_render_description_bolds_heading_lines() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        String output = renderDescription(appServe.getHelp());
        // "Examples:" heading is rendered (bold markup stripped in non-TTY test env)
        assertThat(output).contains("Examples:");
        // must not contain raw unprocessed markup
        assertThat(output).doesNotContain("@|bold");
    }

    @Test
    public void test_render_description_does_not_bold_indented_lines() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        String output = renderDescription(appServe.getHelp());
        // indented example lines must appear verbatim
        assertThat(output).contains("  datashare app start");
    }

    // ===================================================================
    // renderOptionList: two-column alignment
    // ===================================================================

    @Test
    public void test_render_option_list_all_descriptions_on_same_line() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        String output = renderOptionList(appServe.getHelp());

        for (String line : lines(output)) {
            if (line.isBlank()) continue;
            // Each line should contain exactly one segment of content (no mid-line wrap artefacts)
            assertThat(line.startsWith("      ")).as("no over-indented continuation line: " + line).isFalse();
        }
    }

    @Test
    public void test_render_option_list_one_line_per_option() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        CommandLine.Help help = appServe.getHelp();
        String output = renderOptionList(help);

        long visibleOptions = help.commandSpec().options().stream().filter(o -> !o.hidden() && !o.inherited()).count();
        long nonBlankLines  = lines(output).stream().filter(l -> !l.isBlank()).count();
        assertThat(nonBlankLines)
                .as("each option must produce exactly one line")
                .isEqualTo(visibleOptions);
    }

    @Test
    public void test_render_command_list_two_columns() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        String output = renderCommandList(cmd.getHelp());
        assertThat(output).contains("app");
        assertThat(output).contains("worker");

        long subCount    = cmd.getSubcommands().size();
        long nonBlankLines = lines(output).stream().filter(l -> !l.isBlank()).count();
        assertThat(nonBlankLines)
                .as("each subcommand must produce exactly one line")
                .isEqualTo(subCount);
    }

    // ===================================================================
    // Section headings: bold markup, no indent
    // ===================================================================

    @Test
    public void test_option_list_heading_is_bold_and_unindented() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        String section = cmd.getHelpSectionMap().get("optionList").render(appServe.getHelp());
        // heading rendered without raw markup (ANSI stripped in non-TTY test env)
        assertThat(section).contains("Options:");
        assertThat(section).doesNotContain("@|bold");
        // heading must not be preceded by spaces (left-aligned)
        assertThat(section).contains("\nOptions:");
    }

    @Test
    public void test_command_list_heading_is_bold_and_unindented() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        String section = cmd.getHelpSectionMap().get("commandList").render(cmd.getHelp());
        assertThat(section).contains("Commands:");
        assertThat(section).doesNotContain("@|bold");
        assertThat(section).contains("\nCommands:");
    }

    // ===================================================================
    // configure: settings applied recursively
    // ===================================================================

    @Test
    public void test_configure_sets_abbreviated_synopsis_on_root() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        assertThat(cmd.getCommandSpec().usageMessage().abbreviateSynopsis()).isTrue();
    }

    @Test
    public void test_configure_sets_width_on_root() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        assertThat(cmd.getCommandSpec().usageMessage().width()).isEqualTo(HELP_WIDTH);
    }

    @Test
    public void test_configure_sets_sort_options_false_on_root() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        assertThat(cmd.getCommandSpec().usageMessage().sortOptions()).isFalse();
    }

    @Test
    public void test_configure_applies_to_subcommands_recursively() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        for (CommandLine sub : cmd.getSubcommands().values()) {
            assertThat(sub.getCommandSpec().usageMessage().abbreviateSynopsis())
                    .as("abbreviateSynopsis on " + sub.getCommandName()).isTrue();
            assertThat(sub.getCommandSpec().usageMessage().width())
                    .as("width on " + sub.getCommandName()).isEqualTo(HELP_WIDTH);
        }
    }

    @Test
    public void test_configure_footer_set_on_root_only() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        assertThat(cmd.getCommandSpec().usageMessage().footer().length).isGreaterThan(0);
        for (CommandLine sub : cmd.getSubcommands().values()) {
            String[] f = sub.getCommandSpec().usageMessage().footer();
            assertThat(f == null || f.length == 0)
                    .as(sub.getCommandName() + " should have no footer").isTrue();
        }
    }

    // ===================================================================
    // renderGlobalOptionList: Global Options section
    // ===================================================================

    @Test
    public void test_global_option_list_appears_in_leaf_subcommand() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        String output = renderGlobalOptionList(appServe.getHelp());
        assertThat(output).isNotEmpty();
        assertThat(output).contains("--logLevel");
    }

    @Test
    public void test_global_option_list_absent_for_root_command() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        String output = renderGlobalOptionList(cmd.getHelp());
        assertThat(output).isEmpty();
    }

    @Test
    public void test_global_option_list_absent_for_intermediate_subcommand() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine app = cmd.getSubcommands().get("app");
        String output = renderGlobalOptionList(app.getHelp());
        assertThat(output).isEmpty();
    }

    @Test
    public void test_global_option_list_section_heading_rendered() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        String section = cmd.getHelpSectionMap().get("globalOptionList").render(appServe.getHelp());
        assertThat(section).contains("Global Options:");
        assertThat(section).doesNotContain("@|bold");
        assertThat(section).contains("\nGlobal Options:");
    }

    @Test
    public void test_global_option_list_one_line_per_global_option() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine workerRun = cmd.getSubcommands().get("worker").getSubcommands().get("run");
        CommandLine.Help help = workerRun.getHelp();
        String output = renderGlobalOptionList(help);

        long visibleGlobalOptions = help.commandSpec().root().options().stream()
                .filter(o -> !o.hidden()).count();
        long nonBlankLines = lines(output).stream().filter(l -> !l.isBlank()).count();
        assertThat(nonBlankLines)
                .as("each global option must produce exactly one line")
                .isEqualTo(visibleGlobalOptions);
    }

    @Test
    public void test_option_list_is_sorted_alphabetically() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine appServe = cmd.getSubcommands().get("app").getSubcommands().get("start");
        List<String> keys = renderedOptionKeys(renderOptionList(appServe.getHelp()));
        List<String> sorted = keys.stream().sorted().collect(Collectors.toList());
        assertThat(keys).as("options must be sorted alphabetically").isEqualTo(sorted);
    }

    @Test
    public void test_global_option_list_is_sorted_alphabetically() {
        CommandLine cmd = DatashareHelpFactory.configure(new CommandLine(new DatashareCommand()));
        CommandLine workerRun = cmd.getSubcommands().get("worker").getSubcommands().get("run");
        List<String> keys = renderedOptionKeys(renderGlobalOptionList(workerRun.getHelp()));
        List<String> sorted = keys.stream().sorted().collect(Collectors.toList());
        assertThat(keys).as("global options must be sorted alphabetically").isEqualTo(sorted);
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    /**
     * Extracts the sort key for each non-blank line of rendered option output.
     * Uses the long option name (--xxx) if present, otherwise the first option name, lowercased.
     */
    private static List<String> renderedOptionKeys(String rendered) {
        Pattern longOpt = Pattern.compile("--([a-zA-Z][a-zA-Z0-9-]*)");
        Pattern shortOpt = Pattern.compile("-([a-zA-Z])");
        return lines(rendered).stream()
                .filter(l -> !l.isBlank())
                .map(l -> {
                    Matcher m = longOpt.matcher(l);
                    if (m.find()) return m.group(1).toLowerCase();
                    Matcher s = shortOpt.matcher(l);
                    return s.find() ? s.group(1).toLowerCase() : l.trim().toLowerCase();
                })
                .collect(Collectors.toList());
    }
}
