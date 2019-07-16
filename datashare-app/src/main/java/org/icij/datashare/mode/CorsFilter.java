package org.icij.datashare.mode;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.slf4j.LoggerFactory;

public class CorsFilter implements Filter {
    private final String corsPattern;

    public CorsFilter(String corsPattern) {
        this.corsPattern = corsPattern;
        LoggerFactory.getLogger(getClass()).info("adding Cross-Origin Request filter allows {}", corsPattern);
    }

    @Override
    public Payload apply(String s, Context context, PayloadSupplier payloadSupplier) throws Exception {
        return payloadSupplier.get().withAllowOrigin(corsPattern);
    }
}
