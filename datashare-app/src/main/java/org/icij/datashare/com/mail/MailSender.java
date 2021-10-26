package org.icij.datashare.com.mail;

import java.util.Properties;


import com.sun.mail.smtp.SMTPMessage;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;


public class MailSender {

    protected final Log logger = LogFactory.getLog(this.getClass());
    
    public final int port;
    public final String host;

    public MailSender(int host, String port) {
        this.port = host;
        this.host = port;
    }

    public void send(Mail donneesEmail) throws MailException {
        try {
            Message message = createMessage(donneesEmail);
            Transport.send(message);
        } catch (NullPointerException e) {
            logger.error("If error is about MailcapFile, delete .mailcap in the user's home");
            throw new MailException(e);
        } catch (Throwable t) {
            logger.fatal("Failed to send mail : hostmail=" + host + ", port=" + port + ", Exception=" + t.getMessage());
            throw new MailException(t);
        }
    }

    private Message createMessage(Mail donneesEmail) throws MessagingException, AddressException {
        Message message = new SMTPMessage(getMailSession(port, host));
        logger.info("MimeMessage: host = " + host + " - port = " + port);

        message.setHeader("X-Mailer", "msgsend");
        message.setSentDate(new java.util.Date());

        message.setFrom(new InternetAddress(donneesEmail.from));
        logger.info("donneesEmail.listeDestinatairesFrom = " + donneesEmail.from);
        logger.info("donneesEmail.listeDestinatairesTo = "   + donneesEmail.toRecipientList);
        logger.info("donneesEmail.listeDestinatairesCc = "   + donneesEmail.ccRecipientList);
        if (donneesEmail.toRecipientList != null) {
            for (String to : donneesEmail.toRecipientList) {
                message.addRecipient(TO, new InternetAddress(to));
            }
        }
        if (donneesEmail.ccRecipientList != null) {
            for (String cc : donneesEmail.ccRecipientList) {
                message.addRecipient(CC, new InternetAddress(cc));
            }
        }
        message.setSubject(donneesEmail.subject);
        message.setText(donneesEmail.messageBody);
        return message;
    }

    private synchronized static Session getMailSession(int portTransportMail, String hostTransportMail) {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.class", "com.sun.mail.smtp.SMTPTransport");
        properties.setProperty("mail.smtp.port", String.valueOf(portTransportMail));
        properties.setProperty("mail.smtp.host", hostTransportMail);
        return Session.getDefaultInstance(properties);
    }
}
