package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.ExtractedStreamer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

public class ElasticsearchExtractedStreamer implements ExtractedStreamer {
    final Indexer indexer;
    private final String indexName;

    @Inject
    public ElasticsearchExtractedStreamer(final Indexer indexer, final String indexName) {
        this.indexer = indexer;
        this.indexName = indexName;
    }

    @Override
    public Stream<Path> extractedDocuments() throws IOException {
        return indexer.search(indexName, Document.class).withSource("path").execute().map(d -> ((Document)d).getPath());
    }
}
