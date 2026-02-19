package org.icij.datashare;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CasbinRuleTest {

    @Test
    public void testEscapeQuotesValues() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "alice";
        rule.v1 = "data1";
        rule.v2 = "read";
        rule.v3 = "";
        rule.v4 = "";
        rule.v5 = "";

        CasbinRule escaped = CasbinRule.escape(rule);

        assertEquals("p", escaped.ptype); // ptype is NOT escaped in the constructor
        assertEquals("\"alice\"", escaped.v0);
        assertEquals("\"data1\"", escaped.v1);
        assertEquals("\"read\"", escaped.v2);
        assertEquals("", escaped.v3); // empty string should not be escaped
        assertEquals("", escaped.v4);
        assertEquals("", escaped.v5);
    }

    @Test
    public void testEscapeDoesNotDoubleQuote() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "\"alice\"";
        rule.v1 = "data1";
        rule.v2 = "";
        rule.v3 = "";
        rule.v4 = "";
        rule.v5 = "";

        CasbinRule escaped = CasbinRule.escape(rule);

        assertEquals("\"alice\"", escaped.v0);
        assertEquals("\"data1\"", escaped.v1);
    }

    @Test
    public void testToStringArray() {
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
    public void testGetLineText() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "p";
        rule.v0 = "alice";
        rule.v1 = "data1";
        rule.v2 = "read";
        rule.v3 = "";
        rule.v4 = "";
        rule.v5 = "";

        String lineText = CasbinRule.getLineText(rule);

        assertEquals("p, alice, data1, read", lineText);
    }

    @Test
    public void testGetLineTextAllValues() {
        CasbinRule rule = new CasbinRule();
        rule.ptype = "g";
        rule.v0 = "alice";
        rule.v1 = "data2";
        rule.v2 = "write";
        rule.v3 = "v3";
        rule.v4 = "v4";
        rule.v5 = "v5";

        String lineText = CasbinRule.getLineText(rule);

        assertEquals("g, alice, data2, write, v3, v4, v5", lineText);
    }
}
