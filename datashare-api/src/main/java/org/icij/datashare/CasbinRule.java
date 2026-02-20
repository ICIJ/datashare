package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.List;

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
        this.v0 = v0;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
        this.v4 = v4;
        this.v5 = v5;
    }

    public static String escapeSingleRule(String rule) {
        return !rule.isEmpty() && (!rule.startsWith("\"") || !rule.endsWith("\"")) ? String.format("\"%s\"", rule) : rule;
    }

    public static CasbinRule escape(CasbinRule line) {
        return new CasbinRule(
                line.ptype,
                escapeSingleRule(line.v0),
                escapeSingleRule(line.v1),
                escapeSingleRule(line.v2),
                escapeSingleRule(line.v3),
                escapeSingleRule(line.v4),
                escapeSingleRule(line.v5)
        );
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

    @JsonCreator
    public static CasbinRule fromArray(List<String> arr) {
        return new CasbinRule(
                arr.size() > 0 ? arr.get(0) : "",
                arr.size() > 1 ? arr.get(1) : "",
                arr.size() > 2 ? arr.get(2) : "",
                arr.size() > 3 ? arr.get(3) : "",
                arr.size() > 4 ? arr.get(4) : "",
                arr.size() > 5 ? arr.get(5) : "",
                arr.size() > 6 ? arr.get(6) : ""
        );
    }
    public String[] toStringArray() {
        return new String[]{this.ptype, this.v0, this.v1, this.v2, this.v3, this.v4, this.v5};
    }
}