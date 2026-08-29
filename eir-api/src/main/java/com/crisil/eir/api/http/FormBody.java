package com.crisil.eir.api.http;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Request bodies, read as {@code application/x-www-form-urlencoded} rather than as JSON.
 *
 * <p><b>Why not JSON in, when JSON goes out.</b> Writing JSON by hand is a hundred lines and cannot
 * be wrong in a way that matters — the writer controls its own output. Parsing JSON by hand is a
 * tokeniser, a number grammar and a string-escape state machine, and every one of those is a place
 * for a hand-rolled parser to accept something it should not. The inputs this API actually takes are
 * flat: a period id, a contract id, a principal, a rate, a name. Form encoding carries flat
 * key-value pairs, the JDK decodes it, and there is no parser to get wrong.
 *
 * <p>Every accessor refuses rather than defaults. A missing period id is not zero and a blank
 * approver is not "unknown": both are the caller having sent an incomplete request, and this engine
 * does not default an input it was not given.
 */
public final class FormBody {

    private final Map<String, String> values;

    private FormBody(Map<String, String> values) {
        this.values = values;
    }

    public static FormBody parse(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body != null && !body.isBlank()) {
            for (String pair : body.split("&")) {
                int split = pair.indexOf('=');
                if (split < 0) {
                    continue;
                }
                values.put(
                    URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return new FormBody(values);
    }

    /** A required text field, stripped; blank counts as absent. */
    public String text(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new BadRequest("'" + key + "' is required and arrived "
                + (value == null ? "absent" : "blank"));
        }
        return value.strip();
    }

    /** An optional text field, stripped, or the fallback where absent or blank. */
    public String textOr(String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** A required integer field. */
    public int integer(String key) {
        String raw = text(key);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            throw new BadRequest("'" + key + "' must be a whole number, got '" + raw + "'");
        }
    }

    /**
     * A required decimal field, as a {@link BigDecimal} and never through a double.
     *
     * <p>The scale the caller typed is preserved, because it is information: a principal entered as
     * {@code 1000000.00} is stated in paise and {@code 1000000} is not, and {@code Money} has an
     * opinion about the difference.
     */
    public BigDecimal decimal(String key) {
        String raw = text(key);
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException notANumber) {
            throw new BadRequest("'" + key + "' must be a decimal figure, got '" + raw + "'");
        }
    }

    /** Whether the field was sent at all, whatever its value. */
    public boolean has(String key) {
        String value = values.get(key);
        return value != null && !value.isBlank();
    }

    /** A malformed request, distinguished from an engine refusal — see EirServer for why. */
    public static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BadRequest(String message) {
            super(Objects.requireNonNull(message, "message"));
        }
    }
}
