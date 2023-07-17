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
import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Language;

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
import java.util.stream.Stream;

import static org.apache.commons.io.IOUtils.copy;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR;

@Singleton
@Prefix("/")
public class RootResource {
    public static final String INDEX_HTML = "index.html";
    private final PropertiesProvider propertiesProvider;

    @Inject
    public RootResource(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @Operation(description = "Gets the root of the front-end app ie: ./app/index.html<br>" +
            "If pluginsDir is set, it will add in the index the tag <script src=\"plugins/my_plugin/index.js\"></script> else it will return the index.html content as is")
    @ApiResponse(responseCode = "200", description = "returns the content of index.html file", useReturnTypeSchema = true)
    @Get()
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
        List<String> projects = context.currentUser() == null ? new LinkedList<String>() : ((DatashareUser)context.currentUser()).getProjectNames();
        return propertiesProvider.get(PLUGINS_DIR).isPresent() ?
                new PluginService(propertiesProvider).addPlugins(content, projects):
                content;
    }

    @Operation(description = "Gets the public (i.e. without user's information) datashare settings parameters.<br>" +
            "These parameters are used for the client app for the init process.<br>" +
            "The endpoint is removing all fields that contain Address or Secret or Url or Key")
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
    public Properties getVersion() {
        try {
            Properties properties = new Properties();
            InputStream gitProperties = getClass().getResourceAsStream("/git.properties");
            if (gitProperties != null) {
                properties.load(gitProperties);
            }
            return properties;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
