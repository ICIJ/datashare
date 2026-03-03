package org.icij.datashare.policies;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CasbinRuleTest {

    @Test
    public void test_escape_quotes_values() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "PROJECT_ADMIN";
        rule.v1 = "icij";
        rule.v2 = "banana-papers";
        rule.v3 = "p_admin";
        rule.v4 = "";
        rule.v5 = "";

        CasbinRule escaped = CasbinRule.escape(rule);

        assertEquals("p", escaped.ptype); // ptype is NOT escaped in the constructor
        assertEquals("\"PROJECT_ADMIN\"", escaped.v0);
        assertEquals("\"icij\"", escaped.v1);
        assertEquals("\"banana-papers\"", escaped.v2);
        assertEquals("\"p_admin\"", escaped.v3);
        assertEquals("", escaped.v4); // empty string should not be escaped
        assertEquals("", escaped.v5);
    }

    @Test
    public void test_escape_does_not_double_quote() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "\"PROJECT_ADMIN\"";
        rule.v1 = "icij";
        rule.v2 = "";
        rule.v3 = "";
        rule.v4 = "";
        rule.v5 = "";

        CasbinRule escaped = CasbinRule.escape(rule);

        assertEquals("\"PROJECT_ADMIN\"", escaped.v0);
        assertEquals("\"icij\"", escaped.v1);
    }

    @Test
    public void test_to_string_array() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "v0";
        rule.v1 = "v1";
        rule.v2 = "v2";
        rule.v3 = "v3";
        rule.v4 = "v4";
        rule.v5 = "v5";

        String[] array = rule.toStringArray();

        assertArrayEquals(new String[]{"p", "v0", "v1", "v2", "v3", "v4", "v5"}, array);
    }

    @Test
    public void test_get_line_text() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "PROJECT_ADMIN";
        rule.v1 = "icij";
        rule.v2 = "banana-papers";
        rule.v3 = "p_admin";
        rule.v4 = "";
        rule.v5 = "";

        String lineText = CasbinRule.getLineText(rule);

        assertEquals("p, PROJECT_ADMIN, icij, banana-papers, p_admin", lineText);
    }

    @Test
    public void test_get_line_text_all_values() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "g";
        rule.v0 = "alice";
        rule.v1 = "domain1";
        rule.v2 = "project1";
        rule.v3 = "PROJECT_ADMIN";
        rule.v4 = "v4";
        rule.v5 = "v5";

        String lineText = CasbinRule.getLineText(rule);

        assertEquals("g, alice, domain1, project1, PROJECT_ADMIN, v4, v5", lineText);
    }
}
