package org.icij.datashare.cli;

import joptsimple.ValueConverter;

public enum DigestAlgorithm {
    MD5(),
    SHA_1(),
    SHA_256(),
    SHA_384(),
    SHA_512();
    public final String algorithm = this.name().replace('_', '-');

    public String toString() {
        return this.algorithm;
    }

    public static class DigestAlgorithmConverter implements ValueConverter<DigestAlgorithm> {

        @Override
        public DigestAlgorithm convert(String s) {
            return DigestAlgorithm.valueOf(s.replace('-', '_'));
        }

        @Override
        public Class<? extends DigestAlgorithm> valueType() {
            return DigestAlgorithm.class;
        }

        @Override
        public String valuePattern() {
            return "SHA-[1|256|384|512] or MD5";
        }
    }
}
