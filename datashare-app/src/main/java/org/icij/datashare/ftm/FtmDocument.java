package org.icij.datashare.ftm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.icij.ftm.Address;
import org.icij.ftm.Document;
import org.icij.ftm.Folder;
import org.joda.time.DateTime;

import java.net.URL;

public class FtmDocument implements Document {
    @JsonIgnore
    org.icij.datashare.text.Document icjjDoc;
    public FtmDocument(org.icij.datashare.text.Document doc) {
        this.icjjDoc = doc;
    }

    @Override
    public String getFileName() {
        return icjjDoc.getPath().toString();
    }

    @Override
    public String getTitle() {
        return icjjDoc.getTitle();
    }

    @Override
    public String getMimeType() {
        return icjjDoc.getContentType();
    }

    @Override
    public Folder getParent() {
        return null;
    }

    @Override
    public String getContentHash() {
        return icjjDoc.getId();
    }

    @Override
    public String getAuthor() {
        return null;
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
    public int getFileSize() {
        return Math.toIntExact(icjjDoc.getContentLength());
    }

    @Override
    public String getExtension() {
        String name = icjjDoc.getPath().toFile().getName();
        return name.substring(name.lastIndexOf("."));
    }

    @Override
    public String getEncoding() {
        return icjjDoc.getContentEncoding().toString();
    }

    @Override
    public String getBodyText() {
        return icjjDoc.getContent();
    }

    @Override
    public String getMessageId() {
        return null;
    }

    @Override
    public String getLanguage() {
        return icjjDoc.getLanguage().toString();
    }

    @Override
    public String getTranslatedLanguage() {
        return null;
    }

    @Override
    public String getTranslatedText() {
        return null;
    }

    @Override
    public String getDate() {
        return new DateTime(icjjDoc.getCreationDate()).toDateTimeISO().toString();
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
    public String getProcessingStatus() {
        return icjjDoc.getStatus().toString();
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
    public String getName() {
        return icjjDoc.getName();
    }

    @Override
    public String getCountry() {
        return icjjDoc.getLanguage().toString();
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
