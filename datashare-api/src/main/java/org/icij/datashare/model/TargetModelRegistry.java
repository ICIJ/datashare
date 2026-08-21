package org.icij.datashare.model;

import org.icij.datashare.model.ftm.FtmTargetModel;

import java.util.Map;
import java.util.Objects;

public class TargetModelRegistry {
    private static final Map<String, TargetModel> MODELS = Map.of("ftm", new FtmTargetModel());

    public static TargetModel get(String name) {
        Objects.requireNonNull(name, "name");
        TargetModel model = MODELS.get(name);
        if (model == null) {
            throw new UnknownTargetModel(name, MODELS.keySet());
        }
        return model;
    }
}
