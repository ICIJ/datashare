package org.icij.datashare.com;

import org.icij.datashare.com.Message.Field;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.com.Message.Type.SHUTDOWN;
import static org.joda.time.format.ISODateTimeFormat.dateTime;

public class MessageTest {
    @Test
    public void test_tojson() {
        assertThat(new Message(EXTRACT_NLP).toJson()).endsWith("}");
        assertThat(new Message(EXTRACT_NLP).toJson()).startsWith("{");
        assertThat(new Message(EXTRACT_NLP).add(Field.DOC_ID, "my_doc_id").toJson()).contains("\"DOC_ID\":\"my_doc_id\"");
    }

    @Test
    public void test_from_map() throws Exception {
        Message message = new Message(new HashMap<String, String>() {{
            put("DATE", "2018-02-21T12:13:14.020Z");
            put("TYPE", "SHUTDOWN");
        }});
        assertThat(message).isEqualTo(
                new Message(SHUTDOWN, dateTime().parseDateTime("2018-02-21T12:13:14.020Z").toDate()));
    }
}