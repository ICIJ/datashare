package org.icij.datashare.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Context;
import org.junit.rules.ExternalResource;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class LogbackCapturingRule extends ExternalResource {
    public Logger logger;
    TestAppender appender;
    @Override
    protected void before() throws Throwable {
        appender = new TestAppender();
        logger = createLogger(appender);
    }

    @Override
    protected void after() {
        appender.reset();
    }

    public List<String> logs(Level level) {
        return appender.events.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private static class TestAppender extends AppenderBase<ILoggingEvent> {
        List<ILoggingEvent> events = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent iLoggingEvent) {
            events.add(iLoggingEvent);
        }
        void reset() {
            events.clear();
        }
    }

    private static Logger createLogger(AppenderBase<ILoggingEvent> appender) {
        ILoggerFactory lc = LoggerFactory.getILoggerFactory();
        PatternLayoutEncoder ple = new PatternLayoutEncoder();

        ple.setPattern("%d [%thread] %-5level %logger{0} - %msg%n");
        ple.setContext((Context) lc);
        ple.start();
        appender.setContext((Context) lc);
        appender.start();

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(true);

        return logger;
    }
}
