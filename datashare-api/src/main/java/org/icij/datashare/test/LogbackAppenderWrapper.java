package org.icij.datashare.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Context;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class LogbackAppenderWrapper {
    public final Logger logger;
    private final TestAppender appender;

    public LogbackAppenderWrapper() {
        appender = new TestAppender();
        logger = createLogger(appender);
    }

    List<String> logs(Level level) {
        return appender.events.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    public List<String> logs(org.slf4j.event.Level level) {
        return logs(Level.valueOf(level.toString()));
    }

    public List<String> logs() {
        return appender.events.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
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

    public void reset() {
        appender.reset();
    }

    private static class TestAppender extends AppenderBase<ILoggingEvent> {
        Queue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

        @Override
        protected void append(ILoggingEvent iLoggingEvent) {
            events.add(iLoggingEvent);
        }
        void reset() {
            events.clear();
        }
    }
}
