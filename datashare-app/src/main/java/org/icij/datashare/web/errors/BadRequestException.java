package org.icij.datashare.web.errors;

import net.codestory.http.errors.HttpException;
import org.icij.datashare.function.ThrowingSupplier;

public class BadRequestException extends HttpException {
    public BadRequestException(final String message) {
        super(400, message);
    }

    public static <T> T badRequestIfInvalid(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
