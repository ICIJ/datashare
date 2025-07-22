package org.icij.datashare.cli;

import joptsimple.ValueConversionException;
import joptsimple.ValueConverter;

public class PositiveIntegerConverter implements ValueConverter<Integer> {
    @Override
    public Integer convert(String value) {
        try {
            int intValue = Integer.parseInt(value);
            if (intValue <= 0) {
                throw new ValueConversionException("Value must be a positive integer");
            }
            return intValue;
        } catch (NumberFormatException e) {
            throw new ValueConversionException("Value must be a valid integer");
        }
    }

    @Override
    public Class<? extends Integer> valueType() { return Integer.class; }

    @Override
    public String valuePattern() { return "positive integer"; }
}