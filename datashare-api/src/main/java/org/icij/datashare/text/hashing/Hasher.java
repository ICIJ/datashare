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
 * Family of String Hash algorithms
 *
 * Created by julien on 5/9/16.
 */
public enum Hasher {
    MD5     (32),
    SHA_1   (40),
    SHA_256 (64),
    SHA_384 (96),
    SHA_512 (128);

    public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

    // Hash algorithm name
    private final String algorithm;

    // Hash code length
    private final int digestLength;


    Hasher(int dgstLen) {
        algorithm    = name().replace('_', '-');
        digestLength = dgstLen;
    }


    @Override
    public String toString() {
        return algorithm;
    }


    /**
     * Hash message String with algorithm
     *
     * @param message the String to hash
     * @return the corresponding hash String;
     * empty if algorithm is UNKNOWN or NONE or if message is empty.
     */
    public String hash(String message) {
        return hash(message, DEFAULT_ENCODING);
    }

    /**
     * Hash message String with algorithm and charset
     *
     * @param message the String to hash
     * @param charset the charset to use for message
     * @return the corresponding hash code String;
     * empty if algorithm is UNKNOWN or NONE or if message is empty.
     */
    public String hash(String message, Charset charset) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashedBytes = digest.digest(message.getBytes(charset));
            return new String(Hex.encode(hashedBytes));

        } catch (NoSuchAlgorithmException e) {
            //throw new HasherException("Failed to hash from String", e);
            return "";
        }
    }

    /**
     * Hash message from Path
     *
     * @param filePath representing the message to hash
     * @return the corresponding hash code String;
     * empty if algorithm is UNKNOWN or NONE or nothing to take from stream.
     */
    public String hash(Path filePath) {
        try (InputStream stream = Files.newInputStream(filePath)) {
            return hash(stream);

        } catch (IOException e) {
            //throw new HasherException("Failed to hash from Path", e);
            return "";
        }
    }

    /**
     * Hash message from InputStream
     *
     * @param stream the message to hash
     * @return the corresponding hash code String;
     * empty if algorithm is UNKNOWN or NONE or nothing to take from stream.
     */
    public String hash(InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[FileSystemUtils.CHAR_BUFFER_SIZE];
            while (true) {
                int readCount = stream.read(buffer);
                if (readCount < 0) {
                    break;
                }
                digest.update(buffer, 0, readCount);
            }
            byte[] hashedBytes = digest.digest();
            return new String(Hex.encode(hashedBytes));

        } catch (NoSuchAlgorithmException | IOException e) {
            // throw new HasherException("Failed to hash from InputStream", e);
            return "";
        }
    }

    /**
     * Parse Hasher value from String
     *
     * @param algo the algorithm name as a String
     * @return the corresponding Optional Enum value if successful; empty Optional otherwise
     */
    public static Optional<Hasher> parse(final String algo) {
        if (algo == null || algo.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(algo.toUpperCase(Locale.ROOT).replace('-', '_')));

        } catch (IllegalArgumentException e) {
            //throw new IllegalArgumentException(String.format("\"%s\" is not a valid hash algorithm.", algo));
            return Optional.empty();
        }
    }

    /**
     * Is the String a valid hash code?
     *
     * @param str the hash code candidate to check (syntactically)
     * @return true if str is a valid hash code; false otherwise
     */
    public boolean isValidHash(String str) {
        return str.matches(String.format(Locale.ROOT, "[a-fA-F0-9]{%d}", digestLength));
    }

}