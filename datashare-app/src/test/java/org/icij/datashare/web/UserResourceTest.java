package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Paths;

import static java.util.Collections.singletonList;
import static org.icij.datashare.UserEvent.Type.DOCUMENT;
import static org.icij.datashare.session.DatashareUser.singleUser;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class UserResourceTest extends AbstractProdWebServerTest {
    @Mock Repository repository;

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new UserResource(repository)).filter(new LocalUserFilter(new PropertiesProvider())));
    }

    @Test
    public void get_user_information_test() {
        configure(routes -> routes.add(new UserResource(repository)).
                        filter(new BasicAuthFilter("/", "icij", singleUser("pierre"))));

        get("/api/users/me").withPreemptiveAuthentication("pierre", "pass").
                should().respond(200).contain("\"uid\":\"pierre\"");
    }

    @Test
    public void test_get_user_history() {
        UserEvent userEvent = new UserEvent(User.local(), DOCUMENT, "doc_name", Paths.get("doc_uri").toUri());
        when(repository.getUserEvents(User.local())).thenReturn(singletonList(userEvent));

        get("/api/users/me/history").should().contain(userEvent.uri.toString()).contain(User.local().id).respond(200);
    }
}
