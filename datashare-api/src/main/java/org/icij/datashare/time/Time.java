package org.icij.datashare.time;

import java.util.Date;

/**
 * Interface for getting time in datashare.
 */
public interface Time {
    /**
     * @return <code>Date</code> now date for Datashare
     */
    Date now();

    /**
     * @return current milliseconds since 1st january 1970, 00:00:00 GMT
     */
    long currentTimeMillis();

    /**
     * sets current date
     *
     * @throws UnsupportedOperationException
     * @param date
     */
    void setMockDate(Date date);

    /**
     * sleeps the given time
     * @param milliseconds
     */
    void sleep(int milliseconds) throws InterruptedException;

    /**
     * sets current date
     *
     * @throws UnsupportedOperationException
     * @param dateTime formatted iso8601
     */
    void setMockDate(String dateTime);

    /**
     * sets current date and returns this date
     *
     * @throws UnsupportedOperationException
     * @param dateTime formatted iso8601
     */
    Date itIsNow(String dateTime);

	void addMilliseconds(int toAddInMs);

}
