package org.icij.datashare.nlp;

import com.google.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collection;
import java.util.Collections;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntitiesBuilder;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.AbstractPipeline;

import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
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
 * if the document is an rfc822 email
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
    private static final String MESSAGE_HEADER_FIELD = "emailHeaderField";
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
    public List<NamedEntity> process(Document doc) {
        return process(doc, doc.getContentTextLength(), 0);
    }

    @Override
    public List<NamedEntity> process(Document doc, int contentLength, int contentOffset) {
        Matcher matcher = pattern.matcher(doc.getContent().substring(contentOffset, Math.min(contentLength + contentOffset, doc.getContentTextLength())));
        NamedEntitiesBuilder namedEntitiesBuilder = new NamedEntitiesBuilder(EMAIL, doc.getId(), doc.getLanguage()).withRoot(doc.getRootDocument());
        while (matcher.find()) {
            String email = matcher.group(0);
            int start = matcher.start();
            namedEntitiesBuilder.add(NamedEntity.Category.EMAIL, email, start + contentOffset);
        }
        List<NamedEntity> entities = namedEntitiesBuilder.build();
        if ("message/rfc822".equals(doc.getContentType())) {
            entities.addAll(processMetadata(doc));
        }
        return entities;
    }

    protected List<NamedEntity> processMetadata(Document doc) {
        return parsedEmailHeaders
            .stream()
            .flatMap(k -> Optional.ofNullable(doc.getMetadata().get(k))
                .map(m -> {
                    Map<String, Object> meta = Map.of(MESSAGE_HEADER_FIELD, k);
                    NamedEntitiesBuilder builder = new NamedEntitiesBuilder(
                        EMAIL, doc.getId(), doc.getLanguage())
                        .withRoot(doc.getRootDocument())
                        .withMetadata(meta);
                    Matcher metaMatcher = pattern.matcher(m.toString());
                    while (metaMatcher.find()) {
                        builder.add(NamedEntity.Category.EMAIL, metaMatcher.group(0),
                            -1);
                    }
                    return builder.build();
                }).stream()
            )
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
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
    public Set<Language> supportedLanguages() {
        return Set.of(Language.values());
    }

    @Override
    public List<NamedEntity.Category> getTargetEntities() { return Collections.singletonList(NamedEntity.Category.EMAIL);}

    @Override
    public boolean isCaching() { return false;}

    @Override
    public Charset getEncoding() { return Charset.defaultCharset();}

    @Override
    public boolean initialize(Language language) { return true;}
}
