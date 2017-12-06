package org.icij.datashare.text.nlp.open;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.NlpStage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.*;

import static java.util.stream.Collectors.toList;

public class OpenNlpModel {
    private static final Logger LOGGER = LogManager.getLogger(OpenNlpModel.class);
    public static final String CLASSPATH_ROOT_DIR = "/opennlp";
    public final Locale locale;

    public final List<OpenNlpComponent> components;

    public OpenNlpModel(final Locale locale) {
        this.locale = locale;
        this.components = loadComponents();
    }

    public boolean isLoaded() { return components.stream().allMatch(OpenNlpComponent::isLoaded); }

    List<OpenNlpComponent> loadComponents() {
        List<OpenNlpComponent> components = new LinkedList<>();
        try {
            components = getResourceFiles(String.valueOf(Paths.get(CLASSPATH_ROOT_DIR, locale.getLanguage()))).
                    stream().map(OpenNlpModel::modelFactory).collect(toList());
        } catch (IOException e) {
            LOGGER.error("error while loading components from " + CLASSPATH_ROOT_DIR, e);
        }
        return components;
    }

    static OpenNlpComponent modelFactory(final String filename) {
        String name = filename.substring(0, filename.indexOf("."));
        String[] split =name.split("-");

        Optional<NlpStage> nlpStage = NlpStage.parse(split[1]);

        if (nlpStage.isPresent() && nlpStage.get() == NlpStage.NER) {
            Optional<NamedEntity.Category> category = NamedEntity.Category.parse(split[2]);
            return new OpenNlpNerComponent(category.get());
        } else {
            return new OpenNlpComponent(nlpStage.get());
        }
    }

    private List<String> getResourceFiles(String classpath) throws IOException {
        List<String> filenames = new ArrayList<>();
        try (
                InputStream in = getResourceAsStream(classpath);
                BufferedReader br = new BufferedReader(new InputStreamReader(in))
        ) {
            String resource;

            while ((resource = br.readLine()) != null) {
                filenames.add(resource);
            }
        }
        return filenames;
    }

    private InputStream getResourceAsStream(String resource) {
        final InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        return in == null ? getClass().getResourceAsStream(resource) : in;
    }

}
