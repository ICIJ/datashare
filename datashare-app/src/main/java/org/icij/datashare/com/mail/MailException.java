package org.icij.datashare.com.mail;


public class MailException extends RuntimeException {
    /**
     * wraps AddressException, MessagingException, IOException
     */
    public MailException(Throwable t) {
        super( t );
    }
}