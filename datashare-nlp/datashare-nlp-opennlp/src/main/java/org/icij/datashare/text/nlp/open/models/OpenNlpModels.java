package org.icij.datashare.text.nlp.open.models;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

import org.icij.datashare.text.nlp.NlpStage;


/**
 * Created by julien on 8/11/16.
 */
final class OpenNlpModels {

    // Base directory for OpenNLP models
    private static final Path BASE_DIR = Paths.get( OpenNlpModels.class.getPackage().getName().replace(".", "/"));

    // Model directory
    static final Function<NlpStage, Path> DIRECTORY = stage -> BASE_DIR.resolve(stage.toString().toLowerCase());

}
