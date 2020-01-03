package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.queue.DocumentSet;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;

public class ScanIndexTask extends DefaultTask<Long> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final int scrollSize;
    private final String projectName;
    private final DocumentSet filterSet;
    private final User user;

    @Inject
    public ScanIndexTask(DocumentCollectionFactory factory, final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted User user) {
        this.user = user;
        this.scrollSize = parseInt(propertiesProvider.get("scrollSize").orElse("1000"));
        this.projectName = propertiesProvider.get("defaultProject").orElse("local-datashare");
        Optional<String> filterSet = propertiesProvider.get("filterSet");
        this.filterSet = filterSet.map(s -> factory.createSet(propertiesProvider, filterSet.get())).
                orElseThrow(() -> new IllegalArgumentException("no filterSet property defined"));
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        logger.info("scanning index {} with scroll size {}", projectName, scrollSize);
        Indexer.Searcher search = indexer.search(projectName, Document.class).withSource("path").limit(scrollSize);
        List<? extends Entity> docsToProcess;
        long nbProcessed = 0;
        do {
            docsToProcess = search.scroll().collect(toList());
            ((Collection<Path>) filterSet).addAll(docsToProcess.stream().map(d -> ((Document) d).getPath()).collect(toList()));
            nbProcessed += docsToProcess.size();
        } while (docsToProcess.size() != 0);
        logger.info("imported {} paths into {}", nbProcessed, filterSet);
        filterSet.close();
        return nbProcessed;
    }

    @Override
    public User getUser() {
        return user;
    }
}
