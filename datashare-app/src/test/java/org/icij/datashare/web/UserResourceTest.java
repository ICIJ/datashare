package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.junit.Test;

import static org.icij.datashare.session.HashMapUser.singleUser;

public class UserResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
            @Override
            protected Env createEnv() {
                return Env.prod();
            }
        }.startOnRandomPort();
    @Override public int port() { return server.port();}

    @Test
    public void get_user_information_test() {
        server.configure(routes -> routes.add(new UserResource()).
                        filter(new BasicAuthFilter("/", "icij", singleUser("pierre"))));

        get("/api/user").withPreemptiveAuthentication("pierre", "pass").
                should().respond(200).contain("\"uid\":\"pierre\"");
    }

}
