package org.icij.datashare;

public class CasbinRule {
    public String ptype;
    public String v0;
    public String v1;
    public String v2;
    public String v3;
    public String v4;
    public String v5;

    public CasbinRule() {
    }

    public CasbinRule(String ptype, String v0, String v1, String v2, String v3, String v4, String v5) {
        this.ptype = ptype;
        this.v0 = escapeSingleRule(v0);
        this.v1 = escapeSingleRule(v1);
        this.v2 = escapeSingleRule(v2);
        this.v3 = escapeSingleRule(v3);
        this.v4 = escapeSingleRule(v4);
        this.v5 = escapeSingleRule(v5);
    }

    public static String escapeSingleRule(String rule) {
        return !rule.isEmpty() && (!rule.startsWith("\"") || !rule.endsWith("\"")) ? String.format("\"%s\"", rule) : rule;
    }

    public static CasbinRule escape(CasbinRule line) {
        return new CasbinRule(line.ptype, line.v0, line.v1, line.v2, line.v3, line.v4, line.v5);
    }

    public static String getLineText(CasbinRule escapedLine) {
        String lineText = escapedLine.ptype;
        if (!"".equals(escapedLine.v0)) {
            lineText = lineText + ", " + escapedLine.v0;
        }
        if (!"".equals(escapedLine.v1)) {
            lineText = lineText + ", " + escapedLine.v1;
        }
        if (!"".equals(escapedLine.v2)) {
            lineText = lineText + ", " + escapedLine.v2;
        }
        if (!"".equals(escapedLine.v3)) {
            lineText = lineText + ", " + escapedLine.v3;
        }
        if (!"".equals(escapedLine.v4)) {
            lineText = lineText + ", " + escapedLine.v4;
        }
        if (!"".equals(escapedLine.v5)) {
            lineText = lineText + ", " + escapedLine.v5;
        }
        return lineText;
    }

    public String[] toStringArray() {
        return new String[]{this.ptype, this.v0, this.v1, this.v2, this.v3, this.v4, this.v5};
    }
}