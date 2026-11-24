package net.codestory.http.filters.auth;

import net.codestory.http.Context;
import net.codestory.http.Cookie;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.FormAuthFilter;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.session.OAuth2CookieFilter;
import org.icij.datashare.session.UserStore;
import org.icij.datashare.session.UsersIdProviderCache;
import org.icij.datashare.session.YesCookieAuthFilter;
import org.junit.After;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Lives in fluent-http's package so it can call the protected CookieAuthFilter hooks every Datashare filter overrides.
public class DatashareSessionCookieTest {
    private static final long SEED = 42L;
    private static final String SESSION_ID_PATTERN = "[0-9a-f]{32}(\\.[0-9a-f]{64})?";

    private final SessionIdStore sessionIdStore = SessionIdStore.inMemory();

    @Test
    public void test_session_cookie_stays_readable_by_the_client() {
        for (CookieAuthFilter filter : filtersWith(Map.of())) {
            Cookie cookie = filter.authCookie("value");

            assertThat(cookie.isHttpOnly()).as(filter.getClass().getSimpleName()).isFalse();
        }
    }

    @Test
    public void test_session_cookie_is_not_secure_by_default() {
        for (CookieAuthFilter filter : filtersWith(Map.of())) {
            Cookie cookie = filter.authCookie("value");

            assertThat(cookie.isSecure()).as(filter.getClass().getSimpleName()).isFalse();
        }
    }

    @Test
    public void test_session_cookie_is_secure_when_the_option_is_set() {
        for (CookieAuthFilter filter : filtersWith(Map.of("secureSessionCookie", "true"))) {
            Cookie cookie = filter.authCookie("value");

            assertThat(cookie.isSecure()).as(filter.getClass().getSimpleName()).isTrue();
        }
    }

    @Test
    public void test_signout_cookie_is_secure_when_the_option_is_set() {
        for (CookieAuthFilter filter : filtersWith(Map.of("secureSessionCookie", "true"))) {
            Context context = mock(Context.class, RETURNS_DEEP_STUBS);

            Payload payload = filter.signout(context);

            Cookie cookie = payload.cookies().get(0);
            assertThat(cookie.isSecure()).as(filter.getClass().getSimpleName()).isTrue();
        }
    }

    @Test
    public void test_session_cookie_set_through_apply_is_secure_on_https_requests() throws Exception {
        for (CookieAuthFilter filter : filtersWith(Map.of())) {
            Context context = mock(Context.class, RETURNS_DEEP_STUBS);
            when(context.method()).thenReturn("GET");
            when(context.header("x-forwarded-proto")).thenReturn("https");

            Payload payload = filter.apply("/auth/signout", context, Payload::ok);

            Cookie cookie = payload.cookies().get(0);
            assertThat(cookie.isSecure()).as(filter.getClass().getSimpleName()).isTrue();
        }
    }

    @Test
    public void test_session_id_is_32_hex_chars() {
        for (CookieAuthFilter filter : filtersWith(Map.of())) {
            String sessionId = filter.newSessionId("alice");

            assertThat(sessionId).as(filter.getClass().getSimpleName()).matches(SESSION_ID_PATTERN);
        }
    }

    @Test
    public void test_session_id_cannot_be_predicted_from_java_util_random() {
        for (CookieAuthFilter filter : filtersWith(Map.of())) {
            String predictedId = seedSharedRandomAndPredictNextId();

            String sessionId = filter.newSessionId("alice");

            assertThat(sessionId).as(filter.getClass().getSimpleName()).doesNotContain(predictedId);
        }
    }

    @Test
    public void test_oauth_state_cannot_be_predicted_from_java_util_random() throws Exception {
        CookieAuthFilter filter = oauthFilter(new PropertiesProvider());
        Context context = mock(Context.class, RETURNS_DEEP_STUBS);
        String predictedState = seedSharedRandomAndPredictNextId();

        filter.signin(context);

        assertThat(sessionIdStore.getLogin(predictedState)).isNull();
    }

    @After
    public void tearDown() {
        long unpredictableSeed = new SecureRandom().nextLong();
        CookieAuthFilter.RANDOM.setSeed(unpredictableSeed);
    }

    private static String seedSharedRandomAndPredictNextId() {
        CookieAuthFilter.RANDOM.setSeed(SEED);
        Random sameSequence = new Random(SEED);
        String firstHalf = Long.toHexString(sameSequence.nextLong());
        String secondHalf = Long.toHexString(sameSequence.nextLong());
        return firstHalf + secondHalf;
    }

    private List<CookieAuthFilter> filtersWith(Map<String, Object> properties) {
        PropertiesProvider propertiesProvider = new PropertiesProvider(properties);
        Repository repository = mock(Repository.class);
        UsersIdProviderCache usersCache = mock(UsersIdProviderCache.class);
        return List.of(
                new FormAuthFilter(propertiesProvider, mock(Users.class), sessionIdStore, null),
                new LocalUserFilter(propertiesProvider, repository),
                new YesCookieAuthFilter(propertiesProvider, usersCache, sessionIdStore, repository),
                oauthFilter(propertiesProvider));
    }

    private OAuth2CookieFilter oauthFilter(PropertiesProvider propertiesProvider) {
        UsersIdProviderCache usersCache = mock(UsersIdProviderCache.class);
        UserStore userStore = mock(UserStore.class);
        return new OAuth2CookieFilter(propertiesProvider, usersCache, userStore, sessionIdStore, null);
    }
}
