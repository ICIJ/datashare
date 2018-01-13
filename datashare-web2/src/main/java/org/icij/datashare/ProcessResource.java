package org.icij.datashare;

import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;

import static java.nio.file.Paths.get;
import static org.icij.datashare.ProcessResource.ProcessResponse.Result.Error;
import static org.icij.datashare.ProcessResource.ProcessResponse.Result.OK;

@Prefix("/process")
public class ProcessResource {
    @Post("/index/file/:filePath")
    public ProcessResponse indexFile(final String filePath) {
        String path = filePath.replace("|", "/");// hack : see https://github.com/CodeStory/fluent-http/pull/143
        if (!get(path).toFile().exists()) {
            return new ProcessResponse(Error);
        }
        return new ProcessResponse(OK);
    }

    static class ProcessResponse {
        enum Result {OK, Error}

        ;
        final Result result;

        ProcessResponse(Result result) {
            this.result = result;
        }
      }
}
