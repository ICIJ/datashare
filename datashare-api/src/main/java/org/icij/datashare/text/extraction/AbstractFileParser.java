package org.icij.datashare.text.extraction;

import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.function.Functions.parseLanguage;
import static org.icij.datashare.function.ThrowingFunctions.parseBoolean;
import static org.icij.datashare.function.ThrowingFunctions.getProperty;


/**
 * Common structure and behavior of FileParser implementations
 *
 * Created by julien on 10/11/16.
 */
public abstract class AbstractFileParser implements FileParser {

    private static final long serialVersionUID = 7548697822136485L;

    protected final Logger LOGGER = LogManager.getLogger(getClass());

    protected boolean ocrEnabled;

    protected Language language;


    public AbstractFileParser(Properties properties) {
        ocrEnabled = getProperty(Property.ENABLE_OCR.getName(),   properties, parseBoolean) .orElse(false);
        language   = getProperty(Property.OCR_LANGUAGE.getName(), properties, parseLanguage).orElse(ENGLISH);
    }


    @Override
    public Type getType() { return Type.fromClassName(getClass().getSimpleName()).get(); }

}

