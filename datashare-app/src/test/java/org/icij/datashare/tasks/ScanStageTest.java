package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.asynctasks.DefaultStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Project;
import org.icij.datashare.utils.DataDirVerifier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.event.Level;

public class ScanStageTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();
    private final List<Path> emitted = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        dataDir.newFile("doc.txt");
        dataDir.newFile("doc.pdf");
        dataDir.newFile(".hidden.txt");
        dataDir.newFolder("sub");
        dataDir.newFile("sub/deep.txt");
    }

    @Test
    public void test_scan_with_default_options() {
        assertThat(scan(Map.of()).updated()).isEqualTo(4);
    }

    @Test
    public void test_include_pattern_option_filters_the_scan() {
        scan(Map.of("includePattern", "**/*.txt"));

        assertThat(names()).containsOnly("doc.txt", ".hidden.txt", "deep.txt");
    }

    @Test
    public void test_exclude_pattern_option_filters_the_scan() {
        scan(Map.of("excludePattern", List.of("**/*.pdf", "**/sub/**")));

        assertThat(names()).containsOnly("doc.txt", ".hidden.txt");
    }

    @Test
    public void test_hidden_files_can_be_left_out() {
        scan(Map.of("includeHiddenFiles", "false"));

        assertThat(names()).excludes(".hidden.txt");
    }

    @Test
    public void test_max_depth_option_limits_the_walk() {
        scan(Map.of("maxDepth", "1"));

        assertThat(names()).excludes("deep.txt");
    }

    @Test
    public void test_refuses_path_outside_data_dir() {
        assertRefused(Paths.get("/"));
    }

    @Test
    public void test_refuses_parent_traversal() {
        assertRefused(Paths.get("/path/to/data/../../etc"));
    }

    @Test
    public void test_scan_refuses_a_sibling() throws Exception {
        assertRefused(Paths.get("/path/to/data-other"));
    }

    private void assertRefused(Path path) {
        StageResult result = scan(Map.of("dataDir", path.toString()));
        assertThat(result.failed()).isEqualTo(1);
        assertThat(logbackCapturingRule.logs(Level.WARN).size()).isGreaterThan(0);
    }

    private List<String> names() {
        return emitted.stream().map(p -> p.getFileName().toString()).toList();
    }

    private StageResult scan(Map<String, Object> extraOptions) {
        Map<String, Object> options = new HashMap<>(Map.of("dataDir", dataDir.getRoot().toString()));
        options.putAll(extraOptions);
        return new ScanStage(new DataDirVerifier(dataDir.getRoot().toPath()))
                .process(List.of(), new DefaultStageContext<>("taskId", Project.project("prj"), options, out -> emitted.addAll(out)));
    }
}
