package org.icij.datashare.com;

import org.icij.datashare.com.Message.Field;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class MessageTest {
    @Test
    public void test_tojson() {
        assertThat(new Message(EXTRACT_NLP).toJson()).endsWith("}");
        assertThat(new Message(EXTRACT_NLP).toJson()).startsWith("{");
        assertThat(new Message(EXTRACT_NLP).add(Field.DOC_ID, "my_doc_id").toJson()).contains("\"DOC_ID\":\"my_doc_id\"");
    }
}