package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.icij.datashare.PropertiesProvider;
import java.nio.file.Path;
import java.util.List;

@ActivityInterface
public interface IndexActivity {
    @ActivityMethod(name = "index")
    void index(PropertiesProvider propertiesProvider, List<Path> paths, String index);
}
