package org.icij.datashare.com;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static org.icij.datashare.com.Message.Field.*;
import static org.joda.time.format.ISODateTimeFormat.dateTime;

public class Message {
    public enum Field {TYPE, DATE, DOC_ID}
    public enum Type {EXTRACT_NLP}
    final Date date = new Date();
    final Type type;
    public final Map<Field, String> content = new HashMap<>();

    public Message(final Type type) {this.type = type;}

    public String toJson() {
        return "{" + content.entrySet().stream().map(e -> format("\"%s\":\"%s\"", e.getKey(), e.getValue())).
                reduce(format("\"%s\":\"%s\",\"%s\":\"%s\"", TYPE, type, DATE, dateTime().print(date.getTime())),
                        (s1, s2) -> s1 + "," + s2) + "}";
    }

    public Message add(Field k, String v) {
        content.put(k, v);
        return this;
    }
}
