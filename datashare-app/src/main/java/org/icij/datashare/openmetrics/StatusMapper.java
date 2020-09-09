package org.icij.datashare.openmetrics;

import org.icij.datashare.time.DatashareTime;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class StatusMapper {
    private final String metricName;
    private final Object status;
    private final String environment;

    public StatusMapper(String metricName, Object status) {
        this(metricName, status, null);
    }

    public StatusMapper(String metricName, Object status, String environment) {
        this.metricName = metricName;
        this.status = status;
        this.environment = environment == null?"": String.format("environment=\"%s\" ", environment);
    }

    @Override
    public String toString() {
        if (status == null) return "";
        String header = "# HELP datashare The datashare resources status\n" + String.format("# TYPE %s gauge\n", metricName);
        List<Field> declaredFields = Arrays.stream(this.status.getClass().getDeclaredFields()).filter(f -> !f.getName().startsWith("this")).collect(Collectors.toList());

        StringBuilder fieldLines = new StringBuilder();
        for (Field field : declaredFields) {
            try {
                Object value = field.get(status);
                Object numberValue = value;
                String status = "";
                if (String.class.equals(field.getType())) {
                    numberValue = "Nan";
                } else if (boolean.class.equals(field.getType()) || Boolean.class.equals(field.getType())) {
                    numberValue = (Boolean) value ? 1 : 0;
                    status = String.format("status=\"%s\" ", (Boolean) value ? "OK" : "KO");
                }
                fieldLines.append(String.format("%s{%s%sresource=\"%s\"} %s %d\n", metricName, environment, status, field.getName(), numberValue, DatashareTime.getInstance().currentTimeMillis()));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        return header + fieldLines;
    }
}
