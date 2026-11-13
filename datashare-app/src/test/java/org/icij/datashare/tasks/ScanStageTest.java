package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Project;
import static org.fest.assertions.Assertions.assertThat;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ScanStageTest {
    @Rule public TemporaryFolder dataDir = new TemporaryFolder();
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

    private List<String> names() {
        return emitted.stream().map(p -> p.getFileName().toString()).toList();
    }

    private StageResult scan(Map<String, Object> extraOptions) {
        Map<String, Object> options = new HashMap<>(Map.of("dataDir", dataDir.getRoot().toString()));
        options.putAll(extraOptions);
        return new ScanStage().process(List.of(), new ScopedStageContext<>() {
            public Project project() {return Project.project("prj");}

            public Map<String, Object> options() {return options;}

            public boolean isCancelled() {return false;}

            public void emit(List<Path> out) {emitted.addAll(out);}
        });
    }
}
