package org.icij.datashare.policies;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.List;

public class CasbinRule {
    private final String ptype;
    private final String v0;
    private final String v1;
    private final String v2;
    private final String v3;
    private final String v4;
    private final String v5;

    private CasbinRule() {
        this.ptype = "";
        this.v0 = "";
        this.v1 = "";
        this.v2 = "";
        this.v3 = "";
        this.v4 = "";
        this.v5 = "";
    }

    public CasbinRule(String ptype, String... values) {
        this.ptype = ptype;
        this.v0 = values.length > 0 ? values[0] : "";
        this.v1 = values.length > 1 ? values[1] : "";
        this.v2 = values.length > 2 ? values[2] : "";
        this.v3 = values.length > 3 ? values[3] : "";
        this.v4 = values.length > 4 ? values[4] : "";
        this.v5 = values.length > 5 ? values[5] : "";
    }

    public String getPtype() {
        return ptype;
    }

    public String getV0() {
        return v0;
    }

    public String getV1() {
        return v1;
    }

    public String getV2() {
        return v2;
    }

    public String getV3() {
        return v3;
    }

    public String getV4() {
        return v4;
    }

    public String getV5() {
        return v5;
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
        if (arr.isEmpty()) return new CasbinRule();
        String ptype = arr.get(0);
        String[] values = arr.subList(1, arr.size()).toArray(new String[0]);
        return new CasbinRule(ptype, values);
    }
    public String[] toStringArray() {
        return new String[]{this.ptype, this.v0, this.v1, this.v2, this.v3, this.v4, this.v5};
    }
}