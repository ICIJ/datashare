package org.icij.datashare.time;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

import static org.icij.datashare.time.Time.FORMAT_DATE;

public class DatashareDateUtils {
    /**
     * returns a date from string
     * @param dateStr
     * @return java.util.Date
     */
    public static Date formatDate(String dateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(FORMAT_DATE, Locale.ENGLISH);
        return Date.from(LocalDate.parse(dateStr, formatter).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    public static Date addMilliseconds(Date now, int msToAdd) {
        return new Date(now.getTime() + msToAdd);
    }
}
