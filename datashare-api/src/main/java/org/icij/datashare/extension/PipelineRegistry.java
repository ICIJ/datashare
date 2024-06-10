package org.icij.datashare.extension;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class PipelineRegistry {
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    private final PropertiesProvider propertiesProvider;
    private final Map<Pipeline.Type, Pipeline> pipelines = new HashMap<>();

    public PipelineRegistry(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    public Pipeline get(Pipeline.Type type) {
        return pipelines.get(type);
    }

    public Set<Pipeline.Type> getPipelineTypes() {
        return pipelines.keySet();
    }

    public void register(Class<? extends Pipeline> pipelineClass) {
        try {
            Pipeline abstractPipeline = pipelineClass.getDeclaredConstructor(PropertiesProvider.class).newInstance(propertiesProvider);
            pipelines.put(abstractPipeline.getType(), abstractPipeline);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public void register(Pipeline.Type type) {
        try {
            register((Class<? extends Pipeline>) Class.forName(type.getClassName()));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void load(ExtensionLoader loader) throws FileNotFoundException {
        loader.load((Consumer<Class<? extends Pipeline>>) this::register, Pipeline.class::isAssignableFrom);
    }
}

