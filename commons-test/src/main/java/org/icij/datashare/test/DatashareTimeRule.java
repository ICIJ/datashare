package org.icij.datashare.test;

import org.icij.datashare.time.DatashareDateUtils;
import org.icij.datashare.time.DatashareTime;
import org.junit.rules.ExternalResource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;


public class DatashareTimeRule extends ExternalResource {
	public final Date now;
	/**
	 * @param now in iso8601 yyyy-MM-ddTHH:mm:ssZ
	 */
	public DatashareTimeRule(String now) {
		this.now = DatashareDateUtils.formatDate(now);
	}

	@Override protected void before() {
		DatashareTime.setMockTime(true);
		DatashareTime.getInstance().setMockDate(now);
	}

	@Override protected void after() {
		DatashareTime.setMockTime(false);
	}
	public Date now() {
		return DatashareTime.getInstance().now();
	}
	@Override public String toString() { return ZonedDateTime.now( ZoneOffset.UTC ).format( DateTimeFormatter.ISO_INSTANT); }
}
