package org.icij.datashare.time;

import java.time.Instant;
import java.util.Date;

public class DatashareDateUtils {
    /**
     * returns a date from string
     * @param dateStr iso8601 date
     * @return java.util.Date
     */
    public static Date formatDate(String dateStr) {
        return Date.from(Instant.parse(dateStr));
    }

    public static Date addMilliseconds(Date now, int msToAdd) {
        return new Date(now.getTime() + msToAdd);
    }
}
