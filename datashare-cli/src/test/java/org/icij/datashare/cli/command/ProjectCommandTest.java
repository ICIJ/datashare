package org.icij.datashare.cli.command;

import org.icij.datashare.cli.Prompter;
import org.junit.Test;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class ProjectCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_project_with_no_subcommand_exits_2() {
        int exit = parseExitCode("project");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    public void test_project_create_minimal_emits_name() {
        Properties props = parse("project", "create", "my-project");
        assertThat(props).includes(entry("projectCreate", "my-project"));
    }

    @Test
    public void test_project_create_all_flags_propagate_as_sibling_keys() {
        Properties props = parse("project", "create", "my-project",
                "--label", "My Project",
                "--description", "leak archive",
                "--source-path", "/data/my-project",
                "--allow-from-mask", "10.0.0.0",
                "--source-url", "https://src/",
                "--maintainer-name", "Maint",
                "--publisher-name", "Pub",
                "--logo-url", "https://logo.png",
                "--creation-date", "2026-05-15T10:00:00Z",
                "--update-date", "2026-05-16T10:00:00Z",
                "--no-index",
                "--if-not-exists",
                "--json");

        assertThat(props).includes(entry("projectCreate", "my-project"));
        assertThat(props).includes(entry("projectCreate.label", "My Project"));
        assertThat(props).includes(entry("projectCreate.description", "leak archive"));
        assertThat(props).includes(entry("projectCreate.sourcePath", "/data/my-project"));
        assertThat(props).includes(entry("projectCreate.allowFromMask", "10.0.0.0"));
        assertThat(props).includes(entry("projectCreate.sourceUrl", "https://src/"));
        assertThat(props).includes(entry("projectCreate.maintainerName", "Maint"));
        assertThat(props).includes(entry("projectCreate.publisherName", "Pub"));
        assertThat(props).includes(entry("projectCreate.logoUrl", "https://logo.png"));
        assertThat(props).includes(entry("projectCreate.creationDate", "2026-05-15T10:00:00Z"));
        assertThat(props).includes(entry("projectCreate.updateDate", "2026-05-16T10:00:00Z"));
        assertThat(props).includes(entry("projectCreate.noIndex", "true"));
        assertThat(props).includes(entry("projectCreate.ifNotExists", "true"));
        assertThat(props).includes(entry("projectCreate.json", "true"));
    }

    @Test
    public void test_project_create_invalid_creation_date_exits_5() {
        int exit = parseExitCode("project", "create", "my-project", "--creation-date", "not-a-date");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_create_creator_flag_propagates() {
        Properties props = parse("project", "create", "my-project", "--creator", "promera");
        assertThat(props).includes(entry("projectCreate", "my-project"));
        assertThat(props).includes(entry("projectCreate.creator", "promera"));
    }

    @Test
    public void test_project_create_invalid_name_exits_5() {
        int exit = parseExitCode("project", "create", "Has-Uppercase");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_create_invalid_allow_from_mask_exits_5() {
        int exit = parseExitCode("project", "create", "my-project", "--allow-from-mask", "not-a-mask");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_create_invalid_source_url_exits_5() {
        int exit = parseExitCode("project", "create", "my-project", "--source-url", "not a uri");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_create_smoke_end_to_end() {
        Properties props = parse("project", "create", "my-project",
                "--label", "My Project",
                "--source-path", "/data/my-project",
                "--json");

        // The CLI layer only produces properties; the dispatcher (tested separately
        // in CliAppProjectDispatchTest) consumes them. This smoke test confirms the
        // picocli graph hands off a coherent set of typed sibling keys.
        assertThat(props).includes(entry("projectCreate", "my-project"));
        assertThat(props).includes(entry("projectCreate.label", "My Project"));
        assertThat(props).includes(entry("projectCreate.sourcePath", "/data/my-project"));
        assertThat(props).includes(entry("projectCreate.json", "true"));
    }

    @Test
    public void test_project_create_prompts_for_settable_fields_when_missing() {
        // Operator types: blank for label (accept default), a description, a custom
        // source path, blank for source-url, blank for maintainer, blank for publisher,
        // blank for logo. Each newline submits one prompt's answer.
        String typed = String.join("\n",
                "",                          // Label: blank -> null -> service defaults to name
                "leak archive",              // Description
                "/data/my-project",          // Source path
                "",                          // Source URL: blank -> null
                "ICIJ",                      // Maintainer name
                "",                          // Publisher name: blank -> null
                ""                           // Logo URL: blank -> null
        ) + "\n";
        ProjectCreateCommand cmd = new ProjectCreateCommand();
        StringWriter sink = new StringWriter();
        cmd.prompterOverride = new Prompter(
                new BufferedReader(new StringReader(typed)),
                new PrintWriter(sink, true),
                () -> new char[0]);
        cmd.namePositional = "my-project";
        cmd.noInput = false;
        cmd.spec = new CommandLine(cmd).getCommandSpec();
        cmd.run();
        Properties props = cmd.getSubcommandProperties();

        assertThat(props.getProperty("projectCreate")).isEqualTo("my-project");
        // Blank label -> not emitted (service applies default = name).
        assertThat(props.getProperty("projectCreate.label")).isNull();
        assertThat(props.getProperty("projectCreate.description")).isEqualTo("leak archive");
        assertThat(props.getProperty("projectCreate.sourcePath")).isEqualTo("/data/my-project");
        assertThat(props.getProperty("projectCreate.sourceUrl")).isNull();
        assertThat(props.getProperty("projectCreate.maintainerName")).isEqualTo("ICIJ");
        assertThat(props.getProperty("projectCreate.publisherName")).isNull();
        assertThat(props.getProperty("projectCreate.logoUrl")).isNull();
        // Auto-derived fields are never prompted: creationDate/updateDate are
        // stamped by the service. (allowFromMask defaults via picocli's
        // defaultValue annotation, which is only applied when the command is
        // routed through CommandLine.execute(); this direct-instantiation
        // test bypasses that, so the field stays null here.)
        assertThat(props.getProperty("projectCreate.creationDate")).isNull();
        assertThat(props.getProperty("projectCreate.updateDate")).isNull();
    }

    @Test
    public void test_project_create_skips_prompts_for_flags_already_passed() {
        // Operator passes --label and --description on the command line; only the
        // remaining settable fields should prompt. The reader has exactly 5 blank
        // lines (source-path, source-url, maintainer, publisher, logo-url).
        ProjectCreateCommand cmd = new ProjectCreateCommand();
        StringWriter sink = new StringWriter();
        cmd.prompterOverride = new Prompter(
                new BufferedReader(new StringReader("\n\n\n\n\n")),
                new PrintWriter(sink, true),
                () -> new char[0]);
        cmd.namePositional = "my-project";
        cmd.label = "My Project";
        cmd.description = "preset description";
        cmd.noInput = false;
        cmd.spec = new CommandLine(cmd).getCommandSpec();
        cmd.run();
        Properties props = cmd.getSubcommandProperties();

        assertThat(props.getProperty("projectCreate.label")).isEqualTo("My Project");
        assertThat(props.getProperty("projectCreate.description")).isEqualTo("preset description");
        // Prompt for label / description never displayed because flags were set.
        assertThat(sink.toString()).excludes("Label [");
        assertThat(sink.toString()).excludes("Description");
        // The other prompts did display.
        assertThat(sink.toString()).contains("Source path [/vault/my-project]");
    }

    @Test
    public void test_project_delete_minimal_emits_name() {
        Properties props = parse("project", "delete", "my-project");
        assertThat(props).includes(entry("projectDelete", "my-project"));
    }

    @Test
    public void test_project_delete_all_flags_propagate() {
        Properties props = parse("project", "delete", "my-project",
                "--yes", "--keep-index", "--if-exists", "--no-input", "--json");
        assertThat(props).includes(entry("projectDelete", "my-project"));
        assertThat(props).includes(entry("projectDelete.yes", "true"));
        assertThat(props).includes(entry("projectDelete.keepIndex", "true"));
        assertThat(props).includes(entry("projectDelete.ifExists", "true"));
        assertThat(props).includes(entry("projectDelete.noInput", "true"));
        assertThat(props).includes(entry("projectDelete.json", "true"));
    }

    @Test
    public void test_project_delete_invalid_name_exits_5() {
        int exit = parseExitCode("project", "delete", "Has-Uppercase");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_delete_smoke_end_to_end() {
        Properties props = parse("project", "delete", "my-project", "--yes", "--keep-index", "--json");
        assertThat(props).includes(entry("projectDelete", "my-project"));
        assertThat(props).includes(entry("projectDelete.yes", "true"));
        assertThat(props).includes(entry("projectDelete.keepIndex", "true"));
        assertThat(props).includes(entry("projectDelete.json", "true"));
    }

    @Test
    public void test_project_grant_minimal_emits_project_user_role() {
        Properties props = parse("project", "grant", "demeter", "promera", "admin");
        assertThat(props).includes(entry("projectGrant", "demeter"));
        assertThat(props).includes(entry("projectGrant.user", "promera"));
        assertThat(props).includes(entry("projectGrant.role", "admin"));
    }

    @Test
    public void test_project_grant_all_flags_propagate() {
        Properties props = parse("project", "grant", "demeter", "promera", "editor",
                "--if-not-exists", "--json");
        assertThat(props).includes(entry("projectGrant", "demeter"));
        assertThat(props).includes(entry("projectGrant.user", "promera"));
        assertThat(props).includes(entry("projectGrant.role", "editor"));
        assertThat(props).includes(entry("projectGrant.ifNotExists", "true"));
        assertThat(props).includes(entry("projectGrant.json", "true"));
    }

    @Test
    public void test_project_grant_invalid_project_exits_5() {
        int exit = parseExitCode("project", "grant", "Has-Uppercase", "promera", "admin");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_grant_invalid_role_exits_5() {
        int exit = parseExitCode("project", "grant", "demeter", "promera", "owner");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_grant_missing_role_with_no_input_exits_2() {
        int exit = parseExitCode("project", "grant", "demeter", "promera", "--no-input");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    public void test_project_grant_role_canonicalised_lowercase() {
        Properties props = parse("project", "grant", "demeter", "promera", "ADMIN");
        assertThat(props).includes(entry("projectGrant.role", "admin"));
    }

    @Test
    public void test_project_grant_prompts_for_missing_positionals() {
        String typed = String.join("\n", "demeter", "promera", "editor") + "\n";
        ProjectGrantCommand cmd = new ProjectGrantCommand();
        StringWriter sink = new StringWriter();
        cmd.prompterOverride = new Prompter(
                new BufferedReader(new StringReader(typed)),
                new PrintWriter(sink, true),
                () -> new char[0]);
        cmd.noInput = false;
        cmd.spec = new CommandLine(cmd).getCommandSpec();
        cmd.run();
        Properties props = cmd.getSubcommandProperties();

        assertThat(props.getProperty("projectGrant")).isEqualTo("demeter");
        assertThat(props.getProperty("projectGrant.user")).isEqualTo("promera");
        assertThat(props.getProperty("projectGrant.role")).isEqualTo("editor");
    }

    @Test
    public void test_project_revoke_minimal_emits_project_user() {
        Properties props = parse("project", "revoke", "demeter", "promera");
        assertThat(props).includes(entry("projectRevoke", "demeter"));
        assertThat(props).includes(entry("projectRevoke.user", "promera"));
    }

    @Test
    public void test_project_revoke_all_flags_propagate() {
        Properties props = parse("project", "revoke", "demeter", "promera",
                "--yes", "--if-exists", "--no-input", "--json");
        assertThat(props).includes(entry("projectRevoke", "demeter"));
        assertThat(props).includes(entry("projectRevoke.user", "promera"));
        assertThat(props).includes(entry("projectRevoke.yes", "true"));
        assertThat(props).includes(entry("projectRevoke.noInput", "true"));
        assertThat(props).includes(entry("projectRevoke.ifExists", "true"));
        assertThat(props).includes(entry("projectRevoke.json", "true"));
    }

    @Test
    public void test_project_revoke_invalid_project_exits_5() {
        int exit = parseExitCode("project", "revoke", "Has-Uppercase", "promera");
        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_project_revoke_missing_user_with_no_input_exits_2() {
        int exit = parseExitCode("project", "revoke", "demeter", "--no-input");
        assertThat(exit).isEqualTo(2);
    }

    @Test
    public void test_project_grant_help_exits_0() {
        int exit = parseExitCode("project", "grant", "-h");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    public void test_project_revoke_help_exits_0() {
        int exit = parseExitCode("project", "revoke", "-h");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    public void test_project_grant_positional_wins_over_flag() {
        // Spec contract: positional values win when also passed as flags.
        Properties props = parse("project", "grant", "demeter", "promera", "admin",
                "--project", "ignored-project",
                "--user", "ignored-user",
                "--role", "editor");
        assertThat(props).includes(entry("projectGrant", "demeter"));
        assertThat(props).includes(entry("projectGrant.user", "promera"));
        assertThat(props).includes(entry("projectGrant.role", "admin"));
    }
}
