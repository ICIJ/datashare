package org.icij.datashare;

import com.google.inject.Singleton;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

@Singleton
public class TesseractOCRParserWrapper {
    private final static Logger logger = LoggerFactory.getLogger(TesseractOCRParserWrapper.class);
    private final TesseractOCRParser ocrParser;

    public TesseractOCRParserWrapper() {
        this.ocrParser = initializeParser(new TesseractOCRParser());
    }

    TesseractOCRParserWrapper(TesseractOCRParser ocrParser) {
        this.ocrParser = initializeParser(ocrParser);
    }

    public TesseractOCRParser getOcrParser() {
        return ocrParser;
    }

    public boolean hasTesseract() {
        try {
            return ocrParser.hasTesseract();
        } catch (TikaConfigException e) {
            logger.warn("Ocr parser failed to check if tesseract is installed" + e);
        }
        return false;
    }

    public static TesseractOCRParser initializeParser(TesseractOCRParser ocrParser) {
        ocrParser.setPreloadLangs(true);
        try {
            if (ocrParser.hasTesseract()) {
                ocrParser.initialize(new HashMap<>());
                return ocrParser;
            }
        } catch (TikaConfigException e) {
            logger.warn("Ocr parser initialization failed" + e);
        }
        return ocrParser;
    }
}
