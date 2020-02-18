/**
 * Copyright (C) 2013-2015 all@code-story.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package org.icij.datashare.web.testhelpers;
import net.codestory.http.Configuration;
import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import org.junit.rules.ExternalResource;

import java.util.function.Supplier;

import static net.codestory.http.Configuration.NO_ROUTE;
import static net.codestory.http.misc.MemoizingSupplier.memoize;

public class ProdWebServerRule extends ExternalResource {
  private static Supplier<WebServer> server = memoize(() -> new WebServer() {
    @Override
    protected Env createEnv() {
      return Env.prod();
    }
  }.startOnRandomPort());

  @Override
  protected void after() {
    server.get().configure(NO_ROUTE);
  }

  public void configure(Configuration configuration) {
    server.get().configure(configuration);
  }

  public int port() {
    return server.get().port();
  }
}
