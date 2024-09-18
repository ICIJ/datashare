package org.icij.datashare.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;

import static java.util.Arrays.stream;


public enum Hasher {
    MD5     (16),
    SHA_1   (40),
    SHA_256 (64),
    SHA_384 (96),
    SHA_512 (128);

    public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;
    private final String algorithm;
    public final int digestLength;

    Hasher(int digestLen) {
        algorithm    = name().replace('_', '-');
        digestLength = digestLen;
    }

    public static Hasher valueOf(int length) {
        return stream(values()).filter(h -> h.digestLength == length).findFirst().orElseThrow(IllegalArgumentException::new);
    }

    @Override
    public String toString() {
        return algorithm;
    }

    public String toStringWithoutDash() {
        return algorithm.replace("-", "");
    }

    public String hash(String message) {
        return hash(message, DEFAULT_ENCODING);
    }

    static final String HEXES = "0123456789abcdef";

    public static String getHex(byte[] raw) {
        if (raw == null) {
            return null;
        }
        final StringBuilder hex = new StringBuilder(2 * raw.length);
        for (final byte b : raw) {
            hex.append(HEXES.charAt((b & 0xF0) >> 4)).append(HEXES.charAt((b & 0x0F)));
        }
        return hex.toString();
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
            digest.update(message.getBytes(charset));
            return getHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
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
            throw new IllegalArgumentException("cannot hash document", e);
        }
    }

    /**
     * Hash message from Path and string prefix
     *
     * @param filePath representing the message to hash
     * @return the corresponding hash code String;
     * empty if algorithm is UNKNOWN or NONE or nothing to take from stream.
     */
    public String hash(final Path filePath, final String prefix) {
        try (InputStream stream = Files.newInputStream(filePath)) {
            return hash(stream, prefix);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot hash document", e);
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
        return hash(stream, "");
    }

    private String hash(InputStream stream, String prefix) {
        try {
            if (stream == null || stream.available() == 0) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(prefix.getBytes());
            byte[] buffer = new byte[4096];
            while (true) {
                int readCount = stream.read(buffer);
                if (readCount < 0) {
                    break;
                }
                digest.update(buffer, 0, readCount);
            }
            byte[] hashedBytes = digest.digest();
            return getHex(hashedBytes);

        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalArgumentException(e);
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
            return Optional.empty();
        }
    }

    public static String shorten(final String s, final int l) {
        return s.substring(0, l) + "..." + s.substring(s.length() - l);
    }

    public byte[] hash(byte[] buffer) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(buffer);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
