package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.icij.spewer.Spewer;
import org.icij.task.Options;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.Paths.get;
import static java.util.Objects.requireNonNull;
import static org.fest.assertions.Assertions.assertThat;

public class ElasticsearchSpewerStreamingTest {
    @ClassRule
    public static org.icij.datashare.test.ElasticsearchRule es = new org.icij.datashare.test.ElasticsearchRule();

    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer,
            new MemoryDocumentCollectionFactory<>(), l -> Language.ENGLISH, new FieldNames(),
            new PropertiesProvider(new HashMap<>() {{ put("defaultProject", es.getIndexName()); }}));

    private static final String FIXTURE = "/docs/embedded_doc.eml";

    // Build a streaming extractor (default streamingSpew=true). DigestIdentifier so root and embed
    // get distinct content-based ids; same configuration across probe and streaming runs so ids match.
    private Extractor streamingExtractor() {
        Extractor extractor = new Extractor(
                new DocumentFactory().withIdentifier(new DigestIdentifier("SHA-384", Charset.defaultCharset())),
                Options.from(Map.of("streamingSpew", "true", "progressHeartbeatInterval", "0")));
        extractor.setDigester(new UpdatableDigester("test", "SHA-384"));
        return extractor;
    }

    @Test
    public void testStreamingExtractionIndexesRootAndEmbed() throws Exception {
        Path path = get(requireNonNull(getClass().getResource(FIXTURE)).getPath());

        // Probe: extract once (no indexing) to learn the deterministic root + embed ids. Draining the
        // root reader drives the parse so getEmbeds() is populated and ids are computed.
        String rootId;
        String embedId;
        try (Extractor probe = streamingExtractor()) {
            TikaDocument doc = probe.extract(path);
            try (Reader r = doc.getReader()) { Spewer.toString(r); }
            rootId = doc.getId();
            assertThat(doc.getEmbeds()).isNotEmpty();
            embedId = doc.getEmbeds().get(0).getId();
        }

        // Stream-extract-and-index the same file: foreground writes the root, the spew worker writes
        // the embed. Same extractor config => same ids as the probe.
        try (Extractor extractor = streamingExtractor()) {
            extractor.extract(path, spewer);
        }

        // Both the root and the embedded child must be present in the index after streaming.
        Document root = indexer.get(es.getIndexName(), rootId);
        assertThat(root).isNotNull();
        assertThat(root.getId()).isEqualTo(rootId);

        Document child = indexer.get(es.getIndexName(), embedId, rootId);
        assertThat(child).isNotNull();
        assertThat(child.getId()).isNotEqualTo(rootId);
        assertThat(child.getRootDocument()).isEqualTo(rootId);
    }
}
