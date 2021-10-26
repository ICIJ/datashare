package org.icij.datashare.com.mail;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;

public class Mail {

    public final String from;
    public final List<String> toRecipientList;
    public final List<String> ccRecipientList;
    public final String messageBody;
    public final String subject;

    public Mail(String from, List<String> toRecipientList, List<String> ccRecipientList, String subject, String messageBody) {
        this.from = from;
        this.toRecipientList = toRecipientList;
        this.ccRecipientList = ccRecipientList;
        this.messageBody = messageBody;
        this.subject = subject;
    }

    public Mail(String from, String recipient, String subject, String body) {
        this.from = from;
        this.toRecipientList = singletonList(recipient);
        this.ccRecipientList = new ArrayList<>();
        this.messageBody = body;
        this.subject = subject;
    }

    @Override public String toString() {
    	return String.format(
    			"subject:[%s]\n" +
    			"from:[%s]\n" +
    			"to:[%s]\n" +
    			"cc:[%s]\n" +
    			"body:[%s]", subject, from, toRecipientList, ccRecipientList, messageBody.trim());
    }
    
}
