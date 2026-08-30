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

        /**
         * Records one field, and refuses a key this object already carries.
         *
         * <p><b>Why this throws rather than overwriting.</b> {@code fields.put} took the second
         * value silently, and a review found a response that assembled a {@code cohortCount} in one
         * branch and again in another: the first figure was gone from the wire with nothing said,
         * and the endpoint reported a count it had computed and then discarded. That failure is
         * invisible in every test that asserts on the surviving value — which is the shape of defect
         * this codebase keeps finding — and it is worse here than in a general JSON library, because
         * the values being overwritten are control figures and refusal reasons.
         *
         * <p>An {@link IllegalStateException} rather than a refusal value, because there is no
         * caller who can compensate: a handler that names one key twice is a defect in the handler,
         * not a fact about the book, and 06's convention reserves 500 for exactly that. Insertion
         * order is untouched, which several assertions depend on.
         *
         * <p>To replace a field deliberately, build the object with the value you mean. There is no
         * overwrite method on purpose: the one legitimate use — a conditional field — is expressed
         * by choosing the value before the call, and an overwrite method would give the silent
         * behaviour a name and let it back in.
         */
        private Obj set(String key, String rendered) {
            Objects.requireNonNull(key, "key");
            String existing = fields.putIfAbsent(key, rendered);
            if (existing != null) {
                throw new IllegalStateException(
                    "field \"" + key + "\" is already set on this object to " + existing
                        + " and would be silently replaced by " + rendered
                        + "; a response that names one key twice publishes one of the two figures"
                        + " and discards the other with nothing said");
            }
            return this;
        }

        /** A string-valued field; the value is escaped. Null becomes JSON null. */
        public Obj str(String key, String value) {
            return set(key, value == null ? "null" : quote(value));
        }

        /** A figure. Emitted as a JSON string — see the class javadoc for why. */
        public Obj figure(String key, BigDecimal value) {
            return set(key, value == null ? "null" : quote(value.toPlainString()));
        }

        /** A count. The only numbers this API emits as JSON numbers. */
        public Obj count(String key, int value) {
            return set(key, Integer.toString(value));
        }

        public Obj bool(String key, boolean value) {
            return set(key, Boolean.toString(value));
        }

        public Obj obj(String key, Obj value) {
            return set(key, value == null ? "null" : value.toString());
        }

        /** An array of objects. */
        public Obj array(String key, List<Obj> values) {
            List<String> rendered = new ArrayList<>(values.size());
            for (Obj value : values) {
                rendered.add(value.toString());
            }
            return set(key, "[" + String.join(",", rendered) + "]");
        }

        /** An array of strings, each escaped. */
        public Obj strings(String key, List<String> values) {
            List<String> rendered = new ArrayList<>(values.size());
            for (String value : values) {
                rendered.add(quote(value));
            }
            return set(key, "[" + String.join(",", rendered) + "]");
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
