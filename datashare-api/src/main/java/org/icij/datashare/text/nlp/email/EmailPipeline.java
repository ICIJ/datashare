package org.icij.datashare.text.nlp.email;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
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

/**
 * this is a fake NLP pipeline. It just uses syntactic methods to find
 * emails in document contents.
 *
 * first it uses the regexp mentioned here :
 * https://stackoverflow.com/questions/201323/how-to-validate-an-email-address-using-a-regular-expression
 *
 * It uses the same API as the NLP pipelines to integrate seamlessly to datashare.
 */
public class EmailPipeline extends AbstractPipeline {
    Pattern pattern = Pattern.compile("(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b" +
    "\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@" +
            "(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|" +
            "\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}" +
            "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|" +
            "\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])");

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
            annotations.add(NlpStage.NER, start, start + email.length(), email);
        }
        return annotations;
    }

    @Override
    public Type getType() { return Type.EMAIL;}
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
