package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static net.codestory.http.payload.Payload.notFound;
import static net.codestory.http.payload.Payload.forbidden;
import static net.codestory.http.payload.Payload.badRequest;

@Singleton
@Prefix("/api/tree")
public class TreeResource {

    private final PropertiesProvider propertiesProvider;

    @Inject
    public TreeResource(final PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }


    /**
     * List all files and directory for the given path. This endpoint returns a JSON using the same
     * specification than the `tree` command on UNIX. It is the equivalent of:
     *
     * ```
     * tree -L 1 -spJ /home/datashare/data
     * ```
     *
     * @param dirPath
     * @return 200 and the list of files and directory
     *
     * Example $(curl -XGET localhost:8080/api/tree/home/datashare/data)
     */
    @Get(":dirPath:")
    public Payload getTree(final String dirPath) throws IOException {
        File dir = new File('/' + dirPath);
        if (!dir.exists()) { return notFound(); }
        if (!dir.isDirectory()) { return badRequest(); }
        if (!isAllowed(dir)) { return forbidden(); }
        return new Payload(tree(dir)).withCode(200);
    }

    protected List<Map> tree(File dir) {
        Map tree = walkToDirTree(dir, 1);
        Map report = reportDirTree(tree);
        return asList(tree, report);
    }

    protected Map walkToDirTree(File file, Integer depth) {
        Map<String, Object> descriptor = new HashMap<>();
        descriptor.put("name", file.toString());
        descriptor.put("type", fileType(file));
        descriptor.put("prot", fileProtWithD(file));
        // Only file have size
        if (file.isFile()) {
            descriptor.put("size", file.length());
        // Only directory have children
        } else {
            if (depth >= 1) {
                File[] childrenFiles = file.listFiles(new FilenameFilter() {
                  @Override
                  public boolean accept(File current, String name) {
                      File dirOrFile = new File(current, name);
                      return dirOrFile.isDirectory() || dirOrFile.isFile();
                  }
                });
                List children = asList(childrenFiles)
                        .stream()
                        .sorted()
                        .map(child -> walkToDirTree(child, depth - 1))
                        .collect(Collectors.toList());
                descriptor.put("children", children);
            } else {
                descriptor.put("children", new ArrayList());
            }
        }
        return descriptor;
    }

    protected Map reportDirTree(Map tree) {
        Map<String, Object> report = new HashMap<>();
        report.put("directories", 0);
        report.put("files", 0);
        report.put("type", "report");
        // Small `sum` lambda to do the sum of two "Object" values from the hashmap
        BiFunction<Object, Object, Integer> sum = (a, b) -> (Integer) a + (Integer) b;

        for(Map child: (List<Map>) tree.get("children")) {
            String name = (String) child.get("name");
            File asFile = Paths.get(name).toFile();
            if (asFile.isDirectory()) {
                report.merge("directories", 1 + (Integer) reportDirTree(child).get("directories"),  sum);
                report.merge("files", (Integer) reportDirTree(child).get("files"),  sum);
            } else {
                report.merge("files", 1,  sum);
            }
        }
        return report;
    }

    protected String fileType (File file) {
        if (file.isDirectory()) {
            return "directory";
        }
        return "file";
    }

    protected String fileProt (File file) {
        Set<PosixFilePermission> perms = null;
        try {
            perms = Files.readAttributes(file.toPath(), PosixFileAttributes.class).permissions();
            return PosixFilePermissions.toString(perms);
        } catch (IOException e) {
            return null;
        }
    }

    protected String fileProtWithD (File file) {
        String prot = fileProt(file);
        return file.isFile() ? '-' + prot : 'd' + prot;
    }

    protected boolean isAllowed (File file) throws IOException {
        String dataDirCanonical = dataDirPath().toFile().getCanonicalPath();
        String dirCanonical = file.getCanonicalPath();
        return dirCanonical.startsWith(dataDirCanonical);
    }

    protected String dataDir () {
        return propertiesProvider.get("dataDir").orElse("/home/datashare/data");
    }

    protected Path dataDirPath () {
        return Paths.get(this.dataDir());
    }
}
