package org.icij.datashare.user;

import org.icij.datashare.time.DatashareTime;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;

public class DatashareApiKey implements ApiKey {
    public static final String ALGORITHM = "AES";
    final String hashedKey;
    final User user;
    final Date creationDate;

    public DatashareApiKey(User user) throws NoSuchAlgorithmException {
        this(generateSecretKey(), user);
    }

    public DatashareApiKey(SecretKey secretKey, User user) {
        this(DEFAULT_DIGESTER.hash(getBase64Encoded(secretKey)), user);
    }

    public DatashareApiKey(String hashedKey, User user) {
        this(hashedKey, user, DatashareTime.getInstance().now());
    }

    public DatashareApiKey(String hashedKey, User user, Date creationDate) {
        this.hashedKey = hashedKey;
        this.user = user;
        this.creationDate = creationDate;
    }

    @Override
    public boolean match(String base64Key) {
        return DEFAULT_DIGESTER.hash(base64Key).equals(hashedKey);
    }

    public static String getBase64Encoded(SecretKey secretKey) {
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    public static SecretKey generateSecretKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
        keyGen.init(256);
        return keyGen.generateKey();
    }

    @Override public User getUser() { return user;}
    @Override public Date getCreationDate() { return creationDate;}
    @Override public String getId() { return hashedKey;}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DatashareApiKey)) return false;
        DatashareApiKey that = (DatashareApiKey) o;
        return hashedKey.equals(that.hashedKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hashedKey);
    }
}
