package org.icij.datashare.policies;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CasbinRuleTest {

    @Test
    public void test_escape_quotes_values() {
        CasbinRule rule = new CasbinRule("p", "PROJECT_ADMIN", "icij", "banana-papers", "p_admin", "", "");

        CasbinRule escaped = CasbinRule.escape(rule);

        assertEquals("p", escaped.getPtype()); // ptype is NOT escaped in the constructor
        assertEquals("\"PROJECT_ADMIN\"", escaped.getV0());
        assertEquals("\"icij\"", escaped.getV1());
        assertEquals("\"banana-papers\"", escaped.getV2());
        assertEquals("\"p_admin\"", escaped.getV3());
        assertEquals("", escaped.getV4()); // empty string should not be escaped
        assertEquals("", escaped.getV5());
    }

    @Test
    public void test_escape_does_not_double_quote() {
        CasbinRule rule = new CasbinRule("p", "\"PROJECT_ADMIN\"", "icij", "", "", "", "");

        CasbinRule escaped = CasbinRule.escape(rule);

        assertEquals("\"PROJECT_ADMIN\"", escaped.getV0());
        assertEquals("\"icij\"", escaped.getV1());
    }

    @Test
    public void test_to_string_array() {
        CasbinRule rule = new CasbinRule("p", "v0", "v1", "v2", "v3", "v4", "v5");

        String[] array = rule.toStringArray();

        assertArrayEquals(new String[]{"p", "v0", "v1", "v2", "v3", "v4", "v5"}, array);
    }

    @Test
    public void test_get_line_text() {
        CasbinRule rule = new CasbinRule("p", "PROJECT_ADMIN", "icij", "banana-papers", "p_admin", "", "");

        String lineText = CasbinRule.getLineText(rule);

        assertEquals("p, PROJECT_ADMIN, icij, banana-papers, p_admin", lineText);
    }

    @Test
    public void test_get_line_text_all_values() {
        CasbinRule rule = new CasbinRule("g", "alice", "domain1", "project1", "PROJECT_ADMIN", "v4", "v5");

        String lineText = CasbinRule.getLineText(rule);

        assertEquals("g, alice, domain1, project1, PROJECT_ADMIN, v4, v5", lineText);
    }
}
