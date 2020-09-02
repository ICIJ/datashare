package org.icij.datashare;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

public interface Deliverable extends Entity {
    URL getUrl();
    String getUrlFileName();
    File download() throws IOException;
    void install(File deliverable, Path targetDir) throws IOException;
    void install(Path targetDir) throws IOException; // install from local : url is the filesystem file
    boolean isInstalled(Path targetDir) throws IOException;
    void delete(Path installDir) throws IOException;
}
