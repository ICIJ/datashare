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
import net.codestory.rest.FluentRestTest;
import org.junit.ClassRule;

import java.util.concurrent.TimeoutException;

public abstract class AbstractProdWebServerTest implements FluentRestTest {
  @ClassRule
  public static ProdWebServerRule server = new ProdWebServerRule();

  @Override
  public int port() {
    return server.port();
  }

  protected void configure(Configuration configuration) {
    server.configure(configuration);
  }

  protected void waitForDatashare() throws Exception {
      for(int nbTries = 10; nbTries > 0 ; nbTries--) {
          if (get("/settings").response().contentType().contains("application/json")) {
              return;
          }
          Thread.sleep(500); // ms
      }
      throw new TimeoutException("Connection to Datashare failed (maybe linked to Elasticsearch)");
  }
}
