package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import java.nio.file.Path;
import java.util.List;

@ActivityInterface
public interface IndexActivity {
    @ActivityMethod(name = "index")
    void index(IndexOptions indexOptions, List<Path> paths, String index);
}
