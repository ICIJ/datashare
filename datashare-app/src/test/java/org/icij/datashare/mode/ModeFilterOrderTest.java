package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.session.ApiKeyFilter;
import org.icij.datashare.session.CsrfFilter;
import org.icij.datashare.session.LocalUserFilter;
import org.junit.Test;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static org.icij.datashare.EnvUtils.resolveUri;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

// fluent-http stores filters with Deque.addFirst and wraps them from head to tail, so the filter registered first is
// the outermost one and runs first. CsrfFilter reads the current user and the API key marker, so it must come last.
public class ModeFilterOrderTest {
    @Test
    public void test_server_mode_runs_csrf_filter_after_authentication() throws Exception {
        Routes routes = mock(Routes.class, RETURNS_SELF);

        serverMode().addModeConfiguration(routes);

        InOrder order = inOrder(routes);
        order.verify(routes).filter(ApiKeyFilter.class);
        order.verify(routes).filter(Filter.class);
        order.verify(routes).filter(CsrfFilter.class);
    }

    @Test
    public void test_local_mode_runs_csrf_filter_after_the_user_filter() {
        Routes routes = mock(Routes.class, RETURNS_SELF);

        localMode().addModeConfiguration(routes);

        InOrder order = inOrder(routes);
        order.verify(routes).filter(LocalUserFilter.class);
        order.verify(routes).filter(CsrfFilter.class);
    }

    private static CommonMode serverMode() throws Exception {
        Path home = Files.createTempDirectory("datashare-");
        String[] args = {"--mode=SERVER", format("--dataSourceUrl=jdbc:sqlite:file:%s/db.db", home),
                format("--pluginsDir=%s", home), format("--extensionsDir=%s", home)};
        return CommonMode.create(new DatashareCli().parseArguments(args).properties);
    }

    private static CommonMode localMode() {
        return CommonMode.create(new HashMap<>(Map.of(
                "mode", "LOCAL",
                "dataDir", ModeFilterOrderTest.class.getResource("/data").getPath(),
                "extensionsDir", ModeFilterOrderTest.class.getResource("/extensions").getPath(),
                "pluginsDir", ModeFilterOrderTest.class.getResource("/plugins").getPath(),
                "redisAddress", resolveUri("redis", "redis://redis:6379"),
                "elasticsearchAddress", resolveUri("elasticsearch", "http://elasticsearch:9200"),
                "messageBusAddress", "amqp://admin:s3cret@rabbitmq:5672")));
    }
}
