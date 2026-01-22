package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.apache.tika.Tika;
import org.icij.datashare.ExtensionService;
import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.indexing.Indexer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.commons.io.IOUtils.copy;
import static org.icij.datashare.PropertiesProvider.EXTENSIONS_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR_OPT;

@Singleton
@OpenAPIDefinition(info = @Info(title = "Datashare HTTP API", version = "v1"))
@Prefix("/")
public class RootResource {
    public static final String INDEX_HTML = "index.html";
    private final PropertiesProvider propertiesProvider;
    private final Indexer indexer;

    @Inject
    public RootResource(PropertiesProvider propertiesProvider, Indexer indexer) {
        this.propertiesProvider = propertiesProvider;
        this.indexer = indexer;
    }

    @Get
    public String getRoot(Context context) throws IOException {
        Path index = new File(context.env().workingDir(), context.env().appFolder()).toPath().resolve(INDEX_HTML);
        String content;
        if (context.env().classPath() && !index.toFile().isFile()) {
            StringWriter writer = new StringWriter();
            copy(getClass().getResourceAsStream("/" + context.env().appFolder() + "/" + INDEX_HTML), writer, Charset.defaultCharset());
            content = writer.toString();
        } else {
            content = new String(Files.readAllBytes(index), Charset.defaultCharset());
        }
        List<String> projectNames = context.currentUser() == null ? new LinkedList<>() : ((DatashareUser)context.currentUser()).getProjectNames();
        PluginService pluginService = createPluginService();
        if (pluginService != null) {
            return pluginService.addPlugins(content, projectNames);
        }
        return content;
    }

    @Operation(description = """
            Gets the public (i.e. without user's information) datashare settings parameters.
            
            These parameters are used for the client app for the init process.
            
            The endpoint is removing all fields that contain Address or Secret or Url or Key
            """)
    @ApiResponse(responseCode = "200", description = "returns the list of public settings", useReturnTypeSchema = true)
    @Get("settings")
    public Map<String, Object> getPublicSettings() {
        Map<String, Object> filteredProperties = propertiesProvider.getFilteredProperties(".*Address.*", ".*Secret.*", ".*Url.*", ".*Key.*");
        filteredProperties.put("pathSeparator", File.separator);
        return filteredProperties;
    }

    @Operation(description = "Gets the versions (front/back/docker) of datashare.")
    @ApiResponse(responseCode = "200", description = "returns the list of versions of datashare", useReturnTypeSchema = true)
    @Get("version")
    public Properties getVersions() throws IOException {
        Properties properties = new Properties();
        properties.putAll(getDatashareVersion());
        properties.putAll(getExtractorVersion());
        properties.putAll(getIndexVersion());
        return properties;
    }

    /**
     * Returns datashare version info from git.properties (build-time generated).
     */
    static Properties getDatashareVersion() throws IOException {
        Properties properties = new Properties();
        InputStream gitProperties = RootResource.class.getResourceAsStream("/git.properties");
        if (gitProperties != null) {
            properties.load(gitProperties);
        }
        return properties;
    }

    /**
     * Returns the document extractor (Tika) version.
     */
    static Properties getExtractorVersion() {
        Properties properties = new Properties();
        properties.put("ds.extractorVersion", Tika.getString());
        return properties;
    }

    /**
     * Returns the search index (Elasticsearch/OpenSearch) version and distribution.
     */
    private Properties getIndexVersion() {
        Properties properties = new Properties();
        try {
            Map<String, String> indexVersion = indexer.getVersion();
            properties.put("index.version", indexVersion.get("version"));
            properties.put("index.distribution", indexVersion.get("distribution"));
        } catch (Exception e) {
            properties.put("index.version", "unknown");
            properties.put("index.distribution", "unknown");
        }
        return properties;
    }

    private boolean hasPluginsDir() {
        String pluginsDir = propertiesProvider.get(PLUGINS_DIR_OPT).orElse(null);
        return pluginsDir != null && new File(pluginsDir).isDirectory();
    }

    private boolean hasExtensionsDir() {
        String pluginsDir = propertiesProvider.get(EXTENSIONS_DIR_OPT).orElse(null);
        return pluginsDir != null && new File(pluginsDir).isDirectory();
    }

    private ExtensionService createExtensionService() {
        if (this.hasExtensionsDir()) {
            return new ExtensionService(propertiesProvider);
        }
        return null;
    }

    private PluginService createPluginService() {
        if (this.hasPluginsDir()) {
            return new PluginService(propertiesProvider, createExtensionService());
        }
        return null;
    }
}
