package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.text.indexing.Indexer;

import java.nio.file.Path;

import static java.nio.file.Paths.get;
import static org.icij.datashare.ProcessResource.ProcessResponse.Result.Error;
import static org.icij.datashare.ProcessResource.ProcessResponse.Result.OK;

@Prefix("/process")
public class ProcessResource {
    private final Indexer indexer;

    @Inject
    public ProcessResource(final Indexer indexer) {
        this.indexer = indexer;
    }

    @Post("/index/file/:filePath")
    public ProcessResponse indexFile(final String filePath) {
        Path path = get(filePath.replace("|", "/"));// hack : see https://github.com/CodeStory/fluent-http/pull/143

        if (!path.toFile().exists() || !path.toFile().isDirectory()) {
            return new ProcessResponse(Error);
        }
        return new ProcessResponse(OK);
    }

    static class ProcessResponse {
        enum Result {OK, Error}
        final Result result;

        ProcessResponse(Result result) {
            this.result = result;
        }
      }
}
