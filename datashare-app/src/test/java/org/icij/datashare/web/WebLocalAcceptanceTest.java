package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.rest.Response;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.Repository;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

public class WebLocalAcceptanceTest extends AbstractProdWebServerTest {
    @Mock Repository jooqRepository;

    private static AutoCloseable mocks;

    @Before
    public void setUp() throws Exception {
        mocks = openMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        Map<String, Object> properties = Map.of(
            "mode", "LOCAL",
            "dataDir", WebLocalAcceptanceTest.class.getResource("/data").getPath(),
            "extensionsDir", WebLocalAcceptanceTest.class.getResource("/extensions").getPath(),
            "pluginsDir", WebLocalAcceptanceTest.class.getResource("/plugins").getPath(),
            "redisAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"),
            "elasticsearchAddress", EnvUtils.resolveUri("elasticsearch", "http://elasticsearch:9200")
        );
        configure(CommonMode.create(properties).createWebConfiguration());
        waitForDatashare();
    }

    @After
    public void teardown() throws Exception {
        mocks.close();
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().haveType("text/html").contain("<title>datashare-client</title>");
    }

    @Test
    public void test_get_settings() {
        get("/settings").should().haveType("application/json").
                contain(format("\"dataDir\":\"%s\"", getClass().getResource("/data").getPath()));
    }

    @Test
    public void test_get_version() throws Exception {
        Response response = get("/version").response();

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.contentType()).contains("application/json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(response.content(), new TypeReference<>() {});
        assertThat(map.keySet()).contains("git.commit.id", "git.commit.id.abbrev");
    }

    @Test
    public void test_get_extensions_list_installed() {
        get("/api/extensions").should().haveType("application/json").contain("\"installed\":true");
    }

    @Test
    public void test_get_plugins_list_installed() {
        get("/api/plugins").should().haveType("application/json").contain("\"installed\":true");
    }

    @Test
    public void test_get_extensions_plugins_without_directory() throws Exception {
        Map<String, Object> properties = Map.of("mode", "LOCAL",
            "elasticsearchAddress", EnvUtils.resolveUri("elasticsearch", "http://elasticsearch:9200")
        );
        configure(CommonMode.create(properties).createWebConfiguration());
        waitForDatashare();
        get("/api/extensions").should().haveType("application/json").contain("\"installed\":false");
        get("/api/plugins").should().haveType("application/json").contain("\"installed\":false");
    }
}
