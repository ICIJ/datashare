package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import org.apache.commons.io.FilenameUtils;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DigestIdentifier;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.spewer.FieldNames;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.icij.datashare.text.Language.ENGLISH;

public class IndexerHelper {
    private final ElasticsearchClient client;
    private final ElasticsearchIndexer indexer;

    public IndexerHelper(ElasticsearchClient elasticsearch) {
        this.client = elasticsearch;
        this.indexer = new ElasticsearchIndexer(elasticsearch, new PropertiesProvider()).withRefresh(Refresh.True);
    }

    File indexFile(String fileName, String content, TemporaryFolder fs, String indexName) throws IOException {
        String[] pathItems = fileName.split("/");
        File folder = pathItems.length > 1 ? fs.newFolder(Arrays.copyOf(pathItems, pathItems.length - 1)) : fs.getRoot();
        File file = folder.toPath().resolve(pathItems[pathItems.length - 1]).toFile();
        file.createNewFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        String docname = FilenameUtils.removeExtension(FilenameUtils.getName(fileName));
        Document my_doc = DocumentBuilder.createDoc(docname).with(content).with(file.toPath()).build();
        indexer.add(indexName, my_doc);
        return file;
    }

    File indexEmbeddedFile(String project, String docPath) throws IOException {
        Path path = get(getClass().getResource(docPath).getPath());
        Extractor extractor = new Extractor(new DocumentFactory().withIdentifier(new DigestIdentifier("SHA-384", Charset.defaultCharset())));
        extractor.setDigester(new UpdatableDigester(project, Entity.DEFAULT_DIGESTER.toString()));
        TikaDocument document = extractor.extract(path);
        ElasticsearchSpewer elasticsearchSpewer = new ElasticsearchSpewer(new ElasticsearchIndexer(client,
                new PropertiesProvider()).withRefresh(Refresh.True), new MemoryDocumentCollectionFactory<>(), l -> ENGLISH,
                new FieldNames(), new PropertiesProvider(new HashMap<>() {{
                    put("defaultProject","test-datashare");
        }}));
        elasticsearchSpewer.write(document);
        return path.toFile();
    }
}