package top.sshh.bililiverecoder.util;

import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.alibaba.fastjson.util.TypeUtils;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class FastjsonWebhookDateDeserializer implements ObjectDeserializer {

    public static void registerGlobal() {
        com.alibaba.fastjson.parser.ParserConfig.getGlobalInstance()
                .putDeserializer(Date.class, new FastjsonWebhookDateDeserializer());
    }

    private static final DateTimeFormatter[] LOCAL_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        Object value = parser.parse();
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return (T) date;
        }
        if (value instanceof Number number) {
            return (T) new Date(number.longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            return (T) new Date(Long.parseLong(text));
        }
        try {
            return (T) Date.from(parseInstant(text));
        } catch (IllegalArgumentException e) {
            return (T) TypeUtils.castToDate(value);
        }
    }

    @Override
    public int getFastMatchToken() {
        return JSONToken.LITERAL_STRING;
    }

    private static Instant parseInstant(String text) {
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
        }
        for (DateTimeFormatter formatter : LOCAL_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter).atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Unsupported webhook date format: " + text);
    }
}
