package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Test;

import static org.icij.datashare.session.HashMapUser.singleUser;

public class UserResourceTest extends AbstractProdWebServerTest {
    @Test
    public void get_user_information_test() {
        configure(routes -> routes.add(new UserResource()).
                        filter(new BasicAuthFilter("/", "icij", singleUser("pierre"))));

        get("/api/users/me").withPreemptiveAuthentication("pierre", "pass").
                should().respond(200).contain("\"uid\":\"pierre\"");
    }

}
