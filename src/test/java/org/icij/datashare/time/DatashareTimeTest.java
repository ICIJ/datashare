package org.icij.datashare.time;

import org.junit.Assert;
import org.junit.Test;

import java.time.format.DateTimeParseException;
import java.util.Date;

import static org.fest.assertions.Assertions.assertThat;


public class DatashareTimeTest {

	@Test
	public void testGetCurrentDate() {
		DatashareTime.setMockTime(false);
		Date datashareNow = DatashareTime.getInstance().now();
		Date dateNow = new Date();
		Assert.assertNotNull(datashareNow);
		Assert.assertTrue("we suppose there is less than 100ms between 2 lines of code",
				dateNow.getTime() - datashareNow.getTime() < 100L);
	}

	@Test
	public void testGetNowReturnCurrentDate() throws Exception {
		DatashareTime.setMockTime(false);
		Date datashareNow = DatashareTime.getNow();
		Date dateNow = new Date();
		Assert.assertNotNull(datashareNow);
		Assert.assertTrue("we suppose there is less than 100ms between 2 lines of code",
				dateNow.getTime() - datashareNow.getTime() < 100L);
	}

	@Test
	public void testGetNowReturnMockDate() {
		Date date = DatashareDateUtils.formatDate("2020-02-20T12:13:14Z");

		DatashareTime.setMockTime(true);
		DatashareTime.getInstance().setMockDate(date);
		Assert.assertEquals(date, DatashareTime.getNow());
	}

	@Test
	public void testGetCurrentTimeMillis() {
		DatashareTime.setMockTime(false);
		long datashareTimeMillis = DatashareTime.getInstance().currentTimeMillis();
		long systemTimeMillis = System.currentTimeMillis();
		Assert.assertTrue("we suppose there is less than 100ms between 2 lines of code",
				systemTimeMillis - datashareTimeMillis < 100L);
	}

	@Test
	public void testSleepInMockTime() throws Exception {
		Date now = new Date();
		DatashareTime.setMockTime(true);
		DatashareTime.getInstance().setMockDate(now);

		DatashareTime.getInstance().sleep(1000);
		assertThat(DatashareTime.getInstance().currentTimeMillis() - now.getTime()).isEqualTo(1000);
	}

	@Test
	public void testMockInstance() {
		Date date = DatashareDateUtils.formatDate("2020-02-20T12:13:14Z");

		System.setProperty(DatashareTime.DATASHARE_TIME_CLASS_PROPERTY, "org.icij.datashare.time.DatashareMockTime");
		Time time = DatashareTime.getInstance();
		Assert.assertTrue(time instanceof DatashareMockTime);

		// we check that we can change the date
		DatashareMockTime mockTime = (DatashareMockTime) time;
		mockTime.setMockDate(date);
		Assert.assertEquals(date, DatashareTime.getInstance().now());

		// we ckeck that we can change current system instance.
		System.setProperty(DatashareTime.DATASHARE_TIME_CLASS_PROPERTY, "org.icj.datashare.time.DatashareTime");
		// not equals because of introspection delay
		Assert.assertTrue(DatashareTime.getInstance().currentTimeMillis() - System.currentTimeMillis() < 10L);
	}

	@Test
	public void testSetMockDateInMockMode() {
		Date date = DatashareDateUtils.formatDate("2020-02-20T12:13:14Z");

		DatashareTime.setMockTime(true);
		DatashareTime.getInstance().setMockDate(date);
		Assert.assertEquals(date, DatashareTime.getInstance().now());
	}

	@Test
	public void testAddTimeMockMode() {
		Date now = new Date();
		DatashareTime.setMockTime(true);
		DatashareTime.getInstance().setMockDate(now);
		int timeToAddInMs=1000;

		DatashareTime.getInstance().addMilliseconds(timeToAddInMs);

		Date dateInTheFuture = DatashareDateUtils.addMilliseconds(now, timeToAddInMs);
		Assert.assertEquals(dateInTheFuture, DatashareTime.getInstance().now());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testSetMockDateInRealTime() {
		DatashareTime.setMockTime(false);
		DatashareTime.getInstance().setMockDate(new Date());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testSetMockDateStringInRealTime() {
		DatashareTime.setMockTime(false);
		DatashareTime.getInstance().setMockDate("une string");
	}

	@Test(expected = UnsupportedOperationException.class)
	public void testAddMillisecondsInRealTime() {
		DatashareTime.setMockTime(false);
		DatashareTime.getInstance().addMilliseconds(1);
	}

	@Test
	public void testSetMockTime() {
		DatashareTime.setMockTime(true);

		Assert.assertEquals(DatashareTime.getInstance().getClass(), DatashareMockTime.class);

		DatashareTime.setMockTime(false);

		Assert.assertEquals(DatashareTime.getInstance().getClass(), DatashareTime.class);
	}

	@Test
	public void testIsMockTime() {
		DatashareTime.setMockTime(true);

		Assert.assertTrue(DatashareTime.isMockTime());

		DatashareTime.setMockTime(false);

		Assert.assertFalse(DatashareTime.isMockTime());
	}

	@Test
	public void testGetSystemInstanceOnError() {
		System.setProperty(DatashareTime.DATASHARE_TIME_CLASS_PROPERTY, "class.unknown");
		Time time = DatashareTime.getInstance();
		Assert.assertTrue(time instanceof DatashareTime);
	}

	@Test
	public void testSetMockDateString() {
		final String dateStr = "2009-10-10T11:15:00Z";
		DatashareTime.setMockTime(true);

		DatashareTime.getInstance().setMockDate(dateStr);

		Assert.assertEquals(DatashareDateUtils.formatDate(dateStr),
				DatashareTime.getInstance().now());
	}

	@Test(expected = DateTimeParseException.class)
	public void testSetMockDateString_withBadFormat() {
		DatashareTime.setMockTime(true);
		DatashareTime.getInstance().setMockDate("2009-10-10");
	}

}
