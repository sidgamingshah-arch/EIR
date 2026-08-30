package com.crisil.eir.api.modules.runs;

import com.crisil.eir.api.http.Json;
import java.util.Objects;

/**
 * Reads a handful of named fields back out of a response this module's own writer produced.
 *
 * <p><b>Why this exists, stated plainly, because it looks like the wrong thing.</b> The endpoints of
 * 06 § 4 are a second face on figures {@code EirService} already assembles: the close gate's verdict
 * is put together by {@code RunClose.present} and rendered by {@code EirService.close}, and the
 * instruction for this unit is to <em>reuse</em> that rather than re-derive it. But
 * {@code EirService} hands back a rendered {@link Json.Obj} and nothing else — no accessor for
 * {@code mayClose}, no {@code ClosePresentation} — and {@code POST /periods/{id}/close} has to know
 * whether the gate refused, because that is the whole difference between a 200 and the 409 that 06
 * § 4 requires. Re-deriving the verdict here would mean a second close gate at the edge, which is
 * the one thing worse than reading the field back.
 *
 * <p><b>This is not a JSON parser and must never become one.</b> It reads named fields out of
 * output produced by {@link Json.Obj} — a writer whose format is fixed and whose every value is
 * escaped, which is what makes a plain {@code indexOf} exact here and would make it a bug on
 * arbitrary JSON. Two properties do the work:
 *
 * <ul>
 *   <li>{@code Json.quote} escapes every {@code "} inside a value as {@code \"}, so an unescaped
 *       quote in the rendered text is always a delimiter and never content. A refusal sentence
 *       containing the literal text {@code "mayClose":false} renders as {@code \"mayClose\":false}
 *       and cannot be mistaken for the field.</li>
 *   <li>Figures cross the wire as JSON <em>strings</em>, counts as JSON numbers and flags as JSON
 *       booleans (see {@code Json}'s class note), so {@link #count} cannot read a rupee figure as a
 *       count and {@link #flag} cannot read the four characters {@code true} inside a sentence as a
 *       flag.</li>
 * </ul>
 *
 * <p><b>The value's shape is part of the lookup, and that is not a convenience.</b> A run's response
 * carries {@code "computed":2} — how many contracts computed — and, in every per-contract row,
 * {@code "computed":true}. Reading "the first {@code computed}" would be reading whichever the writer
 * happened to emit first, which is a population figure decided by iteration order. So a lookup names
 * the shape it wants and matches only occurrences of that shape: exactly one {@code computed} in that
 * body is a number, and if a second ever appears this refuses rather than picks.
 *
 * <p><b>Every accessor throws where the field is absent, duplicated, or the wrong shape.</b> That is
 * the point of the type. A {@code flag} that defaulted to {@code false} on a missing field would
 * report "the gate refused" for a body that never said so — a 409 nobody can explain — and a
 * {@code flag} defaulting to {@code true} would close a period over a refusal. Both are worse than
 * a 500 that names the field, because a 500 gets fixed. The failing input is a response shape that
 * changed under this module: {@code EirService.close} dropping {@code mayClose}, or gaining a nested
 * object that repeats the name. {@code RunsAndPeriodsModuleTest} constructs both.
 */
public final class JsonView {

    private final String rendered;

    private JsonView(String rendered) {
        this.rendered = Objects.requireNonNull(rendered, "rendered");
    }

    /** A view over a response object this module is about to nest or read. */
    public static JsonView of(Json.Obj body) {
        return new JsonView(Objects.requireNonNull(body, "body").toString());
    }

    /**
     * A view over already-rendered text.
     *
     * <p>Published for the tests, which need to present this type the malformed bodies a live
     * {@code EirService} will not produce today — that is the only way the throwing paths above are
     * ever exercised.
     */
    public static JsonView ofRendered(String rendered) {
        return new JsonView(rendered);
    }

    /** The text this view reads, for nesting or for an error message. */
    public String rendered() {
        return rendered;
    }

    /**
     * A boolean field, as written by {@code Json.Obj.bool}.
     *
     * <p>Refuses {@code "key":"true"} — a quoted flag is a string field that happens to spell a
     * boolean, and reading it as one is how a figure emitted as a string would become a number.
     */
    public boolean flag(String key) {
        int at = valueStart(key, Shape.BOOLEAN);
        return rendered.startsWith("true", at);
    }

    /**
     * An integer field, as written by {@code Json.Obj.count}.
     *
     * <p>The only numbers this API emits unquoted are counts, so this cannot silently read a money
     * figure: {@code "deviation":"5298.16"} does not match a digit at the value position.
     */
    public int count(String key) {
        int at = valueStart(key, Shape.NUMBER);
        int end = at;
        if (rendered.charAt(end) == '-') {
            end++;
        }
        while (end < rendered.length() && Character.isDigit(rendered.charAt(end))) {
            end++;
        }
        return Integer.parseInt(rendered, at, end, 10);
    }

