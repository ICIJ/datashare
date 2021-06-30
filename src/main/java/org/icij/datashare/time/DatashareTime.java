package org.icij.datashare.time;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.AccessControlException;
import java.util.Date;

/**
 * Time provider for being able to test time based routines.
 * The class is given in the DATASHARE_TIME_CLASS_PROPERTY.
 *
 * @see #getInstance()
 *
 * <p>Every access to current time or current date shoud call this service,
 * to be able to override its behaviour for test purpose.</p>
 */
public class DatashareTime implements Time {

    private static final Logger logger = LoggerFactory.getLogger(DatashareTime.class);
    /** property key for "org.icij.datashare.time.class" */
    public static final String DATASHARE_TIME_CLASS_PROPERTY = "datashare.time";
    private static final Time defaultInstance;
    private static Time systemPropertyInstance;

    static {
        defaultInstance = new DatashareTime();
    }
    private DatashareTime() {}

    /**
     * Singleton instance method.
     *
     * DATASHARE_TIME_CLASS_PROPERTY is defined in the JVM.
     *
     * this method returns an instance of the class provided in the property.
     *
     * @see #DATASHARE_TIME_CLASS_PROPERTY
     * <p>
     * if class cannot be loaded by class loader or no default constructor
     * is available, default time provider is returned.
     * </p>
     * @return <code>Time</code> datashare time provider
     */
    public static Time getInstance() {
        String datashareTimeClassName;
        try {
            datashareTimeClassName = System.getProperty(DATASHARE_TIME_CLASS_PROPERTY);
        } catch (AccessControlException e) {
            datashareTimeClassName = null;
        }
        if (datashareTimeClassName != null) {
            try {
                @SuppressWarnings("rawtypes")
				Class datashareMockTimeClass = Class.forName(datashareTimeClassName);
                if (systemPropertyInstance == null
                        || !datashareTimeClassName.equals(systemPropertyInstance.getClass().getName())) {
                    systemPropertyInstance = (Time) datashareMockTimeClass.newInstance();
                    if (logger.isDebugEnabled()) {
                        logger.debug("time instance for datashare : <" + datashareTimeClassName + ">");
                    }
                }
                return systemPropertyInstance;
            } catch (ClassNotFoundException | InstantiationException e) {
                logErrorInstance(datashareTimeClassName);
            } catch (IllegalAccessException e) {
                logger.warn("No empty constructor for : <" + datashareTimeClassName + "> returning default instance");
            }
        }
        return defaultInstance;
    }

    /** returns system time */
    public Date now() {
        return new Date();
    }
    public static Date getNow() {
    	return getInstance().now();
    }

    /**
     * returns System.currentTimeMillis()
     * @see System#currentTimeMillis()
     */
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public void setMockDate(Date date) {
        throw new UnsupportedOperationException("DatashareTime is in real time mode");
    }

    @Override
    public void sleep(int milliseconds) throws InterruptedException {
        Thread.sleep(milliseconds);
    }

    public void setMockDate(String dateTime) {
        throw new UnsupportedOperationException("DatashareTime is in real time mode");
	}

	public void addMilliseconds(int toBeAddedInMilliseconds) {
        throw new UnsupportedOperationException("DatashareTime is in real time mode");
	}

    private static void logErrorInstance(String datashareTimeClassName) {
        logger.error("Cannot instantiate class : <" + datashareTimeClassName+ "> returning default instance");
    }

    public static void setMockTime(boolean mockTime) {
        if (mockTime) {
            System.setProperty(DATASHARE_TIME_CLASS_PROPERTY, DatashareMockTime.class.getCanonicalName());
        } else {
            System.clearProperty(DATASHARE_TIME_CLASS_PROPERTY);
        }
    }

    public static boolean isMockTime() {
        return System.getProperty(DATASHARE_TIME_CLASS_PROPERTY) != null;
    }

	public Date itIsNow(String date) {
		throw new UnsupportedOperationException("DatashareTime is in real time mode");
	}

}
