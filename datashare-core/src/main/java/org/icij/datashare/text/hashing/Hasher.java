package org.icij.datashare.text.hashing;

import java.util.Locale;
import java.util.Optional;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bouncycastle.util.encoders.Hex;

import org.icij.datashare.util.io.FileSystemUtils;


/**
 * Hashers
 *
 * Created by julien on 5/9/16.
 */
public enum Hasher {
    MD5     ("MD5"),
    SHA_1   ("SHA-1"),
    SHA_256 ("SHA-256"),
    SHA_384 ("SHA-384"),
    SHA_512 ("SHA-512");

    public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;


    private final String algorithm;

    Hasher(String algo) {
        algorithm = algo;
    }


    @Override
    public String toString() {
        return algorithm;
    }


    /**
     * Hash message String with algorithm
     *
     * @param message to hash
     * @return the corresponding hash Optional<String>, which might be
     * empty if algorithm is UNKNOWN or NONE or if message is empty.
     */
    public String hash(String message) {
        return hash(message, DEFAULT_ENCODING);
    }

    /**
     * Hash message String with algorithm and charset
     *
     * @param message to hash
     * @param charset to use for message
     * @return the corresponding hash Optional<String>, which might be
     * empty if algorithm is UNKNOWN or NONE or if message is empty.
     */
    public String hash(String message, Charset charset) {
        if (message == null || message.isEmpty())
            return "";
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashedBytes = digest.digest(message.getBytes(charset));
            return new String(Hex.encode(hashedBytes));
        } catch (NoSuchAlgorithmException e) {
            //throw new HashException("Failed to hash from String", e);
            return "";
        }
    }

    /**
     * Hash file from Path with algorithm
     *
     * @param filePath representing the message to hash
     * @return the corresponding hash Optional<String>, which might be
     * empty if algorithm is UNKNOWN or NONE or nothing to read from stream.
     */
    public String hash(Path filePath) {
        try (InputStream stream = Files.newInputStream(filePath)) {
            return hash(stream);
        } catch (IOException e) {
            //throw new HashException("Failed to hash from Path", e);
            return "";
        }
    }

    /**
     * Hash message InputStream with algorithm
     *
     * @param stream representing the message to hash
     * @return the corresponding hash Optional<String>, which might be
     * empty if algorithm is UNKNOWN or NONE or nothing to read from stream.
     */
    public String hash(InputStream stream) {
        try {
            if (stream == null || stream.available() == 0)
                return "";
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[FileSystemUtils.CHAR_BUFFER_SIZE];
            while (true) {
                int readCount = stream.read(buffer);
                if (readCount < 0)
                    break;
                digest.update(buffer, 0, readCount);
            }
            byte[] hashedBytes = digest.digest();
            return new String(Hex.encode(hashedBytes));
        } catch (NoSuchAlgorithmException | IOException e) {
            // throw new HashException("Failed to hash from InputStream", e);
            return "";
        }
    }


    public static Optional<Hasher> parse(final String algo) {
        if (algo == null || algo.isEmpty())
            return Optional.empty();
        try {
            return Optional.of(valueOf(algo.toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            //throw new IllegalArgumentException(String.format("\"%s\" is not a valid hash algorithm.", algo));
            return Optional.empty();
        }
    }

}