    /**
     * A string field, unescaped — the inverse of {@code Json.quote}.
     *
     * <p>Unescaping matters for exactly one field in this module and it is the one that carries the
     * gate's answer: {@code gateVerdict} is {@code CloseDecision.describe()}, which is one sentence
     * followed by one line per refusal, so it arrives with an escaped line break per gate that
     * refused. Read and re-emitted through {@code Json.Obj.str} it round-trips to the same bytes;
     * read naively it would put a raw newline in a response body, which is the defect {@code Json}'s
     * own class note says renders a console blank.
     */
    public String text(String key) {
        int at = valueStart(key, Shape.STRING);
        StringBuilder value = new StringBuilder();
        int index = at + 1;
        while (index < rendered.length()) {
            char ch = rendered.charAt(index);
            if (ch == '"') {
                return value.toString();
            }
            if (ch != '\\') {
                value.append(ch);
                index++;
                continue;
            }
            if (index + 1 >= rendered.length()) {
                break;
            }
            char escaped = rendered.charAt(index + 1);
            index += 2;
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (index + 4 > rendered.length()) {
                        throw malformed(key, "a complete \\uXXXX escape", index);
                    }
                    value.append((char) Integer.parseInt(rendered, index, index + 4, 16));
                    index += 4;
                }
                default -> throw malformed(key, "a JSON escape, not \\" + escaped, index);
            }
        }
        throw malformed(key, "a terminated string", at);
    }

    /**
     * How many times a key appears — the way a repeated field is counted.
     *
     * <p>This is how the number of rows in an array of objects is read, and it is deliberately keyed
     * on a <b>key</b> rather than on a delimiter: counting commas or braces would count the ones
     * inside {@code "5,298.16"} and inside every refusal sentence, and a control report off by the
     * number of commas in its own prose is worse than no count. A key cannot appear inside a value,
     * for the reason in the class note.
     *
     * @param key a field emitted once per row of the array being counted
     */
    public int occurrencesOf(String key) {
        String needle = needleFor(key);
        int total = 0;
        int at = rendered.indexOf(needle);
        while (at >= 0) {
            total++;
            at = rendered.indexOf(needle, at + needle.length());
        }
        return total;
    }

    /** How many times a key carries a given rendered value, e.g. {@code "satisfied":false}. */
    public int occurrencesOf(String key, String renderedValue) {
        String needle = needleFor(key) + renderedValue;
        int total = 0;
        int at = rendered.indexOf(needle);
        while (at >= 0) {
            total++;
            at = rendered.indexOf(needle, at + needle.length());
        }
        return total;
    }

    private String needleFor(String key) {
        return Json.quote(Objects.requireNonNull(key, "key")) + ":";
    }

    /** The three value shapes {@code Json.Obj} writes, and how each is recognised. */
    private enum Shape {
        /** A JSON number: {@code Json.Obj.count}, the only unquoted number this API emits. */
        NUMBER("a whole number written by Json.Obj.count"),
        /** A JSON boolean: {@code Json.Obj.bool}. */
        BOOLEAN("a boolean written by Json.Obj.bool"),
        /** A JSON string: {@code Json.Obj.str} and every figure, per ADR-0002. */
        STRING("a quoted string written by Json.Obj.str or Json.Obj.figure");

        private final String described;

        Shape(String described) {
            this.described = described;
        }

        boolean matchesAt(String rendered, int at) {
            if (at >= rendered.length()) {
                return false;
            }
            char first = rendered.charAt(at);
            return switch (this) {
                case NUMBER -> first == '-' || Character.isDigit(first);
                case BOOLEAN -> rendered.startsWith("true", at) || rendered.startsWith("false", at);
                case STRING -> first == '"';
            };
        }
    }

    /**
     * Where the one value of this key and this shape starts.
     *
     * @throws IllegalStateException if there is no such field, or more than one
     */
    private int valueStart(String key, Shape shape) {
        String needle = needleFor(key);
        int found = -1;
        int at = rendered.indexOf(needle);
        while (at >= 0) {
            int value = at + needle.length();
            if (shape.matchesAt(rendered, value)) {
                if (found >= 0) {
                    throw new IllegalStateException(
                        "the engine's response carries '" + key + "' as " + shape.described
                            + " more than once, so which one answers the question is a matter of"
                            + " writing order. Reading the first would be a coin toss with a period"
                            + " close on it. Body: " + abbreviated());
                }
                found = value;
            }
            at = rendered.indexOf(needle, value);
        }
        if (found < 0) {
            throw new IllegalStateException(
                "the engine's response carries no '" + key + "' field holding " + shape.described
                    + ". This module reads the field back rather than re-deriving it, so a response"
                    + " shape that changed under it is a defect here and not a data condition — see"
                    + " JsonView's class note. Body: " + abbreviated());
        }
        return found;
    }

    /** A string field that started as one and did not finish as one — a truncated body. */
    private IllegalStateException malformed(String key, String expected, int at) {
        return new IllegalStateException(
            "the engine's response field '" + key + "' does not carry " + expected + "; it reads '"
                + rendered.substring(at, Math.min(rendered.length(), at + 24)) + "'");
    }

    private String abbreviated() {
        return rendered.length() <= 400 ? rendered : rendered.substring(0, 400) + "…";
    }
}
