package org.icij.datashare.time;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;


/**
 *  time provider for testing purpose
 */
public class DatashareMockTime implements Time {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());
    private Date mockDate = new Date();
    private final List<DateChangeListener> listeners = new LinkedList<>();

    public void setMockDate(Date mockDate) {
        logger.debug("mock time in datashare : " + mockDate);
        this.mockDate = mockDate;
        notifyListeners();
    }

    @Override
    public void sleep(int milliseconds) {
        addMilliseconds(milliseconds);
    }

    public void setMockDate(String dateTime) {
		setMockDate(DatashareDateUtils.formatDate(dateTime));
	}

	public void addMilliseconds(int timeToAddInMs) {
		setMockDate(DatashareDateUtils.addMilliseconds(now(), timeToAddInMs));
	}

    protected void notifyListeners() {
        for (DateChangeListener listener : listeners) {
            listener.dateChanged();
        }
    }

    public Date now() { return mockDate; }

    public long currentTimeMillis() {
        return mockDate.getTime();
    }

    public void register(DateChangeListener listener) {
        listeners.add(listener);
    }

    public Date itIsNow(String date) {
    	setMockDate(date);
    	return now();
    }

}
