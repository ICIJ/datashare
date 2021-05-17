package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Patch;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/settings")
public class SettingsResource {
    Logger logger = LoggerFactory.getLogger(getClass());
    private PropertiesProvider provider;

    @Inject
    public SettingsResource(PropertiesProvider provider) {
        this.provider = provider;
    }

    /**
     * Preflight for settings.
     *
     * @param context
     * @return 200 with PATCH
     */
    @Options()
    public Payload patchSettingsPreflight(final Context context) {
        return ok().withAllowMethods("OPTION", "PATCH").withAllowHeaders("content-type");
    }

    /**
     * update the datashare settings with provided body. It will save the settings on disk.
     *
     * Returns 404 if settings is not found. It means that the settings file has not been set (or is not readable)
     * Returns 403 if we are in SERVER mode
     *
     * The settings priority is basically
     * DS_DOCKER_* variables > -s file > classpath:datashare.properties > command line. I.e. :
     *
     * - DS_DOCKER_* variables will be taken and override all keys (if any similar keys exist)
     * - if a file is given (w/ -c path/to/file) to the command line it will be read and used (it can be empty or not present)
     * - if no file is given, we are looking for datashare.properties in the classpath (for example in /dist)
     * - if none of the two above cases is fulfilled we are taking the default CLI parameters (and those given by the user)
     * - parameters are common between CLI and settings file, the settings file "wins"
     * - if a settings file is not writable then 404 will be returned (and a WARN will be logged at start)
     *
     * @return 200 or 404 or 403
     *
     * Example :
     * $(curl -i -XPATCH -H 'Content-Type: application/json' localhost:8080/api/settings -d '{"data":{"foo":"bar"}}')
     */
    @Patch()
    public Payload patchSettings(Context context, JsonData data) throws IOException {
        if (provider.get("mode").orElse(Mode.LOCAL.name()).equals(Mode.SERVER.name())) {
            return Payload.forbidden();
        }
        logger.info("user {} is updating the settings", context.currentUser().login());
        try {
            provider.overrideWith(data.asProperties()).save();
        } catch (PropertiesProvider.SettingsNotFound e) {
            return Payload.notFound();
        }
        return Payload.ok();
    }
}
