package org.icij.datashare;

import org.icij.datashare.text.StringUtils;

public class HumanReadableDuration {
    /**
     * format a duration given in hours into the largest natural unit:
     * whole days when it divides evenly, hours otherwise.
     *
     * @param hours: duration in hours
     * @return human readable duration like "1 day", "7 days", "36 hours"
     */
    public static String fromHours(int hours) {
        boolean isWholeNumberOfDays = hours > 0 && hours % 24 == 0;
        if (isWholeNumberOfDays) {
            int days = hours / 24;
            return StringUtils.pluralize(days, "day");
        }
        return StringUtils.pluralize(hours, "hour");
    }
}
