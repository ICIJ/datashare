package org.icij.datashare;

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

    public ProcessResource(final Indexer indexer) {
        this.indexer = indexer;
    }

    @Post("/index/file/:filePath")
    public ProcessResponse indexFile(final String filePath) {
        Path path = get(filePath.replace("|", "/"));// hack : see https://github.com/CodeStory/fluent-http/pull/143

        if (!path.toFile().exists() || !path.toFile().isDirectory()) {
            return new ProcessResponse(Error);
        }
//        DataShare.StandAlone.processDirectory(
//                path, 1, splitComma.andThen(NlpStage.parseAll).apply("NER"),
//                splitComma.andThen(NamedEntity.Category.parseAll).apply("ORG,PERS,LOC"),
//                splitComma.andThen(Pipeline.Type.parseAll).apply("CORE,OPEN,GATE,IXA,MITIE"),
//                1, indexer, "doc");
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
