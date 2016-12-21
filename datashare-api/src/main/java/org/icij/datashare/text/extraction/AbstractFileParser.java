package org.icij.datashare.text.extraction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.icij.datashare.text.Language;

import java.util.Properties;

import static org.icij.datashare.text.Language.NONE;
import static org.icij.datashare.util.function.Functions.parseLanguage;
import static org.icij.datashare.util.function.ThrowingFunctions.getProperty;
import static org.icij.datashare.util.function.ThrowingFunctions.parseBoolean;

/**
 * Common structure and behavior of FileParser implementations
 *
 * Created by julien on 10/11/16.
 */
public abstract class AbstractFileParser implements FileParser {

    protected final Logger LOGGER = LogManager.getLogger(getClass());


    protected boolean ocrEnabled;

    protected Language language;


    public AbstractFileParser(Properties properties) {
        ocrEnabled = getProperty(Property.ENABLE_OCR.getName(),   properties, parseBoolean).orElse(false);
        language   = getProperty(Property.OCR_LANGUAGE.getName(), properties, parseLanguage).orElse(NONE);
    }


    @Override
    public Type getType() { return Type.fromClassName(getClass().getSimpleName()).get(); }

}

