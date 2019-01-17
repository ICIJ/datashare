package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.junit.After;
import org.junit.Test;

import java.util.Properties;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.Mockito.mock;

public class UserTaskResourceTest implements FluentRestTest {
    private WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private TaskManager taskManager;

    @Override
    public int port() {
        return server.port();
    }

    @After
    public void tearDown() {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.cleanDoneTasks();
    }

    @Test
    public void test_task_list_in_server_mode() {
        setupAppWith("foo", "bar");
        TaskManager.MonitorableFutureTask<Void> t1 = taskManager.startTask(new TaskManager.MonitorableFutureTask(new DummyUserTask("foo"), String.class));
        TaskManager.MonitorableFutureTask<Void> t2 = taskManager.startTask(new TaskManager.MonitorableFutureTask(new DummyUserTask("bar"), String.class));

        get("/api/task/").withPreemptiveAuthentication("foo", "qux").should().contain(format("[{\"name\":\"%s\",\"state\":\"DONE\",\"progress\":1.0}]", t1.toString()));
        get("/api/task/").withPreemptiveAuthentication("bar", "qux").should().contain(format("[{\"name\":\"%s\",\"state\":\"DONE\",\"progress\":1.0}]", t2.toString()));
    }

    static class DummyUserTask implements UserTask, Runnable {
        private final String user;
        public DummyUserTask(String user) {this.user = user;}
        @Override public void run() {}
        @Override public User getUser() { return new User(user);}
    }

    private void setupAppWith(String... userLogins) {
        final PropertiesProvider propertiesProvider = new PropertiesProvider();
        taskManager = new TaskManager(propertiesProvider);
        server.configure(new CommonMode(new Properties()) {
            @Override
            protected void configure() {
                bind(PropertiesProvider.class).toInstance(propertiesProvider);
                bind(SessionIdStore.class).toInstance(SessionIdStore.inMemory());
                bind(Filter.class).toInstance(new BasicAuthFilter("/", "ds", HashMapUser.users(userLogins)));
                bind(TaskManager.class).toInstance(taskManager);
                bind(TaskFactory.class).toInstance(mock(TaskFactory.class));
                bind(Indexer.class).toInstance(mock(Indexer.class));
            }
            @Override protected Routes addModeConfiguration(Routes routes) { return routes.add(TaskResource.class);}
        }.createWebConfiguration());
    }
}
