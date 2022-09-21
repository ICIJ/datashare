package org.icij.datashare.web;

import net.codestory.rest.RestAssert;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Collections;

import static org.fest.assertions.Assertions.assertThat;

public class TreeResourceTest extends AbstractProdWebServerTest {

    @Before
    public void setUp () {
        // Set the data dir to be "./docs/"
        String dataDir = getClass().getResource("/docs/").getPath();
        PropertiesProvider propertiesProvider = new PropertiesProvider(Collections.singletonMap("dataDir", dataDir));
        configure(routes -> routes.add(new TreeResource(propertiesProvider)));
    }

    public JSONArray getJSONArray (String url) throws ParseException {
        RestAssert request = get(url);
        request.should().respond(200);
        String jsonString = request.response().content();
        JSONParser parser = new JSONParser();
        return (JSONArray) parser.parse(jsonString);
    }

    @Test
    public void test_reject_files_tree_with_doc_file () {
        String dirName = getClass().getResource("/docs/doc.txt").getPath();
        get("/api/tree" + dirName).should().respond(400);
    }

    @Test
    public void test_reject_files_tree_with_unknown_directory () {
        String dirName = getClass().getResource("/docs/").getPath();
        get("/api/tree" + dirName + "/foo.bar").should().respond(404);
    }

    @Test
    public void test_reject_files_tree_outside_of_datadir () {
        String dirName = getClass().getResource("/data/").getPath();
        get("/api/tree" + dirName).should().respond(403);
    }

    @Test
    public void test_get_files_tree_in_docs_directory () {
        String dirName = getClass().getResource("/docs/").getPath();
        get("/api/tree" + dirName).should().respond(200);
    }

    @Test
    public void test_get_files_tree_in_docs_subdirectory () {
        String dirName = getClass().getResource("/docs/foo").getPath();
        get("/api/tree" + dirName).should().respond(200);
    }

    @Test
    public void test_get_files_tree_in_docs_directory_as_list () throws ParseException {
        String dirName = getClass().getResource("/docs/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        assertThat(results).hasSize(2);
    }

    @Test
    public void test_get_files_tree_in_docs_directory_as_list_with_name () throws ParseException {
        String dirName = getClass().getResource("/docs/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        JSONObject firstDir = (JSONObject) results.get(0);
        assertThat(firstDir.get("name") + File.separator).isEqualTo(dirName);
        assertThat(firstDir.get("type")).isEqualTo("directory");
    }

    @Test
    public void test_get_files_tree_in_docs_directory_as_list_with_type () throws ParseException {
        String dirName = getClass().getResource("/docs/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        JSONObject firstDir = (JSONObject) results.get(0);
        assertThat(firstDir.get("type")).isEqualTo("directory");
    }


    @Test
    public void test_get_files_tree_in_docs_directory_as_list_with_three_children () throws ParseException {
        String dirName = getClass().getResource("/docs/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        JSONObject firstDir = (JSONObject) results.get(0);
        JSONArray children = (JSONArray) firstDir.get("children");
        assertThat(children).hasSize(3);
    }

    @Test
    public void test_get_files_tree_in_docs_directory_as_list_with_a_child_file () throws ParseException {
        String dirName = getClass().getResource("/docs/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        JSONObject firstDir = (JSONObject) results.get(0);
        JSONArray children = (JSONArray) firstDir.get("children");
        JSONObject firstChild = (JSONObject) children.get(0);
        assertThat(firstChild.get("name")).isEqualTo(dirName + "doc.txt");
        assertThat(firstChild.get("type")).isEqualTo("file");
        assertThat(firstChild.get("size").toString()).isEqualTo("28");
        assertThat(firstChild.get("children")).isNull();
    }

    @Test
    public void test_get_files_tree_with_a_report_in_docs () throws ParseException {
        String dirName = getClass().getResource("/docs/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        JSONObject report = (JSONObject) results.get(1);
        assertThat(report.get("type")).isEqualTo("report");
        assertThat(report.get("directories").toString()).isEqualTo("1");
        assertThat(report.get("files").toString()).isEqualTo("2");
    }

    @Test
    public void test_get_files_tree_with_a_report_in_docs_foo () throws ParseException {
        String dirName = getClass().getResource("/docs/foo/").getPath();
        JSONArray results = getJSONArray("/api/tree" + dirName);
        JSONObject report = (JSONObject) results.get(1);
        assertThat(report.get("type")).isEqualTo("report");
        assertThat(report.get("directories").toString()).isEqualTo("0");
        assertThat(report.get("files").toString()).isEqualTo("1");
    }
}
