package org.icij.datashare.text.extraction.tika.parser.ocr;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

import net.sourceforge.tess4j.ITessAPI;

import org.icij.datashare.text.Language;


/**
 * Tess4J Tika Parser Configuration
 * <p>
 * Created by julien on 2/15/17.
 */
public class Tess4JParserConfig implements Serializable {

    private static final long serialVersionUID = -3693268974548732L;


    // Path to the 'tessdata' folder, which contains language files and config files.
    private String tessdataPath = Paths.get(this.getClass().getPackage().getName().replace(".", "/"), "tessdata").toString();

    //Paths.get(
//            System.getProperty("user.dir"), "src", "main", "resources",
//            Paths.get(this.getClass().getPackage().getName().replace(".", "/"), "tessdata").toString()
//        ).toString();

    // Language dictionary to be used.
    private Language language = Language.ENGLISH;

    // Tesseract page segmentation mode.
    private int pageSegMode = ITessAPI.TessPageSegMode.PSM_AUTO_OSD;

    // Tesseract engine mode
    private int ocrEngineMode = ITessAPI.TessOcrEngineMode.OEM_DEFAULT;;

    // Minimum file size to submit file to ocr.
    private int minFileSizeToOcr = 0;

    // Maximum file size to submit file to ocr.
    private int maxFileSizeToOcr = Integer.MAX_VALUE;

    // Maximum time (seconds) to wait for the ocring process termination
    private int timeout = 120;


    /**
     * Default contructor
     */
    public Tess4JParserConfig() {
        Path propertiesPath = Paths.get(this.getClass().getPackage().getName().replace(".", "/"), "Tess4JParserConfig.properties");
        init( this.getClass().getResourceAsStream( propertiesPath.toString()) );
    }

//    /**
//     * Loads properties from InputStream and then tries to close InputStream.
//     * If there is an IOException, this silently swallows the exception
//     * and goes back to the default.
//     *
//     * @param is
//     */
//    public Tess4JParserConfig(InputStream is) {
//        init(is);
//    }


    private void init(InputStream is) {
        if (is == null) {
            return;
        }
        Properties props = new Properties();
        try {
            props.load(is);
        } catch (IOException e) {
            /*NOOP*/
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                /*NOOP*/
            }
        }
//        setTessdataPath    (getProp(props, "tessdataPath",            getTessdataPath()));
//        setLanguage        (Language.parse(getProp(props, "language", getLanguage().iso6392Code())).orElse(Language.ENGLISH));
//        setPageSegMode     (getProp(props, "pageSegMode",             getPageSegMode()));
//        setMinFileSizeToOcr(getProp(props, "minFileSizeToOcr",        getMinFileSizeToOcr()));
//        setMaxFileSizeToOcr(getProp(props, "maxFileSizeToOcr",        getMaxFileSizeToOcr()));
//        setTimeout         (getProp(props, "timeout",                 getTimeout()));
    }


    /**
     * @see #setTessdataPath(String tessdataPath)
     */
    public String getTessdataPath() {
        return tessdataPath;
    }

    /**
     * Set the path to the 'tessdata' folder, which contains language files and config files. In some cases (such
     * as on Windows), this folder is found in the Tesseract installation, but in other cases
     * (such as when Tesseract is built from source), it may be located elsewhere.
     */
    public void setTessdataPath(String tessdataPath) {
        if (!tessdataPath.isEmpty() && !tessdataPath.endsWith(File.separator))
            tessdataPath += File.separator;
        this.tessdataPath = tessdataPath;
    }

    /**
     * @see #setLanguage(Language language)
     */
    public Language getLanguage() {
        return language;
    }

    /**
     * Set tesseract language dictionary to be used. Default is "eng".
     * Multiple languages may be specified, separated by plus characters.
     */
    public void setLanguage(Language language) {
        this.language = language;
    }

    /**
     * @see #setPageSegMode(int pageSegMode)
     */
    public int getPageSegMode() {
        return pageSegMode;
    }

    /**
     * Set tesseract page segmentation mode.
     * Default is 1 = Automatic page segmentation with OSD (Orientation and Script Detection)
     */
    public void setPageSegMode(int pageSegMode) {
        this.pageSegMode = pageSegMode;
    }

    /**
     * @see #setMinFileSizeToOcr(int minFileSizeToOcr)
     */
    public int getMinFileSizeToOcr() {
        return minFileSizeToOcr;
    }

    /**
     * Set minimum file size to submit file to ocr.
     * Default is 0.
     */
    public void setMinFileSizeToOcr(int minFileSizeToOcr) {
        this.minFileSizeToOcr = minFileSizeToOcr;
    }

    /**
     * @see #setMaxFileSizeToOcr(int maxFileSizeToOcr)
     */
    public int getMaxFileSizeToOcr() {
        return maxFileSizeToOcr;
    }

    /**
     * Set maximum file size to submit file to ocr.
     * Default is Integer.MAX_VALUE.
     */
    public void setMaxFileSizeToOcr(int maxFileSizeToOcr) {
        this.maxFileSizeToOcr = maxFileSizeToOcr;
    }

    /**
     * Set maximum time (seconds) to wait for the ocring process to terminate.
     * Default value is 120s.
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * @see #setTimeout(int timeout)
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * @see #setOcrEngineMode(int)
     */
    public int getOcrEngineMode() {
        return ocrEngineMode;
    }

    /**
     * Set Ocr engine mode
     *
     * @param ocrEngineMode
     */
    public void setOcrEngineMode(int ocrEngineMode) {
        this.ocrEngineMode = ocrEngineMode;
    }


    /**
     * Get property from the properties file passed in.
     *
     * @param properties     properties file to read from.
     * @param property       the property to fetch.
     * @param defaultMissing default parameter to use.
     * @return the value.
     */
    private int getProp(Properties properties, String property, int defaultMissing) {
        String p = properties.getProperty(property);
        if (p == null || p.isEmpty()) {
            return defaultMissing;
        }
        try {
            return Integer.parseInt(p);
        } catch (Throwable ex) {
            throw new RuntimeException(
                    String.format(
                            Locale.ROOT,
                            "Cannot parse Tess4JParserConfig variable %s, invalid integer value",
                            property),
                    ex);
        }
    }

    /**
     * Get property from the properties file passed in.
     *
     * @param properties     properties file to read from.
     * @param property       the property to fetch.
     * @param defaultMissing default parameter to use.
     * @return the value.
     */
    private String getProp(Properties properties, String property, String defaultMissing) {
        return properties.getProperty(property, defaultMissing);
    }

}
