package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;

public abstract class AbstractWebAppTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
          return Env.prod();
        }
      }.startOnRandomPort();

      @Rule
      public ExpectedException thrown = ExpectedException.none();

      @Override
      public int port() {
        return server.port();
      }

      @BeforeClass
      static public void setUpClass() {
          server.configure(WebApp.getConfiguration());
      }

      protected void shouldFail(String message) {
        thrown.expect(AssertionError.class);
        thrown.expectMessage(message);
    }
}
