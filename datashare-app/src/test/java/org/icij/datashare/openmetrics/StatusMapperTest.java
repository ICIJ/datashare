package org.icij.datashare.openmetrics;

import org.icij.datashare.test.DatashareTimeRule;
import org.junit.Rule;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class StatusMapperTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-06-30T15:31:00Z");
    @Test
    public void test_null() {
        assertThat(new StatusMapper(null, null).toString()).isEmpty();
    }

    @Test
    public void test_to_string_one_string_field() {
        class StringStatus { String field = "value";}
        assertThat(new StatusMapper("metric_name", new StringStatus()).toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{resource=\"field\"} Nan 1593531060000\n");
    }

    @Test
    public void test_to_string_one_int_field() {
        class IntStatus { int field = 123;}
        assertThat(new StatusMapper("metric_name", new IntStatus()).toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{resource=\"field\"} 123 1593531060000\n");
    }

    @Test
    public void test_to_string_one_Integer_field() {
        class IntStatus { Integer field = 456;}
        assertThat(new StatusMapper("metric_name", new IntStatus()).toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{resource=\"field\"} 456 1593531060000\n");
    }

    @Test
    public void test_to_string_with_platform() {
        class StringStatus { String field = "value";}
        assertThat(new StatusMapper("metric_name", new StringStatus(), "platform").toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{environment=\"platform\",resource=\"field\"} Nan 1593531060000\n");
    }

    @Test
    public void test_to_string_one_boolean_field() {
        class BooleanStatus { boolean field = true;}
        assertThat(new StatusMapper("metric_name", new BooleanStatus()).toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{status=\"OK\",resource=\"field\"} 1 1593531060000\n");
    }

    @Test
    public void test_to_string_one_Boolean_field() {
        class BooleanStatus { Boolean field = false;}
        assertThat(new StatusMapper("metric_name", new BooleanStatus()).toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{status=\"KO\",resource=\"field\"} 0 1593531060000\n");
    }

    @Test
    public void test_to_string_two_fields() {
        class BooleanStatus { Boolean field1 = true;Integer field2 = 3; }
        assertThat(new StatusMapper("metric_name", new BooleanStatus()).toString()).contains("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE metric_name gauge\n" +
                "metric_name{status=\"OK\",resource=\"field1\"} 1 1593531060000\n" +
                "metric_name{resource=\"field2\"} 3 1593531060000\n");
    }


}
