package org.icij.datashare.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.DurationDeserializer;
import java.io.IOException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a {@link Duration} from the "human" form the CLI uses ({@code 30m}, {@code 24h}, {@code 500ms}; no unit
 *  means milliseconds, as {@code org.icij.time.HumanDuration}), and from everything jsr310 reads (ISO-8601, numbers). */
class HumanDurationDeserializer extends StdDeserializer<Duration> {
    private static final Pattern HUMAN = Pattern.compile("(\\d+)(ms|d|h|m|s)?", Pattern.CASE_INSENSITIVE);

    HumanDurationDeserializer() {
        super(Duration.class);
    }

    @Override
    public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            Matcher matcher = HUMAN.matcher(parser.getText().trim());
            if (matcher.matches()) {
                long value = Long.parseLong(matcher.group(1));
                String unit = matcher.group(2) == null ? "ms" : matcher.group(2).toLowerCase();
                return switch (unit) {
                    case "d" -> Duration.ofDays(value);
                    case "h" -> Duration.ofHours(value);
                    case "m" -> Duration.ofMinutes(value);
                    case "s" -> Duration.ofSeconds(value);
                    default -> Duration.ofMillis(value);
                };
            }
        }
        return DurationDeserializer.INSTANCE.deserialize(parser, context);
    }
}
