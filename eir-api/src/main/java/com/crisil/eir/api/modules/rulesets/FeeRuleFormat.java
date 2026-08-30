package com.crisil.eir.api.modules.rulesets;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.policy.fee.rule.FeeRule;
import com.crisil.eir.policy.fee.rule.FeeRuleKey;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rule rows of a submitted fee rule set, as line-oriented text (06 § 5, FR-201, 03 § 3.2).
 *
 * <p><b>Why a format at all.</b> A fee rule set is a table of rows, and the whole point of drafting
 * one over HTTP is submitting several rows at once. Form encoding carries flat key-value pairs and
 * the last of two identical keys wins, so there is no way to send a repeated {@code rule=} field —
 * the rows have to arrive as one value with a separator inside it.
 *
 * <p><b>Why hand-rolled, and why line-oriented.</b> Jackson is banned in every module (ADR-0001),
 * and the same reasoning {@link com.crisil.eir.policy.routing.RoutingTableFormat} sets out applies
 * here at a fraction of the size: a hand-rolled reader knows the row number of every value, and the
 * error messages are the substance rather than an afterthought, because the reader of one is
 * mid-edit in a fee master. This class is deliberately the smaller sibling of that one and copies
 * its conventions — {@code #} comments, blank lines ignored, unknown values refused with the known
 * list, nothing defaulted.
 *
 * <h2>The format</h2>
 *
 * <pre>{@code
 * # fee_code | product | entity | effective_from | classification | rationale
 * PROC_FEE   | *       | *      | 2028-04-01     | INTEGRAL       | origination fee, ACPIR 53
 * DSA_COMM   | HL      | *      | 2028-04-01     | INTEGRAL       | incremental selling cost
 * PENAL_CHG  | *       | *      | 2028-04-01     | EXCLUDED_BY_DIRECTION | RBI penal-charge circular
 * }</pre>
 *
 * <ul>
 *   <li>One rule per line, six fields separated by {@code |}. Fields are stripped, so the block
 *       above may be aligned for review without changing its meaning.
 *   <li>{@code product} and {@code entity} accept {@code *} — or a blank field, which
 *       {@link FeeRuleKey} normalises to the same thing. The {@code (code, *, *)} row is 04 § 2.5's
 *       mandatory per-code default.
 *   <li>The <b>fee code</b> may not be {@code *}. {@link FeeRuleKey} refuses it, and the refusal is
 *       load-bearing rather than fussy: a global {@code (*, *, *)} row would classify every posting,
 *       so no code could ever be unmapped and FR-202 would be unenforceable by construction — the
 *       exception queue would be empty because nothing can miss, not because the taxonomy is
 *       complete. That refusal is left to {@code FeeRuleKey} and surfaces as an accounting refusal
 *       rather than a format fault, exactly as a routing table naming {@code DERECOGNITION} does.
 *   <li>The rationale is the last field and is required. {@link FeeRule} refuses a blank one,
 *       because ACPIR 52 carries no negative list: every classification outside the positive limb is
 *       the bank adopting IFRS 9 B5.4.2/B5.4.3 as its own policy, and an unwritten election is not
 *       an accounting policy an auditor can read. It may contain {@code |}: only the first five
 *       separators split, so the rest of the line is the reason.
 *   <li>Dates are ISO-8601 {@code yyyy-mm-dd}. Both LF and CRLF read.
 * </ul>
 *
 * <p><b>An empty set is refused</b>, and this is the one rule here that is not merely mechanical. A
 * rule set with no rows is representable — {@link com.crisil.eir.policy.fee.rule.FeeRuleSet} accepts
 * an empty list, deliberately, so that a taxonomy under construction can be reviewed — but it is not
 * something anybody submits on purpose, and approved into force it refuses every posting in the book
 * with the same message and tells nobody why. That is the condition
 * {@code FeeClassificationResolver}'s own constructor refuses for a resolver over nothing, in those
 * words, and a submission with no rows is the same mistake one step earlier.
 */
public final class FeeRuleFormat {

    /** Separates the six fields. Only the first five occurrences on a line separate. */
    private static final String SEPARATOR = "\\|";

    /** Fields per row. */
    private static final int FIELDS = 6;

    /** A line whose first non-whitespace character is this is a comment. */
    private static final String COMMENT_MARKER = "#";

    /** The wildcard, spelled as {@link FeeRuleKey#ANY} spells it. */
    private static final String ANY = FeeRuleKey.ANY;

    private FeeRuleFormat() {
        // A static format; there is nothing to configure and nothing to keep.
    }

    /** A fault in the submitted rows, naming the row it is on. */
    public static final class FeeRuleFormatException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /** Used where the fault is about the submission as a whole rather than one row. */
        public static final int NO_ROW = 0;

        private final int rowNumber;

        FeeRuleFormatException(String message, int rowNumber) {
            super(Objects.requireNonNull(message, "message"));
            this.rowNumber = rowNumber;
        }

        /** The one-based row the fault is on, or {@link #NO_ROW}. */
        public int rowNumber() {
            return rowNumber;
        }
    }

    /**
     * Reads every row, or throws naming the first one it cannot.
     *
     * <p>Throws on the first fault rather than collecting them. Deliberate, and the opposite of what
     * this engine does with a <em>contract</em>: a run over ten million contracts collects refusals
     * because a bad contract says nothing about the next one, whereas these rows are one artefact
     * submitted by one person in one edit, and a half-read artefact is not a rule set. The same
     * choice {@code RoutingTableFormat} makes, for the same reason.
     *
     * @param text       the rows; LF or CRLF endings
     * @param sourceName what to name in a fault message
     * @throws FeeRuleFormatException on any fault in the text
     * @throws IllegalArgumentException from {@link FeeRule} or {@link FeeRuleKey} where a row reads
     *     cleanly and states something no rule set may hold — a wildcard fee code, a blank rationale
     */
    public static List<FeeRule> parse(CharSequence text, String sourceName) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceName, "sourceName");

        List<FeeRule> rules = new ArrayList<>();
        // -1 keeps trailing empty lines, so a reported row number matches what the operator sees
        // even where the submission ends in blank lines.
        String[] lines = text.toString().split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            int rowNumber = index + 1;
            String line = stripCarriageReturn(lines[index]).strip();
            if (line.isEmpty() || line.startsWith(COMMENT_MARKER)) {
                continue;
            }
            rules.add(readRule(line, sourceName, rowNumber));
        }
        if (rules.isEmpty()) {
            throw new FeeRuleFormatException(
                sourceName + ": no rule rows. A fee rule set with no rows is not a taxonomy under"
                    + " construction, it is a set that refuses every posting in the book with the"
                    + " same message and tells nobody why. Submit at least one row, and see 04 § 2.5"
                    + " for the mandatory per-code default '<CODE> | * | * | <date> | ... '",
                FeeRuleFormatException.NO_ROW);
        }
        return List.copyOf(rules);
    }

    /** One row. */
    private static FeeRule readRule(String line, String sourceName, int rowNumber) {
        // Limit FIELDS, so the sixth field keeps every '|' after it: a rationale is prose and may
        // legitimately contain one. Splitting greedily would turn a reason containing a pipe into a
        // seven-field row and refuse it, which is a format inventing a restriction on a policy's own
        // words.
        String[] fields = line.split(SEPARATOR, FIELDS);
        if (fields.length != FIELDS) {
            throw fault(
                "expected " + FIELDS + " fields separated by '|' and found " + fields.length
                    + ". The row is 'fee_code | product | entity | effective_from | classification"
                    + " | rationale'; write '*' for a wildcard product or entity",
                sourceName, rowNumber, line);
        }
        String feeCode = fields[0].strip();
        String product = wildcardOrValue(fields[1]);
        String entity = wildcardOrValue(fields[2]);
        LocalDate effectiveFrom = readDate(fields[3].strip(), sourceName, rowNumber, line);
        FeeClassification classification =
            readClassification(fields[4].strip(), sourceName, rowNumber, line);
        String rationale = fields[5].strip();

        if (feeCode.isEmpty()) {
            // Named here rather than left to FeeRuleKey, which refuses a blank code with a correct
            // message that speaks about keys. The person reading this is looking at a row.
            throw fault(
                "the fee code field is empty. FR-202 requires a refusal to name the key it could not"
                    + " resolve, and a blank code cannot be named",
                sourceName, rowNumber, line);
        }
        if (rationale.isEmpty()) {
            // FeeRule refuses this too, and that is the real guard. Refused here as well so the
            // message names the row the operator has to edit.
            throw fault(
                "the rationale field is empty. ACPIR 52 carries no negative list, so a"
                    + " classification outside the positive limb is a policy election, and an"
                    + " unwritten election is not an accounting policy an auditor can read",
                sourceName, rowNumber, line);
        }
        return FeeRule.of(feeCode, product, entity, effectiveFrom, classification, rationale);
    }

    private static String wildcardOrValue(String raw) {
        String value = raw.strip();
        // Blank and '*' both mean any, exactly as FeeRuleKey normalises them, so a row written with
        // an empty column and one written with a star are the same rule rather than two rules a
        // reader cannot tell apart.
        return value.isEmpty() ? ANY : value;
    }

    private static LocalDate readDate(String raw, String sourceName, int rowNumber, String line) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException wrongShape) {
            // Catches a wrong shape (01/04/2028, 2028-4-1) and a date that does not exist
            // (2028-02-30). Coerced, either is silent corruption: a rule's effective date decides
            // whether it is a candidate for a posting at all, so a date a month out silently
            // withdraws a rule for a whole period.
            throw fault(
                "'" + raw + "' is not an ISO-8601 date; expected yyyy-mm-dd, e.g. 2028-04-01",
                sourceName, rowNumber, line);
        }
    }

    private static FeeClassification readClassification(
            String raw, String sourceName, int rowNumber, String line) {
        for (FeeClassification candidate : FeeClassification.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        // Exact match only. Accepting 'integral' or 'Integral' would make the artefact's vocabulary
        // a matter of taste and the diff of an approved rule set ambiguous — the same decision
        // RoutingTableFormat records for mechanism names.
        throw fault(
            "unknown classification '" + raw + "'. Known: " + names(),
            sourceName, rowNumber, line);
    }

    private static String names() {
        List<String> names = new ArrayList<>(FeeClassification.values().length);
        for (FeeClassification value : FeeClassification.values()) {
            names.add(value.name());
        }
        return String.join(", ", names);
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * {@code source:row: problem} plus the row itself.
     *
     * <p>Shaped like a compiler diagnostic, for the reason {@code RoutingTableFormat} gives: the
     * reader is mid-edit and needs the row number first.
     */
    private static FeeRuleFormatException fault(
            String problem, String sourceName, int rowNumber, String line) {
        return new FeeRuleFormatException(
            sourceName + ":" + rowNumber + ": " + problem + System.lineSeparator()
                + "  row: " + line,
            rowNumber);
    }
}
