package org.icij.datashare.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import net.codestory.http.Request;
import org.icij.datashare.BodyTooLargeException;

/**
 * Utility class that will read for a maximum number of bytes in the request to avoid denial of service
 * attacks by sending big requests
 */
public class BoundedJsonBody {
    /**
     * Reads at most maxNumberOfBytes bytes from the body and returns it as a json object.
     * @param request from which body bounded read is made
     * @param maxNumberOfBytes the number of bytes max that will be read
     * @return JSON object read from the body
     * @throws BodyTooLargeException if there are more bytes in the body of the request than maxNumberOfBytes
     * @throws IOException in case of error reading bytes from the request
     */
    public static JsonNode boundedRead(Request request, ObjectMapper objectMapper, int maxNumberOfBytes) throws
            IOException {
        try (InputStream is = request.inputStream()) {
            byte[] read = is.readNBytes(maxNumberOfBytes + 1);
            if (read.length > maxNumberOfBytes) {
                throw new BodyTooLargeException(maxNumberOfBytes);
            }
            return objectMapper.readTree(read);
        }
    }
}
