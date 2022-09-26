package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.BiFunction;

import static java.lang.Integer.parseInt;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.payload.Payload.notFound;
import static net.codestory.http.payload.Payload.forbidden;
import static net.codestory.http.payload.Payload.badRequest;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

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
     * specification than the `tree` command on UNIX. It is roughly the equivalent of:
     *
     * ```
     * tree -L 1 -spJ --noreport /home/datashare/data
     * ```
     *
     * @param dirPath
     * @return 200 and the list of files and directory
     *
     * Example $(curl -XGET localhost:8080/api/tree/home/datashare/data)
     */
    @Get(":dirPath:")
    public FileReport getTree(final String dirPath, Context context) throws IOException {
        Path path = IS_OS_WINDOWS ?  Paths.get(dirPath) : Paths.get(File.separator, dirPath);
        int depth = parseInt(ofNullable(context.get("depth")).orElse("0"));
        File dir = path.toFile();
        if (!dir.exists()) { throw new NotFoundException(); }
        if (!dir.isDirectory()) { throw new BadRequestException();}
        if (!isAllowed(dir)) { throw new ForbiddenException();}
        return tree(path, depth);
    }

    private FileReport tree(Path dir, int depth) throws IOException {
        FileReport rootReport = new FileReport(dir.toFile());
        Files.walkFileTree(dir, new FileReport.FileReportVisitor(rootReport, depth));
        return rootReport;
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
