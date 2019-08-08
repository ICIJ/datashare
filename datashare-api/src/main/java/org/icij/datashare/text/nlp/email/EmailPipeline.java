package org.icij.datashare.text.nlp.email;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.joining;
import static org.icij.datashare.text.NamedEntity.allFrom;
import static org.icij.datashare.text.nlp.Pipeline.Type.EMAIL;


/**
 * this is a fake NLP pipeline. It just uses syntactic methods to find
 * emails in document contents.
 * <p>
 * it uses the regexp mentioned here :
 * https://stackoverflow.com/questions/201323/how-to-validate-an-email-address-using-a-regular-expression
 * <p>
 * It implements the same API as the NLP pipelines to integrate seamlessly to datashare.
 *
 * if the the document is an rfc822 email
 * then we also parse a list of headers coming from https://tools.ietf.org/html/rfc2076
 * and transformed by tika/extract (for the keys).
 *
 * These fields are supposed to contain email addresses that we want to
 * save as named entities.
 *
 */
public class EmailPipeline extends AbstractPipeline {
    private static final String DEFAULT_METADATA_FIELD_PREFIX = "tika_metadata_";
    private static final String RAW_HEADER_FIELD_PREFIX = "Message-Raw-Header-";
    private static final String MESSAGE_FIELD_PREFIX = "Message-";
    final Pattern pattern = Pattern.compile("(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b" +
            "\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@" +
            "(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|" +
            "\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}" +
            "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|" +
            "\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])");

    private final Set<String> parsedEmailHeaders = unmodifiableSet(new HashSet<>(asList(
            tika("Dc-Title"),
            tika("Dc-Creator"),
            tika("Creator"),
            tika("Author"),
            tika("Meta-Author"),

            tikaMsgHeader("To"),
            tikaMsgHeader("From"),
            tikaMsgHeader("Cc"),
            tikaMsgHeader("Bcc"),

            tikaRawHeader("Return-Path"),
            tikaRawHeader("Delivered-To"),
            tikaRawHeader("Reply-To"),
            tikaRawHeader("Followup-To"),
            tikaRawHeader("Alternate-Recipient"),
            tikaRawHeader("For-Handling"),
            tikaRawHeader("Resent-Reply-To"),
            tikaRawHeader("Resent-Sender"),
            tikaRawHeader("Resent-From"),
            tikaRawHeader("Resent-To"),
            tikaRawHeader("Resent-cc"),
            tikaRawHeader("Resent-bcc")
    )));

    @Inject
    public EmailPipeline(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.getProperties());
    }

    @Override
    public Annotations process(String content, String docId, Language language) {
        Annotations annotations = new Annotations(docId, getType(), language);
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String email = matcher.group(0);
            int start = matcher.start();
            annotations.add(NlpStage.NER, start, start + email.length(), NamedEntity.Category.EMAIL);
        }
        return annotations;
    }

    @Override
    protected List<NamedEntity> processHeaders(Document doc) {
        if ("message/rfc822".equals(doc.getContentType())) {
            String metadataString = parsedEmailHeaders.stream().map(key -> doc.getMetadata().getOrDefault(key, "").toString()).collect(joining(" "));
            Annotations metaDataAnnotations = process(metadataString, doc.getId(), doc.getLanguage());
            return allFrom(metadataString, metaDataAnnotations).stream().map(ne ->
                    NamedEntity.create(ne.getCategory(), ne.getMention(), -1,
                            ne.getDocumentId(), ne.getRootDocument(), ne.getExtractor(),
                            ne.getExtractorLanguage())).collect(Collectors.toList());
        }
        return super.processHeaders(doc);
    }

    public static String tikaRawHeader(String s) {
        return tika(RAW_HEADER_FIELD_PREFIX + s);
    }

    public static String tikaMsgHeader(String s) {
        return tika(MESSAGE_FIELD_PREFIX + s);
    }

    public static String tika(String s) {
        return DEFAULT_METADATA_FIELD_PREFIX + s.toLowerCase().replace("-", "_");
    }

    @Override
    public Type getType() { return EMAIL;}

    @Override
    public void terminate(Language language) {}

    @Override
    public Map<Language, Set<NlpStage>> supportedStages() { throw new NotImplementedException();}

    @Override
    public boolean supports(NlpStage stage, Language language) { return stage == NlpStage.NER;}

    @Override
    public List<NamedEntity.Category> getTargetEntities() { return Collections.singletonList(NamedEntity.Category.EMAIL);}

    @Override
    public List<NlpStage> getStages() { return Collections.singletonList(NlpStage.NER);}

    @Override
    public boolean isCaching() { return false;}

    @Override
    public Charset getEncoding() { return Charset.defaultCharset();}

    @Override
    public boolean initialize(Language language) { return true;}

    @Override
    public Optional<String> getPosTagSet(Language language) { return Optional.empty();}
}
