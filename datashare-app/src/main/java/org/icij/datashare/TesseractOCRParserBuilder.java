package org.icij.datashare;

import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

public class TesseractOCRParserBuilder {
    private TesseractOCRParser ocrParser;

    public TesseractOCRParserBuilder() {
        this.ocrParser = initializeParser(new TesseractOCRParser());
    }

    TesseractOCRParserBuilder(TesseractOCRParser ocrParser) {
        this.ocrParser = initializeParser(ocrParser);
    }

    public TesseractOCRParser getOcrParser() {
        return ocrParser;
    }

    public static TesseractOCRParser initializeParser(TesseractOCRParser ocrParser) {
        ocrParser.setPreloadLangs(true);
        try {
            if (ocrParser.hasTesseract()) {
                ocrParser.initialize(new HashMap<>());
                return ocrParser;
            }
        } catch (TikaConfigException e) {
            LoggerFactory.getLogger(TesseractOCRParserBuilder.class).info("Ocr parser initialization failed" + e);
        }
        return ocrParser;
    }
}
