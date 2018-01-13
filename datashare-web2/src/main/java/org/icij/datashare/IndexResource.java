package org.icij.datashare;

import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;

@Prefix("/index")
public class IndexResource {
      @Post("/search")
      public void search(Search search) {
      }
}
