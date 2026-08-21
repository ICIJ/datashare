package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmTargetModel;

import java.util.Map;
import java.util.TreeSet;

public class TargetModelRegistry {
    private static final Map<String, TargetModel> MODELS = Map.of("ftm", new FtmTargetModel());

    public static TargetModel get(String name) {
        TargetModel model = MODELS.get(name);
        if (model == null) {
            throw new IllegalArgumentException("unknown data model '" + name
                    + "', known models: " + new TreeSet<>(MODELS.keySet()));
        }
        return model;
    }
}
