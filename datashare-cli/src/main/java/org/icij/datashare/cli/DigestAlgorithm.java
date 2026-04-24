package org.icij.datashare.cli;

import joptsimple.ValueConverter;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

public enum DigestAlgorithm {
    MD5(),
    SHA_1(),
    SHA_256(),
    SHA_384(),
    SHA_512();
    public final String algorithm = this.name().replace("_", "");

    static DigestAlgorithm fromString(String s) {
        String normalized = s.toUpperCase().replaceAll("[_\\-]", "");
        for (DigestAlgorithm v : DigestAlgorithm.values()) {
            if (v.name().replace("_", "").equals(normalized)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown digest algorithm: " + s);
    }

    public String toString() {
        return this.algorithm;
    }

    public static class PicocliConverter implements ITypeConverter<DigestAlgorithm> {
        @Override
        public DigestAlgorithm convert(String s) {
            try {
                return fromString(s);
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(e.getMessage());
            }
        }
    }

    public static class DigestAlgorithmConverter implements ValueConverter<DigestAlgorithm> {

        @Override
        public DigestAlgorithm convert(String s) {
            return fromString(s);
        }

        @Override
        public Class<? extends DigestAlgorithm> valueType() {
            return DigestAlgorithm.class;
        }

        @Override
        public String valuePattern() {
            return "MD5, SHA1, SHA256, SHA384, SHA512 (with optional - or _ separator)";
        }
    }
}
