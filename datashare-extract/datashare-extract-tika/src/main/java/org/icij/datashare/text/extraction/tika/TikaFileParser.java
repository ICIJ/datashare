package org.icij.datashare.text.extraction.tika;

import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObjectFactory;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.LinkContentHandler;
import org.apache.tika.sax.TeeContentHandler;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.extraction.AbstractFileParser;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.extraction.tika.parser.ocr.Tess4JParserConfig;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static org.icij.datashare.text.Language.*;


/**
 * {@link AbstractFileParser}
 * {@link FileParser}
 * {@link Type#TIKA}
 *
 * Created by julien on 3/9/16.
 */
public final class TikaFileParser extends AbstractFileParser {

    private static final long serialVersionUID = 1346797528461364L;

    public static final String RESOURCE_NAME    = Metadata.RESOURCE_NAME_KEY;
    public static final String CONTENT_TYPE     = Metadata.CONTENT_TYPE;
    public static final String CONTENT_LENGTH   = Metadata.CONTENT_LENGTH;
    public static final String CONTENT_ENCODING = Metadata.CONTENT_ENCODING;
    public static final String CONTENT_LANGUAGE = Metadata.CONTENT_LANGUAGE;
    public static final Function<Language, Language> LANGUAGE_MAPPER = lang -> {
        if (lang.equals(ENGLISH) || lang.equals(SPANISH) || lang.equals(FRENCH) || lang.equals(GERMAN))
            return lang;
        if (lang.equals(GALICIAN) || lang.equals(CATALAN))
            return SPANISH;
        return ENGLISH;
    };


    private final AutoDetectParser parser;

    private final ParseContext context = new ParseContext();

    private final TikaConfig config = TikaConfig.getDefaultConfig();

    private final PDFParserConfig pdfConfig = new PDFParserConfig();

    private final Tess4JParserConfig ocrConfig = new Tess4JParserConfig();

    private final Set<MediaType> excludedMediaTypes = new HashSet<>();

    private final LanguageDetector languageDetector = LanguageDetectorBuilder.create(NgramExtractors.standard())
            .withProfiles(new LanguageProfileReader().readAllBuiltIn())
            .build();

    private final TextObjectFactory textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();


    public TikaFileParser(Properties properties) throws IOException {
        super(properties);

        parser = new AutoDetectParser(config);
        parser.setFallback(new ErrorParser(parser, excludedMediaTypes));

        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);

        if (ocrEnabled) { enableOcr(); } else { disableOcr(); }

        context.set(Parser.class, parser);
        context.set(PDFParserConfig.class, pdfConfig);
    }

    public TikaFileParser() throws IOException {
        this(new Properties());
    }


    @Override
    public Optional<Document> parse(Path filePath) {
        Metadata metadata = new Metadata();
        try (TikaInputStream input = TikaInputStream.get(filePath, metadata)) {
            return parse(input, metadata, filePath);
        } catch (IOException | SAXException | TikaException e) {
            LOGGER.error("failed parsing from " + filePath, e);
            return Optional.empty();
        }
    }

    private Optional<Document> parse(InputStream is, Metadata metadata, Path filePath)
            throws TikaException, SAXException, IOException {
        BodyContentHandler textHandler = new BodyContentHandler(-1);
        LinkContentHandler linkHandler = new LinkContentHandler();
        TeeContentHandler  handler     = new TeeContentHandler(textHandler, linkHandler);

        LOGGER.info(getClass().getName() + " PARSING " + filePath);

        parser.parse(is, handler, metadata, context);

        String content = textHandler.toString();

        languageDetector.detect(textObjectFactory.forText(content))
                .transform(Optional::of).or(Optional::empty)
                .ifPresent( ldLocale -> metadata.set(CONTENT_LANGUAGE, applyLanguageMap(ldLocale.getLanguage())) );

        return Document.create(
                filePath,
                content,
                getLanguage(metadata).orElse(ENGLISH),
                getEncoding(metadata).orElse(StandardCharsets.UTF_8),
                getContentType(metadata).orElse("UNKNOWN"),
                getMetadataAsMap(metadata).orElse(new HashMap<>())
        );
    }

    private void enableOcr() {
        ocrConfig.setLanguage(language);
        pdfConfig.setExtractInlineImages(true);
        context.set(Tess4JParserConfig.class, ocrConfig);
        ocrEnabled = true;
    }

    private void disableOcr() {
        pdfConfig.setExtractInlineImages(false);
        context.set(Tess4JParserConfig.class, null);
        ocrEnabled = false;
    }

    private boolean ocrEnabled() {
        return ocrEnabled;
    }

    private void setOcrLanguage(Language language) {
        if (language != null && ! language.equals(UNKNOWN))
            ocrConfig.setLanguage(language);
    }

    private String applyLanguageMap(String language) {
        Optional<Language> langOpt = Optional.ofNullable(Language.parse(language));
        return langOpt.isPresent() ?
                LANGUAGE_MAPPER.apply(langOpt.get()).toString() :
                language;
    }

    private void excludeParser(Class exclude) {
        final ParseContext                           context   = new ParseContext();
        final CompositeParser                        composite = (CompositeParser) config.getParser();
        final Map<MediaType, Parser>                 parsers   = composite.getParsers();
        final Iterator<Map.Entry<MediaType, Parser>> iterator  = parsers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MediaType, Parser> pair = iterator.next();
            Parser parser = pair.getValue();
            if (parser.getClass() == exclude) {
                iterator.remove();
                excludedMediaTypes.addAll(parser.getSupportedTypes(context));
            }
        }
        composite.setParsers(parsers);
    }


    private Optional<Language> getLanguage(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_LANGUAGE))
            return Optional.empty();
        return Optional.ofNullable(Language.parse(metadata.get(CONTENT_LANGUAGE)));
    }

    private OptionalInt getLength(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_LENGTH))
            return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(metadata.get(CONTENT_LENGTH)));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private Optional<Charset> getEncoding(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_ENCODING))
            return Optional.empty();
        try{
            return Optional.of(Charset.forName(metadata.get(CONTENT_ENCODING)));
        } catch (Exception e) {
            LOGGER.error("failed parsing Charset " + metadata.get(CONTENT_ENCODING), e);
            return Optional.empty();
        }
    }

    private Optional<String> getName(Metadata metadata) {
        if (! asList(metadata.names()).contains(RESOURCE_NAME))
            return Optional.empty();
        return Optional.of(metadata.get(RESOURCE_NAME));
    }

    private Optional<String> getContentType(Metadata metadata) {
        if (! asList(metadata.names()).contains(CONTENT_TYPE))
            return Optional.empty();
        return Optional.of(metadata.get(CONTENT_TYPE));
    }

    private Optional<Map<String, String>> getMetadataAsMap(Metadata metadata) {
        Map<String, String> map = new HashMap<String, String>() {{
            stream(metadata.names()).forEach( key -> put(key, metadata.get(key)) );
        }};
        return Optional.of(map);
    }

}
