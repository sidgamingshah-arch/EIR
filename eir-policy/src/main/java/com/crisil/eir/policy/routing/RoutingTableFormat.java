package com.crisil.eir.policy.routing;

import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.RateDriver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The routing table's on-disk text form — the construction path that makes
 * ADR-0006 true rather than aspirational.
 *
 * <p>ADR-0006 decides that the driver-to-mechanism mapping is "data in an
 * approved, versioned table — not a {@code switch} statement", so that when the
 * IASB's April 2026 tentative decision reaches an Exposure Draft the response is
 * "a new routing table version with an impact preview and checker approval — the
 * same governed path as any other policy change. Not a code change, a regression
 * cycle and a release." Until now the only way to build a {@link RoutingTable}
 * was {@link RoutingTable#ofSpecDefaults} — eight literal {@code mapping.put}
 * calls in Java. That is data in the sense that a hard-coded constant is data:
 * changing it needs a compiler. This class is the file format that replaces it,
 * and the reason the Phase 2 exit-gate sentence "the routing table can be changed
 * without a code deploy" holds.
 *
 * <p><strong>No binding library.</strong> The root POM's enforcer bans Jackson
 * along with Spring, Hibernate and JPA, transitively, in every module (ADR-0001).
 * So the format is line-oriented text read by a hand-rolled reader. That is not a
 * concession: a hand-rolled reader knows the line number of every value, and the
 * error messages below — which have to be actionable for whoever is editing an
 * approved accounting policy at 22:00 during a close — are the substance of this
 * class rather than an afterthought. {@link java.util.Properties} was the other
 * candidate and was rejected for exactly that reason: it discards line numbers,
 * silently keeps the last of two duplicate keys, and cannot tell a typo'd key
 * from an absent one.
 *
 * <h2>The format</h2>
 *
 * <pre>{@code
 * # a whole-line comment
 * version.id            = RT-2027.1
 * version.description   = what interpretation this version encodes
 * version.effectiveFrom = 2027-04-01
 * version.maker         = policy.author@bank.example
 * version.checker       = policy.approver@bank.example
 * version.approvedOn    = 2027-02-26
 *
 * route.TIME_VALUE_OF_MONEY          = RESET
 * route.CREDIT_RISK_MARKET           = RESET
 * route.CREDIT_RATCHET_PREDETERMINED = CATCH_UP
 * route.ESG_LINKED                   = CATCH_UP
 * route.STEP_UP_PREDETERMINED        = CATCH_UP
 * route.BEHAVIOURAL_ESTIMATE         = CATCH_UP
 * route.DISBURSEMENT_TIMING          = CATCH_UP
 * route.NEGOTIATED                   = MODIFICATION_TEST
 * }</pre>
 *
 * <ul>
 *   <li>One {@code key = value} per line. The first {@code =} separates them, so a
 *       description may contain further {@code =} signs. Key and value are
 *       stripped of surrounding whitespace, which is why the block above may be
 *       aligned for review without changing its meaning.
 *   <li>A line whose first non-whitespace character is {@code #} is a comment, and
 *       a blank line is ignored. {@code #} is <em>not</em> a comment marker
 *       mid-line: a value is never at the start of a line, so a hash inside a
 *       description is unambiguous and needs no escaping.
 *   <li>Inside a value, {@code \n}, {@code \r}, {@code \t} and {@code \\} are the
 *       only escapes; any other backslash sequence is an error rather than a
 *       literal, so that a typo cannot pass through into an approved description.
 *   <li>Dates are ISO-8601 {@code yyyy-mm-dd}. Files are UTF-8 — an Indian bank's
 *       policy owner has a name, and it may not be ASCII.
 *   <li>Unknown keys are rejected. {@code versoin.id} must not present itself as a
 *       missing {@code version.id}; the operator has to be told about the typo,
 *       not about its consequence.
 *   <li>Every key needs a value. A blanked one is refused rather than read as
 *       absent, including a value that decodes to whitespace: deleting a row and
 *       emptying it are different edits and neither may pass silently.
 *   <li>LF and CRLF endings both read, and a leading UTF-8 byte order mark is
 *       ignored. The file is edited on whatever the policy owner has installed, and
 *       an invisible character must not become part of a key.
 * </ul>
 *
 * <h2>Round-trip and byte stability</h2>
 *
 * <p>{@link #emit} is deterministic: fixed key order, fixed {@code LF} line
 * endings regardless of platform, no generation timestamp. Two emitted versions of a
 * table therefore diff to exactly the rows that changed, which is what a checker
 * approves against, and {@code parse(emit(t))} equals {@code t} for every table.
 * Determinism is a control requirement rather than tidiness: comparing an approved
 * artefact byte for byte is only meaningful if writing the same table twice produces
 * the same bytes.
 *
 * <p>The <em>other</em> direction does not hold in general, and must not be relied
 * on. {@code emit(parse(x))} equals {@code x} only when {@code x} was itself
 * emitted: a hand-edited file's comments, row order and alignment are presentation,
 * they are deliberately not carried into the {@link RoutingTable}, and re-emitting
 * drops them. Two artefacts are therefore compared for equality as <em>parsed
 * tables</em>, or by re-emitting both and comparing those; comparing a hand-edited
 * file against a re-emission of itself would report a semantically identical
 * approved table as changed.
 *
 * <h2>Failure</h2>
 *
 * <p>Every fault throws {@link RoutingTableFormatException} naming the offending
 * line — see that type for why a malformed configuration file throws where a
 * malformed <em>contract</em> would return an {@code InvariantResult}. Nothing is
 * defaulted, inferred, or half-loaded. In particular a file missing one driver row
 * is diagnosed <em>here</em>, naming the driver, rather than being handed to
 * {@code RoutingTable}'s constructor: that constructor's message is correct but
 * speaks about a {@code Map}, and the person who needs it is looking at a text
 * file.
 */
public final class RoutingTableFormat {

    /** Key for {@link RoutingTableVersion#id()}. */
    private static final String KEY_ID = "version.id";

    /** Key for {@link RoutingTableVersion#description()}. */
    private static final String KEY_DESCRIPTION = "version.description";

    /** Key for {@link RoutingTableVersion#effectiveFrom()}. */
    private static final String KEY_EFFECTIVE_FROM = "version.effectiveFrom";

    /** Key for {@link RoutingTableVersion#maker()}. */
    private static final String KEY_MAKER = "version.maker";

    /** Key for {@link RoutingTableVersion#checker()}. */
    private static final String KEY_CHECKER = "version.checker";

    /** Key for {@link RoutingTableVersion#approvedOn()}. */
    private static final String KEY_APPROVED_ON = "version.approvedOn";

    /**
     * The six version keys, in emission order.
     *
     * <p>All six are mandatory. None may be defaulted: an absent {@code maker} or
     * {@code checker} would produce a table that looks approved and is not, and an
     * absent {@code effectiveFrom} would leave the date-based selection of the
     * version in force undefined.
     */
    private static final List<String> VERSION_KEYS = List.of(
        KEY_ID, KEY_DESCRIPTION, KEY_EFFECTIVE_FROM, KEY_MAKER, KEY_CHECKER, KEY_APPROVED_ON);

    /** Prefix marking one driver-to-mechanism row. */
    private static final String ROUTE_PREFIX = "route.";

    /** A line whose first non-whitespace character is this is a comment. */
    private static final String COMMENT_MARKER = "#";

    /** Separates key from value. Only the first occurrence on a line separates. */
    private static final char SEPARATOR = '=';

    /** Emitted line ending. Always LF, never the platform's — see byte stability. */
    private static final String LINE_FEED = "\n";

    /** Source name used when the caller supplies text with no provenance. */
    private static final String ANONYMOUS_SOURCE = "<text>";

    /** Below this many characters a "did you mean" suggestion is noise, not help. */
    private static final int HINT_MIN_LENGTH = 4;

    /**
     * U+FEFF, which Windows editors put at the head of a UTF-8 file.
     *
     * <p>Written as an escape deliberately: the character itself is invisible, and a
     * source line whose meaning depends on an invisible character is the bug this
     * constant exists to fix.
     */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * The header {@link #emit} writes above the data.
     *
     * <p>It exists so that the artefact documents itself. The file is read and
     * edited by an accounting policy owner, not only by this class, and the two
     * sentences about issuing a new version id are the ones that stop a closed
     * period becoming irreproducible.
     */
    private static final List<String> HEADER = List.of(
        "# EIR routing table — rate driver to mechanism, as approved versioned data (ADR-0006).",
        "#",
        "# One 'key = value' per line; a line starting with '#' is a comment; blank lines are",
        "# ignored. Dates are ISO-8601. In a value, \\n, \\r, \\t and \\\\ are the only escapes.",
        "#",
        "# Every rate driver needs exactly one route row. A partial table is refused rather than",
        "# defaulted: a defaulted routing is a silently wrong routing (ADR-0006, FR-504).",
        "#",
        "# Changing a row is a maker-checker event, not an edit — issue a NEW version.id. Every",
        "# routed event stores the version id that routed it, so two mappings sharing one id",
        "# make a closed period irreproducible on replay.");

    private RoutingTableFormat() {
        // Static format; there is nothing to configure and nothing to keep.
    }

    /**
     * Reads a routing table from a UTF-8 file.
     *
     * @throws IOException                  if the file cannot be read or is not UTF-8
     * @throws RoutingTableFormatException  if it can be read and is not one approved table
     */
    public static RoutingTable parse(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    /** Reads a routing table from text of unknown provenance. */
    public static RoutingTable parse(CharSequence text) {
        return parse(text, ANONYMOUS_SOURCE);
    }

    /**
     * Reads a routing table from text, reporting faults against {@code sourceName}.
     *
     * <p>Strict throughout. The value of a strict reader here is not neatness: this
     * file decides whether an ESG ratchet resets the rate or books a catch-up, and
     * reference cases 3 and 4 are the same instrument in the same month with a
     * 627.42 charge in one and nothing in the other. A reader that tolerated a
     * duplicate row, or filled an absent one, would let a one-character edit change
     * the P&amp;L of a portfolio with no diagnostic anywhere.
     *
     * @param text       the file's contents; LF or CRLF line endings
     * @param sourceName what to name in error messages, typically a path
     * @throws RoutingTableFormatException naming the offending line, on any fault
     */
    public static RoutingTable parse(CharSequence text, String sourceName) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceName, "sourceName");

        // LinkedHashMap, not EnumMap-of-anything: the version keys are strings, and
        // insertion order is what lets an error message quote them back in file order.
        Map<String, Value> versionFields = new LinkedHashMap<>();
        Map<RateDriver, Mechanism> mapping = new EnumMap<>(RateDriver.class);
        Map<RateDriver, Integer> routeLines = new EnumMap<>(RateDriver.class);

        // -1 keeps trailing empty lines, so the reported line numbers match the file's
        // even when it ends with a run of blank lines.
        String[] lines = text.toString().split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String line = stripCarriageReturn(lines[index]);
            if (index == 0) {
                line = stripByteOrderMark(line);
            }
            String content = line.strip();
            if (content.isEmpty() || content.startsWith(COMMENT_MARKER)) {
                continue;
            }
            int separator = content.indexOf(SEPARATOR);
            if (separator < 0) {
                throw fault(
                    "expected 'key = value' but the line has no '=' separator",
                    sourceName, lineNumber, line);
            }
            String key = content.substring(0, separator).strip();
            String rawValue = content.substring(separator + 1).strip();
            if (key.isEmpty()) {
                throw fault("the key before '=' is empty", sourceName, lineNumber, line);
            }
            String value = unescape(rawValue, sourceName, lineNumber, line);
            if (value.strip().isEmpty()) {
                // Not treated as "absent". An operator who blanks a value has made an edit;
                // the resulting table must not silently lose the field. Tested after
                // unescaping so that a value of '\t' — which is not empty as written and is
                // blank as meant — is refused here with its line number rather than reaching
                // RoutingTableVersion, which would reject it without one.
                throw fault(
                    "key '" + key + "' has an empty value; every key in a routing table must "
                        + "carry a value, and an unwanted row is deleted rather than blanked",
                    sourceName, lineNumber, line);
            }

            if (key.startsWith(ROUTE_PREFIX)) {
                readRoute(key, value, mapping, routeLines, sourceName, lineNumber, line);
            } else if (VERSION_KEYS.contains(key)) {
                Value previous = versionFields.put(key, new Value(value, lineNumber, line));
                if (previous != null) {
                    throw fault(
                        "duplicate key '" + key + "', already set on line " + previous.lineNumber()
                            + "; the file's meaning would depend on which one wins",
                        sourceName, lineNumber, line);
                }
            } else {
                throw fault(
                    "unknown key '" + key + "'." + hintFor(key, VERSION_KEYS)
                        + " Known keys: " + String.join(", ", VERSION_KEYS)
                        + ", and 'route.<RATE_DRIVER>'",
                    sourceName, lineNumber, line);
            }
        }

        RoutingTableVersion version = readVersion(versionFields, sourceName);
        requireEveryDriverRouted(mapping, sourceName);
        return new RoutingTable(version, mapping);
    }

    /**
     * Writes {@code table} in the form {@link #parse} reads, exactly.
     *
     * <p>Deterministic and LF-terminated — see the class comment on byte stability.
     * The emitted text carries the header comment block, which {@code parse}
     * ignores, so the artefact is readable by the policy owner who has to approve
     * it and not only by this class.
     */
    public static String emit(RoutingTable table) {
        Objects.requireNonNull(table, "table");
        StringBuilder out = new StringBuilder();
        for (String headerLine : HEADER) {
            out.append(headerLine).append(LINE_FEED);
        }
        out.append(LINE_FEED);

        RoutingTableVersion version = table.version();
        appendRow(out, KEY_ID, version.id());
        appendRow(out, KEY_DESCRIPTION, version.description());
        appendRow(out, KEY_EFFECTIVE_FROM, version.effectiveFrom().toString());
        appendRow(out, KEY_MAKER, version.maker());
        appendRow(out, KEY_CHECKER, version.checker());
        appendRow(out, KEY_APPROVED_ON, version.approvedOn().toString());
        out.append(LINE_FEED);

        // RateDriver.values() order, not the map's iteration order and not alphabetical:
        // declaration order groups the market-movement drivers first, which is how 03 § 6.1
        // presents the table and how a reviewer reads it.
        for (RateDriver driver : RateDriver.values()) {
            appendRow(out, ROUTE_PREFIX + driver.name(), table.mechanismFor(driver).name());
        }
        return out.toString();
    }

    /** One driver-to-mechanism row, with duplicate and unknown-name detection. */
    private static void readRoute(
            String key,
            String value,
            Map<RateDriver, Mechanism> mapping,
            Map<RateDriver, Integer> routeLines,
            String sourceName,
            int lineNumber,
            String line) {
        String driverName = key.substring(ROUTE_PREFIX.length());
        RateDriver driver = constantOrNull(RateDriver.values(), driverName);
        if (driver == null) {
            throw fault(
                "unknown rate driver '" + driverName + "'."
                    + hintFor(driverName, namesOf(RateDriver.values()))
                    + " Known drivers: " + String.join(", ", namesOf(RateDriver.values())),
                sourceName, lineNumber, line);
        }
        Mechanism mechanism = constantOrNull(Mechanism.values(), value);
        if (mechanism == null) {
            throw fault(
                "unknown mechanism '" + value + "' for driver " + driver + "."
                    + hintFor(value, namesOf(Mechanism.values()))
                    + " Known mechanisms: " + String.join(", ", namesOf(Mechanism.values())),
                sourceName, lineNumber, line);
        }
        Integer previousLine = routeLines.get(driver);
        if (previousLine != null) {
            // Rejected even when the two rows agree. Two rows for one driver is an editing
            // accident — a copy-paste during a re-routing — and "the last one wins" is a
            // rule nobody reviewing a diff applies in their head.
            throw fault(
                "duplicate route for " + driver + ", already routed on line " + previousLine
                    + "; one driver has exactly one mechanism",
                sourceName, lineNumber, line);
        }
        mapping.put(driver, mechanism);
        routeLines.put(driver, lineNumber);
    }

    /** The version block, with its own approval checks. */
    private static RoutingTableVersion readVersion(Map<String, Value> fields, String sourceName) {
        List<String> missing = new ArrayList<>();
        for (String key : VERSION_KEYS) {
            if (!fields.containsKey(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            // No line to point at, which is exactly what makes an omission easy to miss on
            // review — so it is named as a whole-file fault rather than inferred later.
            throw fault(
                "the version block is incomplete; missing " + missing
                    + ". A routing table without a complete approval trail is not an approved "
                    + "table, and its id is what every routed event stores",
                sourceName, RoutingTableFormatException.NO_LINE, null);
        }
        Value maker = fields.get(KEY_MAKER);
        Value checker = fields.get(KEY_CHECKER);
        if (maker.text().strip().equals(checker.text().strip())) {
            // RoutingTableVersion enforces this too. It is checked here as well so the
            // message can name the line the operator has to change, and name both lines.
            //
            // Compared on the STRIPPED values, exactly as RoutingTableVersion compares them.
            // Otherwise 'version.checker = \tmaker@bank.example' — one escape away from the
            // maker — would slip past this check and be caught only by the record, which
            // reports no line number. Four-eyes is not ceremony here, so the evasion has to
            // fail at the line the operator can see.
            throw fault(
                "maker and checker are both '" + maker.text().strip() + "' (maker on line "
                    + maker.lineNumber() + "); a routing table approved by its own maker is not "
                    + "approved. Four-eyes is not ceremony here: the mapping alone decides "
                    + "whether a month carries a catch-up charge or nothing",
                sourceName, checker.lineNumber(), checker.line());
        }
        LocalDate effectiveFrom = readDate(fields.get(KEY_EFFECTIVE_FROM), sourceName);
        LocalDate approvedOn = readDate(fields.get(KEY_APPROVED_ON), sourceName);
        try {
            return new RoutingTableVersion(
                fields.get(KEY_ID).text(),
                fields.get(KEY_DESCRIPTION).text(),
                effectiveFrom,
                maker.text(),
                checker.text(),
                approvedOn);
        } catch (IllegalArgumentException | NullPointerException cause) {
            // Belt and braces. Every condition RoutingTableVersion rejects is already
            // checked above with a line number, so reaching here means that record gained a
            // rule this reader does not know about; the operator still gets the file's name.
            throw new RoutingTableFormatException(
                sourceName + ": version block rejected — " + cause.getMessage(),
                sourceName, RoutingTableFormatException.NO_LINE, null, cause);
        }
    }

    /** One ISO-8601 date, or a fault naming the line and the expected form. */
    private static LocalDate readDate(Value field, String sourceName) {
        try {
            return LocalDate.parse(field.text());
        } catch (DateTimeParseException cause) {
            // Catches both a wrong shape (01/04/2027, 2027-4-1) and a date that does not
            // exist (2027-02-30). Both are silent corruption if coerced: an effective date
            // one month out selects the wrong version in force for a whole close.
            throw new RoutingTableFormatException(
                message(
                    "'" + field.text() + "' is not an ISO-8601 date; expected yyyy-mm-dd, "
                        + "e.g. 2027-04-01",
                    sourceName, field.lineNumber(), field.line()),
                sourceName, field.lineNumber(), field.line(), cause);
        }
    }

    /**
     * Rejects a partial table, naming the drivers with no row.
     *
     * <p>{@code RoutingTable}'s constructor rejects an incomplete map as well, and
     * that is the real guard. This one exists to make the message address the
     * person editing the file: it names the missing driver and the line to add,
     * rather than reporting an unmapped key in a {@code Map} they never saw.
     */
    private static void requireEveryDriverRouted(
            Map<RateDriver, Mechanism> mapping, String sourceName) {
        List<RateDriver> missing = new ArrayList<>();
        for (RateDriver driver : RateDriver.values()) {
            if (!mapping.containsKey(driver)) {
                missing.add(driver);
            }
        }
        if (!missing.isEmpty()) {
            throw fault(
                "no route row for " + missing + "; every rate driver must be routed explicitly, "
                    + "because a defaulted routing is a silently wrong routing (ADR-0006, "
                    + "FR-504). Add 'route.<RATE_DRIVER> = <MECHANISM>' for each",
                sourceName, RoutingTableFormatException.NO_LINE, null);
        }
    }

    /** Applies the four value escapes; anything else is a fault, never a literal. */
    private static String unescape(String value, String sourceName, int lineNumber, String line) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                out.append(current);
                continue;
            }
            if (index + 1 == value.length()) {
                throw fault(
                    "the value ends with a dangling '\\'; write '\\\\' for a literal backslash",
                    sourceName, lineNumber, line);
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                default -> throw fault(
                    "unknown escape '\\" + escaped + "' in the value; only \\n, \\r, \\t and "
                        + "\\\\ are escapes. A backslash typo must not reach an approved "
                        + "description unnoticed",
                    sourceName, lineNumber, line);
            }
        }
        return out.toString();
    }

    /** The inverse of {@link #unescape}, so that emit and parse round-trip exactly. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(current);
            }
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, String key, String value) {
        // No alignment padding. Aligned '=' reads better in a fixed table, but adding a
        // ninth driver with a longer name would reflow every row and the diff a checker
        // approves would show eight changes instead of one.
        out.append(key).append(' ').append(SEPARATOR).append(' ')
            .append(escape(value)).append(LINE_FEED);
    }

    /** Tolerates a CRLF file without letting the '\r' into a value. */
    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * Drops a leading UTF-8 byte order mark from the first line.
     *
     * <p>Notepad and PowerShell's {@code Set-Content} write one, and the same
     * operator whose CRLF endings are tolerated above will save this file with
     * those tools. U+FEFF is not whitespace, so without this it sticks to the first
     * token and the diagnostic becomes "unknown key 'version.id'. Known keys:
     * version.id, ..." — self-contradictory, because the offending character is
     * invisible. Only the first line, and only a leading one: elsewhere U+FEFF is a
     * zero-width no-break space and belongs to whatever value contains it.
     */
    private static String stripByteOrderMark(String line) {
        return !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK ? line.substring(1) : line;
    }

    private static <E extends Enum<E>> E constantOrNull(E[] values, String name) {
        for (E candidate : values) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        // Deliberately exact-match only. Accepting 'reset' or 'Reset' would make the file's
        // vocabulary a matter of taste, and the diff of an approved table ambiguous.
        return null;
    }

    private static List<String> namesOf(Enum<?>[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    /**
     * A "did you mean" clause when the input differs only in case, separators or a
     * truncated tail.
     *
     * <p>Cheap, and it turns the three commonest edit mistakes — {@code reset} for
     * {@code RESET}, {@code catch up} for {@code CATCH_UP}, {@code ESG_LINK} for
     * {@code ESG_LINKED} — from a hunt through an enum into a one-line fix. It is a
     * hint only: the reader still refuses the file, because guessing what an
     * approved accounting policy meant is exactly the behaviour this format exists
     * to prevent.
     */
    private static String hintFor(String given, List<String> candidates) {
        String probe = withoutSeparators(given);
        if (probe.length() < HINT_MIN_LENGTH) {
            return "";
        }
        for (String candidate : candidates) {
            String normalised = withoutSeparators(candidate);
            if (normalised.equalsIgnoreCase(probe)
                || startsWithIgnoreCase(normalised, probe)
                || startsWithIgnoreCase(probe, normalised)) {
                return " Did you mean '" + candidate + "'?";
            }
        }
        return "";
    }

    /** Case, spaces, hyphens and underscores are what a typo usually gets wrong. */
    private static String withoutSeparators(String value) {
        return value.replace(" ", "").replace("-", "").replace("_", "");
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length()
            && value.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }

    private static RoutingTableFormatException fault(
            String problem, String sourceName, int lineNumber, String line) {
        return new RoutingTableFormatException(
            message(problem, sourceName, lineNumber, line), sourceName, lineNumber, line);
    }

    /**
     * {@code source:line: problem} plus the line itself.
     *
     * <p>Shaped like a compiler diagnostic on purpose. The reader of this message is
     * mid-edit in a text file and needs the line number first.
     */
    private static String message(String problem, String sourceName, int lineNumber, String line) {
        StringJoiner joiner = new StringJoiner("");
        joiner.add(sourceName);
        if (lineNumber != RoutingTableFormatException.NO_LINE) {
            joiner.add(":").add(Integer.toString(lineNumber));
        }
        joiner.add(": ").add(problem);
        if (line != null) {
            joiner.add(System.lineSeparator()).add("  line: ").add(line);
        }
        return joiner.toString();
    }

    /**
     * One value as it appeared in the file, with where it appeared.
     *
     * <p>The line number is carried alongside the text rather than looked up later
     * because the version block is validated after the whole file is read, and by
     * then the only way to name the offending line is to have kept it.
     */
    private record Value(String text, int lineNumber, String line) {
    }
}
