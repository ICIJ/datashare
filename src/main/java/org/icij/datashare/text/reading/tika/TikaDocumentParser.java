package org.icij.datashare.text.reading.tika;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.language.LanguageIdentifier;
import org.apache.tika.language.ProfilingHandler;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;

import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.LinkContentHandler;
import org.apache.tika.sax.TeeContentHandler;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.reading.DocumentParser;
import org.icij.datashare.text.reading.DocumentParserException;
import org.xml.sax.SAXException;

import static java.util.logging.Level.SEVERE;
import static org.icij.datashare.text.Language.*;


/**
 * Created by julien on 3/9/16.
 */
public final class TikaDocumentParser implements DocumentParser {

    private static final Logger LOGGER = Logger.getLogger(TikaDocumentParser.class.getName());


    public static final String RESOURCE_NAME = Metadata.RESOURCE_NAME_KEY;

    public static final String CONTENT_TYPE = Metadata.CONTENT_TYPE;

    public static final String CONTENT_LENGTH = Metadata.CONTENT_LENGTH;

    public static final String CONTENT_LANGUAGE = Metadata.CONTENT_LANGUAGE;

    public static final String CONTENT_LANGUAGE_BELIEF = "Content-Language-Belief";

    public static final Function<Language, Language> LANGUAGE_MAP = (lang) -> {
        if (lang.equals(FRENCH) || lang.equals(SPANISH) || lang.equals(GERMAN))
            return lang;
        if (lang.equals(GALICIAN))
            return SPANISH;
        return ENGLISH;
    };


    private final TikaConfig config = TikaConfig.getDefaultConfig();

    private final PDFParserConfig pdfConfig = new PDFParserConfig();

    private final TesseractOCRConfig ocrConfig = new TesseractOCRConfig();


    private final Set<MediaType> excludedTypes = new HashSet<>();

    private final AutoDetectParser parser;


    private boolean ocrDisabled = false;

    private ParseContext context;

    private TeeContentHandler handler;

    private Metadata metadata;


    public TikaDocumentParser() {
        pdfConfig.setExtractInlineImages(true);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);
        pdfConfig.setUseNonSequentialParser(true);
        parser  = new AutoDetectParser(config);
        context = new ParseContext();
        if ( ! ocrDisabled)
            context.set(TesseractOCRConfig.class, ocrConfig);
        context.set(PDFParserConfig.class, pdfConfig);
        parser.setFallback(new ErrorParser(parser, excludedTypes));
        context.set(Parser.class, parser);
    }

    public void setOcrLanguage(final String ocrLanguage) {
        ocrConfig.setLanguage(ocrLanguage);
    }

    @Override
    public void disableOcr() {
        if ( ! ocrDisabled) {
            excludeParser(TesseractOCRParser.class);
            ocrDisabled = true;
            pdfConfig.setExtractInlineImages(false);
        }
    }


    @Override
    public Optional<Language> getLanguage() {
        if (metadata == null)
            return Optional.empty();
        return Language.parse(metadata.get(CONTENT_LANGUAGE));
    }

    @Override
    public OptionalInt getLength() {
        if (metadata == null || ! Arrays.asList(metadata.names()).contains(CONTENT_LENGTH))
            return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(metadata.get(CONTENT_LENGTH)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    @Override
    public Optional<Charset> getEncoding() {
        if (metadata == null || ! Arrays.asList(metadata.names()).contains(Metadata.CONTENT_ENCODING))
            return Optional.empty();
        return Optional.of(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<String> getName() {
        if (metadata == null || ! Arrays.asList(metadata.names()).contains(RESOURCE_NAME))
            return Optional.empty();
        return Optional.of(metadata.get(RESOURCE_NAME));
    }

    @Override
    public Optional<String> getType() {
        if (metadata == null)
            return Optional.empty();
        return Optional.of(metadata.get(CONTENT_TYPE));
    }

    @Override
    public Optional<Map<String, String>> getMetadata() {
        if (metadata == null)
            return Optional.empty();
        Map<String, String> map = new HashMap<>();
        for(String key : metadata.names()) {
            map.put(key, metadata.get(key));
        }
        return Optional.of(map);
    }

    @Override
    public Optional<String> parse(Path filePath) {
        Metadata metadata = new Metadata();
        try (TikaInputStream input = TikaInputStream.get(filePath, metadata)) {
            return Optional.of(parse(input, metadata));
        } catch (DocumentParserException | IOException e) {
            //throw new DocumentParserException(e.getMessage(), e.getCause());
            LOGGER.log(SEVERE, "Failed to parse " + filePath, e);
            return Optional.empty();
        }
    }

    private String parse(final InputStream is, final Metadata md) throws DocumentParserException {
        BodyContentHandler textHandler = new BodyContentHandler(-1);
        LinkContentHandler linkHandler = new LinkContentHandler();
        ProfilingHandler   profiler    = new ProfilingHandler();
        handler = new TeeContentHandler(textHandler, linkHandler, profiler);
        metadata = md;

        try {
            parser.parse(is, handler, metadata, context);
        } catch (IOException | SAXException | TikaException e) {
            throw new DocumentParserException(e.getMessage(), e.getCause());
        }

        LanguageIdentifier identifier = profiler.getLanguage();
        metadata.set(CONTENT_LANGUAGE, identifier.getLanguage());

        String langBelief = identifier.isReasonablyCertain() ? "strong" : "weak" ;
        metadata.set(CONTENT_LANGUAGE_BELIEF, langBelief);

        return textHandler.toString();
    }


    private void excludeParser(final Class exclude) {
        final CompositeParser composite = (CompositeParser) config.getParser();
        final Map<MediaType, Parser> parsers = composite.getParsers();
        final Iterator<Map.Entry<MediaType, Parser>> iterator = parsers.entrySet().iterator();
        final ParseContext context = new ParseContext();

        while (iterator.hasNext()) {
            Map.Entry<MediaType, Parser> pair = iterator.next();
            Parser parser = pair.getValue();
            if (exclude == parser.getClass()) {
                iterator.remove();
                excludedTypes.addAll(parser.getSupportedTypes(context));
            }
        }
        composite.setParsers(parsers);
    }

}
