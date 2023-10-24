package org.icij.datashare.cli;

import java.util.Properties;
import org.slf4j.LoggerFactory;

public interface FooService {
    void foo(Properties properties);

    class FooServiceImpl implements FooService {
        @Override
        public void foo(Properties properties) {
            LoggerFactory.getLogger(getClass()).info("{}", properties);
        }
    }
}
