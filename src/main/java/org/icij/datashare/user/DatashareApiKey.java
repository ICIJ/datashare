package org.icij.datashare.user;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

public class DatashareApiKey implements ApiKey {
    public static final String ALGORITHM = "AES";
    final String hashedKey;
    final User user;

    public DatashareApiKey(User user) throws NoSuchAlgorithmException {
        this(generateSecretKey(), user);
    }

    public DatashareApiKey(SecretKey secretKey, User user) {
        this.user = user;
        this.hashedKey = HASHER.hash(getBase64Encoded(secretKey));
    }

    public DatashareApiKey(String hashedKey, User user) {
        this.hashedKey = hashedKey;
        this.user = user;
    }

    @Override
    public boolean match(String base64Key) {
        return HASHER.hash(base64Key).equals(hashedKey);
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
