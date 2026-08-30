package com.crisil.eir.api.modules.approximations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One shortcut, and whether anything on file defends it.
 *
 * <p><b>Four flags, not one "permitted".</b> Collapsing them is the mistake this record exists to
 * make impossible, because the state that matters is the one a single flag cannot express: a
 * shortcut somebody <em>reached for</em> that nothing on file defends. 06 § 7 names it — "an
 * undocumented approximation drifting quietly across a portfolio" — and {@link #undocumented()} is
 * the predicate {@link ApproximationRegister} counts.
 *
 * <p>The four are genuinely independent and each answers a different question:
 *
 * <ul>
 *   <li>{@link #sought} — <b>was the shortcut reached for?</b> The one the FR-809 count keys off.
 *   <li>{@link #inForce} — <b>is the book measured on it?</b> Narrower than {@code sought},
 *       because a gate may have demoted the subject to a more expensive and more correct basis.
 *   <li>{@link #evidenced} — <b>does a current document defend it?</b>
 *   <li>{@link #correctlyRefused} — <b>was it refused on purpose?</b> FR-412's absolute
 *       prohibition, which is the gate working rather than a missing file.
 * </ul>
 *
 * <p>The combinations that occur, and what each means:
 *
 * <ul>
 *   <li><b>sought, in force, evidenced</b> — the ordinary case. A Tier 3 population with a current
 *       equivalence test inside its Board threshold.
 *   <li><b>sought, in force, not evidenced</b> — a finding. A pool measured collectively whose
 *       quarterly back-test is out of date; an ACPIR 51 contractual-life election with no
 *       justification recorded.
 *   <li><b>sought, not in force, not evidenced</b> — <em>also</em> a finding, and the one that was
 *       nearly lost. A Tier 3 population with no equivalence test is demoted to Tier 2 by FR-411,
 *       so the shortcut stops being in force precisely because the evidence was missing. See
 *       {@link #undocumented()}.
 *   <li><b>sought, not in force, correctly refused</b> — the gate working. FR-412 refusing Tier 3
 *       to a zero-coupon instrument, before any evidence is consulted and beyond any evidence's
 *       power to reverse. Reported because a refusal that leaves no trace is indistinguishable
 *       from a shortcut nobody proposed, and subtracted from the count because it is not a
 *       missing document.
 *   <li><b>not sought</b> — a subject put to a gate that had nothing to gate: a Tier 1 or Tier 2
 *       proposal reaching the Tier 3 evaluator. Published so that a reader can see the gate ran.
 * </ul>
 *
 * @param category        which of FR-809's four this row belongs to
 * @param subjectId       the population, contract, pool or product the shortcut applies to
 * @param shortcut        what is being approximated, in words
 * @param inForce         whether the approximation is actually applied in the reported period
 * @param evidenced       whether documented, in-date evidence supports it
 * @param sought          whether the shortcut was <em>proposed</em> for this subject at all —
 *                        true even where a gate then refused it. See {@link #undocumented()} for
 *                        why this is the field the FR-809 count keys off rather than
 *                        {@code inForce}
 * @param correctlyRefused whether the shortcut was refused by a rule that is <em>meant</em> to
 *                        refuse it, as against refused for want of evidence. FR-412's absolute
 *                        prohibition on zero-coupon and deep-discount instruments is the only
 *                        instance today, and it is the gate working
 * @param basis           the audit sentence: what was decided and on what
 * @param evidenceDate    when the evidence was struck, or null where there is none
 * @param evidenceExpires when it stops being current, or null
 * @param varianceBps     the measured approximated-versus-solved delta in basis points, or null
 * @param thresholdBps    the approved tolerance the delta is measured against, or null
 * @param invariant       the invariant this row bears on, e.g. {@code TG-1}, or null
 * @param exception       the exception-queue category raised, or null where none is
 */
public record ApproximationRow(
    ApproximationCategory category,
    String subjectId,
    String shortcut,
    boolean inForce,
    boolean evidenced,
    boolean sought,
    boolean correctlyRefused,
    String basis,
    LocalDate evidenceDate,
    LocalDate evidenceExpires,
    BigDecimal varianceBps,
    BigDecimal thresholdBps,
    String invariant,
    String exception) {

    public ApproximationRow {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(shortcut, "shortcut");
        Objects.requireNonNull(basis, "basis");
        if (subjectId.isBlank()) {
            throw new IllegalArgumentException(
                "an approximation row names the subject the shortcut applies to; a row with no"
                    + " subject cannot be traced to a population, a contract or a pool, and an"
                    + " approximation nobody can locate is the one this register exists to find");
        }
        if (basis.isBlank()) {
            throw new IllegalArgumentException(
                "row for " + subjectId + " records no basis; 03 § 10.2 is explicit that \"we"
                    + " approximated because it was immaterial\" is a complete answer only when"
                    + " the materiality assessment exists on paper with a number attached");
        }
        // The check this record is worth having for. "Evidenced" claims a document exists, and a
        // document with no date cannot be tested for currency — 03 § 10.2 item 3 requires annual
        // re-performance and 03 § 10.1 a quarterly back-test, and both are date arithmetic. A row
        // asserting evidence with no date would satisfy every count in this report while being
        // precisely the undocumented shortcut the report is a control over.
        if (evidenced && evidenceDate == null) {
            throw new IllegalArgumentException(
                "row for " + subjectId + " claims documented evidence with no date on it; the"
                    + " currency of that evidence is what TG-1 and the 03 § 10.1 back-test"
                    + " assert, and an undated document cannot be current or stale");
        }
        if (evidenceExpires != null && evidenceDate == null) {
            throw new IllegalArgumentException(
                "row for " + subjectId + " has an expiry with no performance date; the window is"
                    + " measured from when the evidence was struck");
        }
        if (evidenceDate != null && evidenceExpires != null
            && evidenceExpires.isBefore(evidenceDate)) {
            throw new IllegalArgumentException(
                "row for " + subjectId + " has evidence performed " + evidenceDate
                    + " expiring earlier, on " + evidenceExpires);
        }
        // A shortcut cannot be applied without having been proposed. The pairing matters because
        // undocumented() is defined over `sought` and the report's "measured on" reading over
        // `inForce`; a row in force but not sought would be counted by neither and displayed by
        // both, which is the state where a shortcut is applied and no figure in the report knows.
        if (inForce && !sought) {
            throw new IllegalArgumentException(
                "row for " + subjectId + " reports the shortcut in force without it having been"
                    + " sought; the register counts unevidenced shortcuts over what was sought,"
                    + " so such a row would be applied to the book and absent from every figure");
        }
        // FR-412 refuses before any evidence is consulted, so a refusal cannot also be a
        // permission. If these ever coexisted the register would subtract a row from the
        // undocumented count on the strength of a refusal it simultaneously claims was allowed.
        if (correctlyRefused && inForce) {
            throw new IllegalArgumentException(
                "row for " + subjectId + " is recorded as correctly refused and also in force;"
                    + " 03 § 10.3's prohibition is absolute and a refused shortcut is not applied");
        }
    }

    /**
     * The shortcut was <b>sought</b> and nothing on file defends it — the finding of FR-809.
     *
     * <p>{@link ApproximationRegister} publishes the count of these as a single figure, because
     * that figure is the one question a control owner asks of this endpoint and the one an
     * auditor asks of the bank.
     *
     * <p><b>Why this keys off {@link #sought} and not {@link #inForce}, which is what it did
     * first and was wrong.</b> For the one category 06 § 7 singles out, {@code inForce} and
     * {@code evidenced} are the same predicate, so {@code inForce && !evidenced} was a
     * structural zero. {@code EquivalenceTestGate} <em>demotes</em> an unevidenced Tier 3
     * population to Tier 2 rather than letting it stand, so the moment the evidence is missing
     * the shortcut stops being in force — and a count defined over shortcuts still in force can
     * never see the very population that FR-411 exists to catch. A Tier 3 population with no
     * equivalence test on file rendered {@code undocumented: false}, contributed nothing to the
     * register's headline figure, and never reached
     * {@code IndAs107Extract}'s Ind AS 1.122 sign-off refusal. That is the flagship risk
     * invisible to the flagship control — the same defect as a control that cannot fail, arrived
     * at by defining the count over the wrong side of a demotion.
     *
     * <p>So the question the count asks is "was a shortcut sought that nobody has evidence for",
     * which is true of the demoted population and stays true of one that was never demoted
     * because nothing gated it. {@link #inForce} remains on the row for the different question a
     * reader also needs — what basis is the book actually measured on.
     *
     * <p>{@link #correctlyRefused} is subtracted because FR-412 is not a missing document. 03
     * § 10.3 refuses zero-coupon and deep-discount instruments at any tenor, the gate applies
     * that before consulting evidence, and {@code EquivalenceTestOutcome.Ground} is explicit
     * that such a refusal is a TG-1 <em>pass</em>: "blocking a period close on a population
     * where the engine did exactly the right thing would bury the genuine TG-1 breaches in a
     * list of correct refusals". Counting them would do the same thing to this register.
     */
    public boolean undocumented() {
        return sought && !evidenced && !correctlyRefused;
    }

    /** A one-line audit sentence, for the {@code gaps}-style flat listings. */
    public String describe() {
        StringBuilder sentence = new StringBuilder()
            .append(category.name()).append(' ').append(subjectId).append(": ")
            .append(inForce ? "IN FORCE" : "not applied")
            .append(evidenced ? ", evidenced" : ", NOT EVIDENCED")
            .append(correctlyRefused ? " (correctly refused, not a missing document)" : "")
            .append(undocumented() ? " [UNDOCUMENTED SHORTCUT]" : "")
            .append(" — ").append(basis);
        if (evidenceDate != null) {
            sentence.append(" [evidence performed ").append(evidenceDate);
            if (evidenceExpires != null) {
                sentence.append(", current to ").append(evidenceExpires);
            }
            sentence.append(']');
        }
        if (exception != null) {
            sentence.append(" [exception ").append(exception).append(']');
        }
        return sentence.toString();
    }
}
