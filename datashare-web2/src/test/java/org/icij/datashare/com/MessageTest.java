package org.icij.datashare.com;

import org.icij.datashare.com.Message.Field;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class MessageTest {
    @Test
    public void test_tojson() {
        assertThat(new Message().toJson()).endsWith("}");
        assertThat(new Message().toJson()).startsWith("{");
        assertThat(new Message().add(Field.DOC_ID, "my_doc_id").toJson()).contains("\"DOC_ID\":\"my_doc_id\"");
    }
}