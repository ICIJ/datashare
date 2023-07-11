package org.icij.datashare.com.mail;

import com.sun.mail.smtp.SMTPMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Properties;

import static java.util.Optional.ofNullable;
import static javax.mail.Message.RecipientType.CC;
import static javax.mail.Message.RecipientType.TO;


public class MailSender {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    
    public final int port;
    public final String host;
    final String user;
    final String password;
    final boolean tls;
    final boolean debug;

    public MailSender(String host, int port) {
        this(host, port, null, null, false, false);
    }

    public MailSender(URI uri) {
        this(
            uri.getHost(),
            uri.getPort(),
            uri.getUserInfo() != null ? uri.getUserInfo().split(":")[0]: null,
            uri.getUserInfo() != null ? uri.getUserInfo().split(":")[1]: null,
            uri.getScheme().equals("smtps"),
            ofNullable(uri.getQuery()).orElse("").contains("debug=true")
        );
    }

    public MailSender(String host, int port, String user, String password, boolean tls, boolean debug) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.tls = tls;
        this.debug = debug;
    }

    public void send(Mail donneesEmail) throws MailException {
        try {
            Message message = createMessage(donneesEmail);
            send(message);
        } catch (NullPointerException e) {
            logger.error("If error is about MailcapFile, delete .mailcap in the user's home");
            throw new MailException(e);
        } catch (Throwable t) {
            logger.error("Failed to send mail : hostmail=" + host + ", port=" + port,  t);
            throw new MailException(t);
        }
    }

    private void send(Message message) throws MessagingException {
        if (shouldAuth()) {
            Transport.send(message, user, password);
        } else {
            Transport.send(message);
        }
    }

    private Message createMessage(Mail donneesEmail) throws MessagingException {
        Session mailSession = getMailSession(this);
        mailSession.setDebug(debug);
        if (shouldAuth()) {
            mailSession.getTransport().connect(host, user, password);
        }
        Message message = new SMTPMessage(mailSession);
        logger.info("MimeMessage: host = " + host + " - port = " + port);

        message.setHeader("X-Mailer", "msgsend");
        message.setSentDate(new java.util.Date());

        message.setFrom(new InternetAddress(donneesEmail.from));
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

    private synchronized static Session getMailSession(MailSender mailSender) {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.class", "com.sun.mail.smtp.SMTPTransport");
        properties.setProperty("mail.smtp.port", String.valueOf(mailSender.port));
        properties.setProperty("mail.smtp.host", mailSender.host);
        properties.setProperty("mail.smtp.auth", String.valueOf(mailSender.shouldAuth()));
        properties.setProperty("mail.smtp.ssl.enable", String.valueOf(mailSender.tls));

        return mailSender.shouldAuth() ? Session.getDefaultInstance(properties) : Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(mailSender.user, mailSender.password);
            }
        });
    }

    boolean shouldAuth() {return user != null;}
}
