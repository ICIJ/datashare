package org.icij.datashare.com;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.lang.String.format;
import static org.icij.datashare.com.Message.Field.*;
import static org.joda.time.format.ISODateTimeFormat.dateTime;

public class Message {
    public enum Field {TYPE, DATE, DOC_ID, R_ID, VALUE}
    public enum Type {SHUTDOWN, INIT_MONITORING, EXTRACT_NLP}

    public final Date date;
    public final Type type;
    public final Map<Field, String> content = new HashMap<>();

    public Message(final HashMap map) {
        HashMap clone = (HashMap) map.clone();
        this.type = Type.valueOf((String) clone.remove(TYPE.name()));
        this.date = dateTime().parseDateTime((String) clone.remove(DATE.name())).toDate();
        this.addAll(clone);
    }

    public Message(final Type type) {this.type = type; this.date = new Date();}
    public Message(final Type type, final Date date) {this.type = type; this.date = date;}

    public String toJson() {
        return "{" + content.entrySet().stream().map(e -> format("\"%s\":\"%s\"", e.getKey(), e.getValue())).
                reduce(format("\"%s\":\"%s\",\"%s\":\"%s\"", TYPE, type, DATE, dateTime().print(date.getTime())),
                        (s1, s2) -> s1 + "," + s2) + "}";
    }

    public Message add(Field k, String v) {
        content.put(k, v);
        return this;
    }
    public Message addAll(HashMap result) {
        result.forEach((key, value) -> content.put(Field.valueOf((String)key), (String)value));
        return this;
    }

    @Override
    public String toString() { return "Message{date=" + date + ", type=" + type + ", content=" + content + '}';}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return Objects.equals(date, message.date) &&
                type == message.type &&
                Objects.equals(content, message.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, type, content);
    }
}
