package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.NotFoundException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.file.DirectoryReport;
import org.icij.datashare.file.FileReportVisitor;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

@Singleton
@Prefix("/api/tree")
public class TreeResource {

    private final PropertiesProvider propertiesProvider;

    @Inject
    public TreeResource(final PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @Operation(description = """
            Lists all files and directory for the given path. This endpoint returns a JSON using the same specification than the `tree` command on UNIX. It is roughly the equivalent of:
            ```
            tree -L 1 -spJ --noreport /home/datashare/data
            ```
            """)
    @ApiResponse(responseCode = "200", description = "returns the list of files and directory", useReturnTypeSchema = true)
    @Get(":dirPath:")
    public DirectoryReport getTree(@Parameter(name="dirPath", description="directory path in the tree", in = ParameterIn.PATH) final String dirPath, Context context) throws IOException {
        Path path = IS_OS_WINDOWS ?  Paths.get(dirPath) : Paths.get(File.separator, dirPath);
        int depth = parseInt(ofNullable(context.get("depth")).orElse("0"));
        File dir = path.toFile();
        if (!dir.exists()) { throw new NotFoundException(); }
        if (!dir.isDirectory()) { throw new BadRequestException();}
        if (!isAllowed(dir)) { throw new ForbiddenException();}
        return tree(path, depth);
    }

    private DirectoryReport tree(Path dir, int depth) throws IOException {
        DirectoryReport rootReport = new DirectoryReport(dir.toFile());
        FileReportVisitor visitor = new FileReportVisitor(rootReport, depth);
        Set<FileVisitOption> options = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        Files.walkFileTree(dir, options, Integer.MAX_VALUE, visitor);
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
