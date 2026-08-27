package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmTargetModel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class TargetModelRegistry {
    private static final Map<String, Supplier<TargetModel>> FACTORIES = Map.of("ftm", FtmTargetModel::new);
    private static final Map<String, TargetModel> PARSED = new ConcurrentHashMap<>();

    private TargetModelRegistry() {
    }

    /** Reads a model's ontology on first use rather than in a static initializer, so a bundle that
     *  cannot be read reaches the caller as {@link UnreadableModelResource} on every call, instead of
     *  an ExceptionInInitializerError once and a causeless NoClassDefFoundError from then on. */
    public static TargetModel get(String name) {
        Objects.requireNonNull(name, "name");
        Supplier<TargetModel> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new UnknownTargetModel(name, FACTORIES.keySet());
        }
        return PARSED.computeIfAbsent(name, ignored -> factory.get());
    }
}
