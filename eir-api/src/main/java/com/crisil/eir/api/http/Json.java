package com.crisil.eir.api.http;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON writer, by hand, because Jackson is banned and this format is small enough to own.
 *
 * <p><b>The one thing this type exists to get right.</b> Every figure in this engine is a
 * {@link BigDecimal} or a {@link com.crisil.eir.domain.Money}, and JSON's number type is a
 * double in every browser that will read it. {@code 0.010421491800} through a double loses the
 * trailing zeros that say the rate is stated to twelve places, and a gross carrying amount of
 * 533914.11 becomes 533914.10999999997 often enough to be noticed in a control report. So
 * <b>every figure is emitted as a JSON string</b>, never as a JSON number, and
 * {@link #number(BigDecimal)} exists only for counts, which are ints.
 *
 * <p>That is not a workaround. The UI formats and displays these figures; it never computes with
 * them, and it must not be able to. A string is the type that says so.
 *
 * <p>Escaping covers what an EIR API can actually emit: quote, backslash, the two line breaks, tab,
 * and any remaining control character as {@code \\uXXXX}. Invariant details and refusal reasons are
 * long prose sentences carrying contract ids and rupee figures, and one unescaped newline in a
 * refusal is a page that renders nothing.
 */
public final class Json {

    private Json() {
    }

    /** A JSON object, written in insertion order so a response diff is readable. */
    public static final class Obj {
        private final Map<String, String> fields = new LinkedHashMap<>();

        /** A string-valued field; the value is escaped. Null becomes JSON null. */
        public Obj str(String key, String value) {
            fields.put(key, value == null ? "null" : quote(value));
            return this;
        }

        /** A figure. Emitted as a JSON string — see the class javadoc for why. */
        public Obj figure(String key, BigDecimal value) {
            fields.put(key, value == null ? "null" : quote(value.toPlainString()));
            return this;
        }

        /** A count. The only numbers this API emits as JSON numbers. */
        public Obj count(String key, int value) {
            fields.put(key, Integer.toString(value));
            return this;
        }

        public Obj bool(String key, boolean value) {
            fields.put(key, Boolean.toString(value));
            return this;
        }

        public Obj obj(String key, Obj value) {
            fields.put(key, value == null ? "null" : value.toString());
            return this;
        }

        /** An array of objects. */
        public Obj array(String key, List<Obj> values) {
            List<String> rendered = new ArrayList<>(values.size());
            for (Obj value : values) {
                rendered.add(value.toString());
            }
            fields.put(key, "[" + String.join(",", rendered) + "]");
            return this;
        }

        /** An array of strings, each escaped. */
        public Obj strings(String key, List<String> values) {
            List<String> rendered = new ArrayList<>(values.size());
            for (String value : values) {
                rendered.add(quote(value));
            }
            fields.put(key, "[" + String.join(",", rendered) + "]");
            return this;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                out.append(quote(field.getKey())).append(':').append(field.getValue());
                first = false;
            }
            return out.append('}').toString();
        }
    }

    public static Obj object() {
        return new Obj();
    }

    /** A bare number, for the rare top-level count. */
    public static String number(BigDecimal value) {
        return Objects.requireNonNull(value, "value").toPlainString();
    }

    /**
     * One JSON string literal, escaped.
     *
     * <p>Iterates code units rather than code points on purpose: a surrogate pair passes through as
     * its two units, which is valid JSON and is what a UTF-8 response body wants. The approver
     * names in this system can be non-ASCII — {@code RoutingTableFormat} has a test for exactly
     * that — so nothing here may assume Latin-1.
     */
    public static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8).append('"');
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
