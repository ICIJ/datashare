package org.icij.datashare.text.extraction.tika.parser.ocr;

import com.sun.jna.Platform;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.image.ImageParser;
import org.apache.tika.parser.image.TiffParser;
import org.apache.tika.parser.jpeg.JpegParser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;


/**
 * Tess4J Tika Parser
 *
 * Created by julien on 2/15/17.
 */
public class Tess4JParser extends AbstractParser {

    private static final long serialVersionUID = -5693216478732659L;

    private static final Logger LOGGER = LoggerFactory.getLogger(Tess4JParser.class);

    private static final Tess4JParserConfig DEFAULT_CONFIG = new Tess4JParserConfig();

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(asList(MediaType.image("png"),
                    MediaType.image("jpeg"),
                    MediaType.image("jpx"),
                    MediaType.image("jp2"),
                    MediaType.image("gif"),
                    MediaType.image("tiff"),
                    MediaType.image("x-ms-bmp"),
                    MediaType.image("x-portable-pixmap")))
    );

    // TIKA-1445 workaround parser
    private static Parser _TMP_IMAGE_METADATA_PARSER = new Tess4JParser.CompositeImageParser();
    private static class CompositeImageParser extends CompositeParser {
        private static final long serialVersionUID = -1593574568521937L;
        private static List<Parser> imageParsers = asList(new Parser[]{
                new ImageParser(),
                new JpegParser(),
                new TiffParser()
        });
        CompositeImageParser() {
            super(new MediaTypeRegistry(), imageParsers);
        }
    }

    static {
        try {
            Files.list(Paths.get(LoadLibs.TESS4J_TEMP_DIR, Platform.RESOURCE_PREFIX))
                    .forEach( libPath -> {
                        System.load( libPath.toString() );
                        LOGGER.info("loading " + libPath );
                    });
        } catch (IOException e) {
            LOGGER.error("failed loading " + Platform.RESOURCE_PREFIX + " Tesseract related native libraries", e);
        }
    }


    private final ITesseract ocrInstance;


    public Tess4JParser() {
        ocrInstance = new Tesseract();
    }


    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        // If no Tess4JParserConfig set, don't advertise anything,
        // so the other image parsers can be selected instead
        if ( context.get(Tess4JParserConfig.class) == null )
            return Collections.emptySet();
        // Otherwise offer supported image types
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
            throws TikaException {

        String type = metadata.get(Metadata.CONTENT_TYPE)
                .replace("/", ".")
                .replace(" ", ".")
                .replace("-", ".")
                .toLowerCase();

        LOGGER.info("parsing content type: " +  type );

        TemporaryResources tmp = new TemporaryResources();
        try (TikaInputStream tis = TikaInputStream.get(stream, tmp)) {
            File input = tis.getFile();
            long size  = tis.getLength();
            Tess4JParserConfig config = context.get(Tess4JParserConfig.class, DEFAULT_CONFIG);
            if (size >= config.getMinFileSizeToOcr() && size <= config.getMaxFileSizeToOcr()) {
                File tmpFile = tmp.createTemporaryFile();
                File output  = tmpFile.toPath().resolveSibling(tmpFile.toPath().getFileName() + "." + type).toFile();
                Files.copy(input.toPath(), output.toPath());
                String ocrOutput = doOCR(output, config);
                Files.delete(output.toPath());
                extractOutput(new StringReader(ocrOutput), new XHTMLContentHandler(handler, metadata));
            }
            // Temporary workaround for TIKA-1445 - until we can specify
            //  composite parsers with strategies (eg Composite, Try In Turn),
            //  always send the image onwards to the regular parser to have
            //  the metadata for them extracted as well
            _TMP_IMAGE_METADATA_PARSER.parse(tis, handler, metadata, context);

        } catch (Exception e) {
            LOGGER.error("failed parsing", e);
        } finally {
            tmp.dispose();
        }
    }


    /**
     * Run Tess4J OCR instance
     *
     * @param input   the File to be OCRed
     * @param config  the Configuration for tesseract-ocr
     * @throws IOException if an input error occurred
     */
    private String doOCR(File input, Tess4JParserConfig config) {
        File tessdataDir = Paths.get(LoadLibs.TESS4J_TEMP_DIR, config.getTessdataPath()).toFile();
        if ( ! Files.exists(tessdataDir.toPath())){
            LOGGER.info("loading Tesseract data");
            LoadLibs.extractTessResources(config.getTessdataPath());
        }
        ocrInstance.setDatapath(tessdataDir.getParent());
        ocrInstance.setLanguage(config.getLanguage().iso6392Code());
        ocrInstance.setPageSegMode(config.getPageSegMode());
        ocrInstance.setOcrEngineMode(config.getOcrEngineMode());
        try {
            return ocrInstance.doOCR(input);
        } catch (TesseractException e) {
            LOGGER.error("OCR failed", e);
            return "";
        }
    }

    /**
     * Reads the contents of the given stream and write it to the given XHTML
     * content handler. The stream is closed once fully processed.
     *
     * @param reader
     *          Reader where is the result of ocr
     * @param xhtml
     *          XHTML content handler
     * @throws SAXException
     *           if the XHTML SAX events could not be handled
     * @throws IOException
     *           if an input error occurred
     */
    private void extractOutput(final Reader reader, final XHTMLContentHandler xhtml) throws SAXException, IOException {
        xhtml.startDocument();
        xhtml.startElement("div", "class", "ocr");

        final char[] buffer = new char[1024];
        for (int n = reader.read(buffer); n != -1; n = reader.read(buffer)) {
            if (n > 0) {
                xhtml.characters(buffer, 0, n);
            }
        }

        xhtml.endElement("div");
        xhtml.endDocument();
    }
}
