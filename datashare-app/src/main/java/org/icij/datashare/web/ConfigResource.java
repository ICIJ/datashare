package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Patch;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Mode;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static net.codestory.http.payload.Payload.ok;

@Prefix("/api/config")
public class ConfigResource {
    Logger logger = LoggerFactory.getLogger(getClass());
    private PropertiesProvider provider;

    @Inject
    public ConfigResource(PropertiesProvider provider) {
        this.provider = provider;
    }

    /**
     * gets the private datashare configuration with user's information
     *
     * @return 200 and the json config
     *
     * Example :
     * $(curl -i localhost:8080/api/config)
     */
    @Get()
    public Map<String, Object> getConfig(Context context) {
        Map<String, Object> filteredProperties = provider.getFilteredProperties(".*Address.*", ".*Secret.*");
        HashMapUser user = (HashMapUser) context.currentUser();
        List<String> projects = user.getProjects();
        if (!provider.get("mode").orElse(Mode.LOCAL.toString()).equals(Mode.SERVER.toString())) {
            projects.add(0, user.defaultProject());
        }
        filteredProperties.put("userProjects", projects);
        return filteredProperties;
    }

    /**
     * Preflight for config.
     *
     * @param context
     * @return 200 with PATCH
     */
    @Options()
    public Payload patchConfigPreflight(final Context context) {
        return ok().withAllowMethods("OPTION", "PATCH");
    }

    /**
     * update the datashare configuration with provided body. It will save the configuration on disk.
     *
     * Returns 404 if configuration is not found. It means that the configuration file has not been set (or is not readable)
     *
     * The configuration priority is basically -c file > classpath:datashare.properties > command line. I.e. :
     *
     * - if a file is given (w/ -c path/to/file) to the command line it will be read and used (it can be empty or not present)
     * - if no file is given, we are looking for datashare.properties in the classpath (for example in /dist)
     * - if none of the two above cases is fulfilled we are taking the default CLI parameters (and those given by the user)
     * - if there are common parameters in CLI and a config file, the config file "wins"
     * - if a config file is not writable then 404 will be returned (and a WARN will be logged at start)
     *
     * @return 200 or 404
     *
     * Example :
     * $(curl -i -XPATCH -H 'Content-Type: application/json' localhost:8080/api/config -d '{"data":{"foo":"bar"}}')
     */
    @Patch()
    public Payload patchConfig(Context context, JsonData data) throws IOException {
        logger.info("user {} is updating the configuration", context.currentUser().login());
        try {
            provider.overrideWith(data.asProperties()).save();
        } catch (PropertiesProvider.ConfigurationNotFound e) {
            return Payload.notFound();
        }
        return Payload.ok();
    }
}
