package com.crisil.eir.api.modules.approximations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One shortcut, and whether anything on file defends it.
 *
 * <p><b>{@link #inForce} and {@link #evidenced} are two facts, not one.</b> Collapsing them into
 * a single "permitted" flag is the mistake this record exists to make impossible, because the
 * combination that matters is the one a single flag cannot express: a shortcut that is
 * <em>applied</em> and <em>unevidenced</em>. 06 § 7 names it — "an undocumented approximation
 * drifting quietly across a portfolio" — and {@link #undocumented()} is the predicate
 * {@link ApproximationRegister} counts.
 *
 * <p>All four combinations occur in this engine and each means something different:
 *
 * <ul>
 *   <li><b>in force, evidenced</b> — the ordinary case. A Tier 3 population with a current
 *       equivalence test inside its Board threshold.
 *   <li><b>in force, not evidenced</b> — the finding. A pool measured collectively whose
 *       quarterly back-test is out of date; an ACPIR 51 contractual-life election with no
 *       justification recorded.
 *   <li><b>not in force, not evidenced</b> — a correct refusal, kept on the record. FR-412
 *       refusing Tier 3 to a zero-coupon instrument, which {@code EquivalenceTestGate} does
 *       <em>before</em> consulting any evidence and which no evidence can reverse. Reported
 *       because a refusal that leaves no trace is indistinguishable from a shortcut nobody
 *       proposed.
 *   <li><b>not in force, evidenced</b> — evidence on file for a shortcut that is not being
 *       taken. Harmless, and still worth publishing: it is what a population looks like the
 *       period after it was demoted for a different reason.
 * </ul>
 *
 * @param category        which of FR-809's four this row belongs to
 * @param subjectId       the population, contract, pool or product the shortcut applies to
 * @param shortcut        what is being approximated, in words
 * @param inForce         whether the approximation is actually applied in the reported period
 * @param evidenced       whether documented, in-date evidence supports it
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
    }

    /**
     * The shortcut is applied and nothing on file defends it — the finding of FR-809.
     *
     * <p>{@link ApproximationRegister} publishes the count of these as a single figure, because
     * that figure is the one question a control owner asks of this endpoint and the one an
     * auditor asks of the bank.
     */
    public boolean undocumented() {
        return inForce && !evidenced;
    }

    /** A one-line audit sentence, for the {@code gaps}-style flat listings. */
    public String describe() {
        StringBuilder sentence = new StringBuilder()
            .append(category.name()).append(' ').append(subjectId).append(": ")
            .append(inForce ? "IN FORCE" : "not applied")
            .append(evidenced ? ", evidenced" : ", NOT EVIDENCED")
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
