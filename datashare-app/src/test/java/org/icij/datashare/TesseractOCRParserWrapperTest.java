package org.icij.datashare;

import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

public class TesseractOCRParserWrapperTest {
    @Mock private TesseractOCRParser ocrParser;

    @Test
    public void test_create_ocr_parser_with_tesseract() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenReturn(true);
        TesseractOCRParserWrapper tesseractOCRParserWrapper = new TesseractOCRParserWrapper(ocrParser);
        verify(ocrParser).initialize(any());
    }

    @Test
    public void test_create_ocr_parser_without_tesseract() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenReturn(false);
        TesseractOCRParserWrapper tesseractOCRParserWrapper = new TesseractOCRParserWrapper(ocrParser);
        verify(ocrParser, never()).initialize(any());
    }

    @Test
    public void test_create_ocr_parser_throws_tika_exception() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenThrow(new TikaConfigException("Ocr parser initialization failed"));
        TesseractOCRParserWrapper tesseractOCRParserWrapper = new TesseractOCRParserWrapper(ocrParser);
        verify(ocrParser, never()).initialize(any());
    }

    @Test
    public void test_has_tesseract_wrapper_is_true() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenReturn(true);
        TesseractOCRParserWrapper tesseractOCRParserWrapper = new TesseractOCRParserWrapper(ocrParser);
        assertThat(tesseractOCRParserWrapper.hasTesseract()).isTrue();
    }

    @Test
    public void test_has_tesseract_wrapper_is_false() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenReturn(false);
        TesseractOCRParserWrapper tesseractOCRParserWrapper = new TesseractOCRParserWrapper(ocrParser);
        assertThat(tesseractOCRParserWrapper.hasTesseract()).isFalse();
    }

    @Test
    public void test_has_tesseract_wrapper_is_false_tika_config_exception() throws TikaConfigException {
        when(ocrParser.hasTesseract()).thenThrow(new TikaConfigException("Ocr parser initialization failed"));
        TesseractOCRParserWrapper tesseractOCRParserWrapper = new TesseractOCRParserWrapper(ocrParser);
        assertThat(tesseractOCRParserWrapper.hasTesseract()).isFalse();
    }

    @Before
    public void setUp() {
        openMocks(this);
    }
}