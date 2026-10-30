package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.icij.datashare.PropertiesProvider;
import java.nio.file.Path;
import java.util.List;
import org.icij.datashare.tasks.ArtifactOptions;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;

@ActivityInterface
public interface IndexActivity {
    @ActivityMethod(name = "index")
    void index(IndexOptions indexOptions, ArtifactOptions artifactOptions, List<Path> paths, String index);
}
