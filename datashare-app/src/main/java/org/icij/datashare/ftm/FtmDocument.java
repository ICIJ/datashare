package org.icij.datashare.ftm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.tika.metadata.TikaCoreProperties;
import org.icij.ftm.Address;
import org.icij.ftm.Document;
import org.icij.ftm.Folder;
import org.joda.time.DateTime;

import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FtmDocument implements Document {
    public static final String TARGET_LANGUAGE_KEY = "target_language";
    public static final String TRANSLATED_CONTENT_KEY = "content";
    @JsonIgnore
    org.icij.datashare.text.Document icijDoc;
    public FtmDocument(org.icij.datashare.text.Document doc) {
        this.icijDoc = doc;
    }

    @Override
    public String getFileName() {
        return icijDoc.getPath().toString();
    }

    @Override
    public String getTitle() {
        return icijDoc.getTitle();
    }

    @Override
    public String getMimeType() {
        return icijDoc.getContentType();
    }

    @Override
    public String getContentHash() {
        return icijDoc.getId();
    }

    @Override
    public String getEncoding() {
        return icijDoc.getContentEncoding().toString();
    }

    @Override
    public String getBodyText() {
        return icijDoc.getContent();
    }

    @Override
    public int getFileSize() {
        return Math.toIntExact(icijDoc.getContentLength());
    }

    @Override
    public String getExtension() {
        String name = icijDoc.getPath().toFile().getName();
        return name.substring(name.lastIndexOf("."));
    }

    @Override
    public String getLanguage() {
        return icijDoc.getLanguage().toString();
    }

    @Override
    public String getDate() {
        return new DateTime(icijDoc.getCreationDate()).toDateTimeISO().toString();
    }

    @Override
    public String getProcessingStatus() {
        return icijDoc.getStatus().toString();
    }

    @Override
    public String getName() {
        return icijDoc.getName();
    }

    @Override
    public String getCountry() {
        return icijDoc.getLanguage().toString();
    }

    @Override
    public Folder getParent() {
        return null;
    }

    @Override
    public String getAuthor() {
        return (String) icijDoc.getMetadata().get(TikaCoreProperties.CREATOR.getName());
    }

    @Override
    public String getTranslatedLanguage() {
        return ofNullable(icijDoc.getContentTranslated()).filter(l -> !l.isEmpty()).map(maps -> maps.get(0).get(TARGET_LANGUAGE_KEY)).orElse(null);
    }

    @Override
    public String getTranslatedText() {
        return ofNullable(icijDoc.getContentTranslated()).stream().flatMap(List::stream).map(m -> m.get(TRANSLATED_CONTENT_KEY)).collect(Collectors.joining());
    }

    @Override
    public String getGenerator() {
        return null;
    }

    @Override
    public String getCrawler() {
        return null;
    }

    @Override
    public String getMessageId() {
        return null;
    }

    @Override
    public String getAuthoredAt() {
        return null;
    }

    @Override
    public String getPublishedAt() {
        return null;
    }

    @Override
    public Folder getAncestors() {
        return null;
    }

    @Override
    public String getProcessingError() {
        return null;
    }

    @Override
    public String getProcessingAgent() {
        return null;
    }

    @Override
    public String getProcessedAt() {
        return null;
    }

    @Override
    public String getSummary() {
        return null;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getAlias() {
        return null;
    }

    @Override
    public String getPreviousName() {
        return null;
    }

    @Override
    public String getWeakAlias() {
        return null;
    }

    @Override
    public URL getSourceUrl() {
        return null;
    }

    @Override
    public String getPublisher() {
        return null;
    }

    @Override
    public URL getPublisherUrl() {
        return null;
    }

    @Override
    public URL getAlephUrl() {
        return null;
    }

    @Override
    public URL getWikipediaUrl() {
        return null;
    }

    @Override
    public String getWikidataId() {
        return null;
    }


    @Override
    public String getKeywords() {
        return null;
    }

    @Override
    public String getTopics() {
        return null;
    }

    @Override
    public String getAddress() {
        return null;
    }

    @Override
    public Address getAddressEntity() {
        return null;
    }

    @Override
    public String getProgram() {
        return null;
    }

    @Override
    public String getNotes() {
        return null;
    }

    @Override
    public Document getProof() {
        return null;
    }

    @Override
    public String getIndexText() {
        return null;
    }

    @Override
    public String getCreatedAt() {
        return null;
    }

    @Override
    public String getModifiedAt() {
        return null;
    }

    @Override
    public String getRetrievedAt() {
        return null;
    }

    @Override
    public String getDetectedLanguage() {
        return null;
    }

    @Override
    public String getDetectedCountry() {
        return null;
    }

    @Override
    public String getNamesMentioned() {
        return null;
    }

    @Override
    public String getPeopleMentioned() {
        return null;
    }

    @Override
    public String getCompaniesMentioned() {
        return null;
    }

    @Override
    public String getIbanMentioned() {
        return null;
    }

    @Override
    public String getIpMentioned() {
        return null;
    }

    @Override
    public String getLocationMentioned() {
        return null;
    }

    @Override
    public String getPhoneMentioned() {
        return null;
    }

    @Override
    public String getEmailMentioned() {
        return null;
    }
}
