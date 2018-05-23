package org.icij.datashare.text.nlp.ixapipe;

import eus.ixa.ixa.pipe.ml.utils.Flags;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.NlpStage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.text.Language.*;

public class IxaNerModels extends IxaModels<eus.ixa.ixa.pipe.nerc.Annotate> {
    private static volatile IxaNerModels instance;
    private static final Object mutex = new Object();
    static final Map<Language, String> MODEL_NAMES = new HashMap<Language, String>() {{
        put(ENGLISH, "conll03/en-best-clusters-conll03.bin");
        put(SPANISH, "es-clusters-dictlbj-conll02.bin");
        put(GERMAN, "de-clusters-dictlbj-conll03.bin");
        put(DUTCH, "nl-clusters-dictlbj-conll02.bin");
        put(ITALIAN, "it-clusters-evalita09.bin");
        put(BASQUE, "eu-clusters-egunkaria.bin");
    }};

    public static IxaNerModels getInstance() {
        IxaNerModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new IxaNerModels();
                }
            }
        }
        return instance;
    }

    private IxaNerModels() { super(NlpStage.NER);}

    @Override
    protected IxaAnnotate<eus.ixa.ixa.pipe.nerc.Annotate> loadModelFile(Language language, ClassLoader loader) throws IOException {
        final Path path = getModelsBasePath(language).resolve(MODEL_NAMES.get(language));
        final URL resource = createResourceOrThrowIoEx(path, loader);

        Properties properties = IxaNerModels.nerAnnotatorProperties(language,
                resource.getPath(),
                Flags.DEFAULT_LEXER,
                Flags.DEFAULT_DICT_OPTION,
                Flags.DEFAULT_DICT_PATH,
                Flags.DEFAULT_FEATURE_FLAG);
        return new IxaAnnotate<>(new eus.ixa.ixa.pipe.nerc.Annotate(properties));
    }

    private static Properties nerAnnotatorProperties(Language language, String model,
                                                     String lexer,
                                                     String dictTag,
                                                     String dictPath,
                                                     String clearFeatures) {
        Properties annotateProperties = new Properties();
        annotateProperties.setProperty("model", model);
        annotateProperties.setProperty("language", language.iso6391Code());
        annotateProperties.setProperty("ruleBasedOption", lexer);
        annotateProperties.setProperty("dictTag", dictTag);
        annotateProperties.setProperty("dictPath", dictPath);
        annotateProperties.setProperty("clearFeatures", clearFeatures);
        return annotateProperties;
    }
}
