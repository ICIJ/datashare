package org.icij.datashare.tasks.temporal;

import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;

public class IndexActivityImpl implements IndexActivity {
    private final Logger logger = LoggerFactory.getLogger(IndexActivityImpl.class);

    @Override
    public void index(IndexOptions indexOptions, List<Path> paths, String index) {
        // What to do here ?
        logger.atInfo()
              .log("Received {} to index", paths.stream().map(String::valueOf).reduce("", (tot, s) -> tot + s));

    }
}
