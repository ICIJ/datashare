package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.netflix.conductor.client.http.ConductorClient;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.TaskWorkerApp;

public class TaskManagerConductor extends org.icij.datashare.asynctasks.TaskManagerConductor {
    @Inject
    public TaskManagerConductor(ConductorClient client, PropertiesProvider propertiesProvider)
        throws IOException, URISyntaxException {
        super(client, Utils.getRoutingStrategy(propertiesProvider), globResources("conductor/tasks", "*.json"), globResources("conductor/workflows", "*.json"));
    }

    public static List<Path> globResources(String rootPath, String globPattern)
        throws IOException, URISyntaxException {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
        URI resourceUri = TaskWorkerApp.class.getClassLoader().getResource(rootPath).toURI();
        Path resourcePath;
        if (resourceUri.getScheme().equals("jar")) {
            try (FileSystem fileSystem = FileSystems.newFileSystem(resourceUri, Collections.emptyMap())) {
                resourcePath = fileSystem.getPath(rootPath);
                try (Stream<Path> paths = Files.walk(resourcePath, 1)) {
                    return paths.filter(path -> matcher.matches(path.getFileName())).toList();
                }
            }
        }
        try (Stream<Path> paths = Files.walk(Paths.get(resourceUri))) {
            return paths.filter(path -> matcher.matches(path.getFileName())).toList();
        }
    }
}
