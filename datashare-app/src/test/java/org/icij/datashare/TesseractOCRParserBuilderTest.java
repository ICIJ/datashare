package org.icij.datashare;

import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class TesseractOCRParserBuilderTest {
    @Mock private TesseractOCRParser ocrParser;

    @Test
    public void test_create_ocr_parser_with_tesseract() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenReturn(true);
        TesseractOCRParserBuilder tesseractOCRParserBuilder = new TesseractOCRParserBuilder(ocrParser);
        verify(ocrParser).initialize(any());
    }

    @Test
    public void test_create_ocr_parser_without_tesseract() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenReturn(false);
        TesseractOCRParserBuilder tesseractOCRParserBuilder = new TesseractOCRParserBuilder(ocrParser);
        verify(ocrParser, never()).initialize(any());
    }

    @Test
    public void test_create_ocr_parser_throws_tika_exception() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenThrow(new TikaConfigException("Ocr parser initialization failed"));
        TesseractOCRParserBuilder tesseractOCRParserBuilder = new TesseractOCRParserBuilder(ocrParser);
        verify(ocrParser, never()).initialize(any());
    }

    @Before
    public void setUp() {
        initMocks(this);
    }
}