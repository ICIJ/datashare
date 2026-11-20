package org.icij.datashare.user;

import org.icij.datashare.time.DatashareTime;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class DatashareApiKey implements ApiKey {
    public static final String ALGORITHM = "AES";
    final String hashedKey;
    final User user;
    final Date creationDate;
    final Date expirationDate;
    final List<String> projects;

    public DatashareApiKey(User user) throws NoSuchAlgorithmException {
        this(generateSecretKey(), user);
    }

    public DatashareApiKey(SecretKey secretKey, User user) {
        this(secretKey, user, null);
    }

    public DatashareApiKey(SecretKey secretKey, User user, Date expirationDate) {
        this(secretKey, user, expirationDate, List.of());
    }

    public DatashareApiKey(SecretKey secretKey, User user, Date expirationDate, List<String> projects) {
        this(hash(secretKey), user, DatashareTime.getInstance().now(), expirationDate, projects);
    }

    private static String hash(SecretKey secretKey) {
        String encoded = getBase64Encoded(secretKey);
        return DEFAULT_DIGESTER.hash(encoded);
    }

    public DatashareApiKey(String hashedKey, User user) {
        this(hashedKey, user, DatashareTime.getInstance().now());
    }

    public DatashareApiKey(String hashedKey, User user, Date creationDate) {
        this(hashedKey, user, creationDate, null);
    }

    public DatashareApiKey(String hashedKey, User user, Date creationDate, Date expirationDate) {
        this(hashedKey, user, creationDate, expirationDate, List.of());
    }

    public DatashareApiKey(String hashedKey, User user, Date creationDate, Date expirationDate, List<String> projects) {
        this.projects = projects == null ? List.of() : List.copyOf(projects);
        this.hashedKey = hashedKey;
        this.user = user;
        this.creationDate = creationDate;
        this.expirationDate = expirationDate;
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

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public Date getCreationDate() {
        return creationDate;
    }

    @Override
    public Date getExpirationDate() {
        return expirationDate;
    }

    @Override
    public List<String> getProjects() {
        return projects;
    }

    @Override
    public String getId() {
        return hashedKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DatashareApiKey))
            return false;
        DatashareApiKey that = (DatashareApiKey) o;
        return hashedKey.equals(that.hashedKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hashedKey);
    }
}
