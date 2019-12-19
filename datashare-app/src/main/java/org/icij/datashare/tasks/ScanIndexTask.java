package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentSet;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;

public class ScanIndexTask extends PipelineTask {
    private final Indexer indexer;
    private final int scrollSize;
    private final String projectName;
    private final Set<Path> filterSet;

    @Inject
    public ScanIndexTask(final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted User user) {
        super(DatashareCli.Stage.SCANIDX, user, propertiesProvider);
        this.scrollSize = parseInt(propertiesProvider.get("scrollSize").orElse("1000"));
        this.projectName = propertiesProvider.get("defaultProject").orElse("local-datashare");
        Optional<String> filterSet = propertiesProvider.get("filterSet");
        this.filterSet = filterSet.<Set<Path>>map(s -> new RedisDocumentSet(filterSet.get(),
                propertiesProvider.get("redisAddress").orElse("redis://redis:6379"))).orElse(null);
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        return filterSet == null ? scanIndex(queue) : scanIndex(filterSet);
    }

    @NotNull
    private Long scanIndex(Collection<Path> collection) throws IOException {
        Indexer.Searcher search = indexer.search(projectName, Document.class).withSource("path").limit(scrollSize);
        List<? extends Entity> docsToProcess;
        long nbProcessed = 0;
        do {
            docsToProcess = search.scroll().collect(toList());
            collection.addAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toList()));
            nbProcessed += docsToProcess.size();
        } while (docsToProcess.size() != 0);
        return nbProcessed;
    }
}
