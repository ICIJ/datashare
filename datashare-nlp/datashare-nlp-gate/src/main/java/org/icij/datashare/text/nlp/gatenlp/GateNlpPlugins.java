package org.icij.datashare.text.nlp.gatenlp;

import gate.Gate;
import gate.creole.ANNIEConstants;
import gate.creole.ResourceInstantiationException;
import gate.persist.PersistenceException;
import gate.util.persistence.PersistenceManager;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.icij.datashare.function.Functions.capitalize;
import static org.icij.datashare.text.Language.ENGLISH;

public class GateNlpPlugins extends AbstractModels {
    private static volatile GateNlpPlugins instance;
    private static final Object mutex = new Object();
    private static final String VERSION = "8.4.1";
    private static final Path gatenlp = BASE_CLASSPATH.resolve("gatenlp").
                    resolve(VERSION.replace('.', '-'));

    private GateNlpPlugins() {
        super(Pipeline.Type.GATENLP, null);
    }

    public static GateNlpPlugins getInstance() {
        GateNlpPlugins local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new GateNlpPlugins();
                }
            }
        }
        return instance;
    }

    @Override
    protected Path getModelsBasePath(Language language) {
        if (language == ENGLISH) {
            return gatenlp.resolve("ANNIE");
        }
        return gatenlp.resolve(getLanguagePluginName(language));
    }

    @Override
    protected Object loadModelFile(Language language, ClassLoader loader) throws IOException {
        try {
            if (language == ENGLISH) {
                return PersistenceManager.loadObjectFromFile(new File(new File(Gate.getPluginsHome(),
                        ANNIEConstants.PLUGIN_DIR), ANNIEConstants.DEFAULT_FILE));
            } else {
                return PersistenceManager.loadObjectFromFile(new File(new File(Gate.getPluginsHome(),
                        getLanguagePluginName(language)), language.name().toLowerCase() + ".gapp"));
            }
        } catch (PersistenceException | ResourceInstantiationException e) {
            throw new IllegalStateException("cannot load ANNIE plugin", e);
        }
    }

    private String getLanguagePluginName(Language language) {
        return "Lang_" + capitalize.apply(language.name());
    }

    @Override
    protected String getVersion() {
        return VERSION;
    }
}
