package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
public class TaskError implements Serializable {
    final String name;
    final String message;
    final String cause;
    final List<StacktraceItem> stacktrace;

    @JsonCreator
    public TaskError(@JsonProperty("name") String name,
                     @JsonProperty("message") String message,
                     @JsonProperty("cause") String cause,
                     @JsonProperty("stacktrace") List<StacktraceItem> stacktrace) {
        super();
        this.name = name;
        this.message = message;
        this.cause = cause;
        this.stacktrace = stacktrace;
    }

    public TaskError(Throwable throwable) {
        this(throwable.getClass().getName(), throwable.getMessage(),
                ofNullable(throwable.getCause()).map(c -> format("%s: %s",
                                throwable.getCause().getClass().getName(),
                                throwable.getCause().getMessage())).orElse(null), stream(throwable.getStackTrace()).map(se ->
                        {
                            String name = se.isNativeMethod() ? se.getClassName(): format("%s.%s", se.getClassName(), se.getMethodName());
                            return new StacktraceItem(se.getFileName(), se.getLineNumber(), name);
                        }).collect(Collectors.toList()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskError that = (TaskError) o;
        return Objects.equals(name, that.name) && Objects.equals(message, that.message) && Objects.equals(cause, that.cause) && Objects.equals(stacktrace, that.stacktrace);
    }

    @Override
    public String toString() {
        StringBuilder formattedString = new StringBuilder();
        formattedString.append(name).append(": ").append(message);
        stacktrace.forEach(s -> formattedString.append(s).append('\n'));
        ofNullable(cause).ifPresent(s -> formattedString.append('\n').append(cause));
        return formattedString.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, message, cause, stacktrace);
    }

    public String getMessage() {
        return message;
    }

    public static class StacktraceItem {
        final String file;
        final int lineno;
        final String name;

        @JsonCreator
        public StacktraceItem(@JsonProperty("file") String file,
                              @JsonProperty("lineno") int lineno,
                              @JsonProperty("name") String name) {
            this.file = file;
            this.lineno = lineno;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StacktraceItem that = (StacktraceItem) o;
            return lineno == that.lineno && Objects.equals(file, that.file) && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, lineno, name);
        }

        @Override
        public String toString() {
            return "\tat " + name + (lineno < 0 ? " (native method)": ":" + lineno);
        }
    }
}
