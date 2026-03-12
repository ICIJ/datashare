package org.icij.datashare.web.errors;

import net.codestory.http.errors.HttpException;
import org.icij.datashare.function.ThrowingSupplier;
import org.icij.datashare.policies.errors.InvalidValueException;

public class BadRequestException extends HttpException {
    public BadRequestException(final String message, final Throwable cause) {
        super(400, message, cause);
    }

    public static <T> T badRequestIfInvalid(ThrowingSupplier<T> supplier) {
        try {
            return supplier.getThrows();
        } catch (InvalidValueException e) {
            throw new BadRequestException(e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
