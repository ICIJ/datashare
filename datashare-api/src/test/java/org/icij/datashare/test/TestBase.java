package org.icij.datashare.test;

/**
 * Created by julien on 5/3/16.
 */
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.BeforeClass;

public abstract class TestBase {

    protected static final Logger logger = Logger.getLogger("datashare-test");

    @BeforeClass
    public static void setUpBeforeClass() {
        logger.setLevel(Level.INFO);
    }

}

