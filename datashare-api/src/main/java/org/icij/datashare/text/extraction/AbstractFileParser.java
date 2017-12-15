package org.icij.datashare.text.extraction;

import org.icij.datashare.text.Language;

import java.util.Properties;

import static org.icij.datashare.function.Functions.parseLanguage;
import static org.icij.datashare.function.ThrowingFunctions.getProperty;
import static org.icij.datashare.function.ThrowingFunctions.parseBoolean;
import static org.icij.datashare.text.Language.ENGLISH;


/**
 * Common structure and behavior of FileParser implementations
 *
 * Created by julien on 10/11/16.
 */
public abstract class AbstractFileParser implements FileParser {

    private static final long serialVersionUID = 7548697822136485L;

    protected boolean ocrEnabled;

    protected Language language;


    public AbstractFileParser(Properties properties) {
        ocrEnabled = getProperty(Property.ENABLE_OCR.getName(),   properties, parseBoolean) .orElse(false);
        language   = getProperty(Property.OCR_LANGUAGE.getName(), properties, parseLanguage).orElse(ENGLISH);
    }


    @Override
    public Type getType() { return Type.fromClassName(getClass().getSimpleName()).get(); }

}

