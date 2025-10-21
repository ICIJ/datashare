package org.icij.datashare.text;

import junit.framework.TestCase;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.ProjectProxy.*;

public class ProjectProxyTest extends TestCase {

    public void test_as_name_list_returns_project_names_as_string_list() {
        List<ProjectProxy> list = List.of(new ProjectProxy[]{
                new ProjectProxy("prj1"),
                new ProjectProxy("prj2")
        });
        assertThat(asNameList(list)).containsExactly("prj1","prj2");
    }

    public void test_as_name_array_returns_project_names_as_string_array() {
        List<ProjectProxy> list = List.of(new ProjectProxy[]{
                new ProjectProxy("prj1"),
                new ProjectProxy("prj2")
        });
        assertThat(asNameArray(list)).isEqualTo(new String[]{"prj1","prj2"});
    }
    public void test_as_comma_concat_names_string_returns_project_names_concatenated() {
        List<ProjectProxy> list = List.of(new ProjectProxy[]{
                new ProjectProxy("prj1"),
                new ProjectProxy("prj2")
        });
        assertThat(asCommaConcatNames(list)).isEqualTo("prj1, prj2");
    }
    public void test_as_names_string_list_returns_project_proxy_from_names() {
        List<String> list = List.of(new String[]{
                "prj1",
                "prj2"
        });
        assertThat(fromNameStringList(list)).isEqualTo(List.of(new ProjectProxy[]{
                new ProjectProxy("prj1"),
                new ProjectProxy("prj2")
        }));
    }
}