package com.crisil.eir.policy.tier;

import com.crisil.eir.domain.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One performed equivalence test: the evidence that makes a Tier 3 approximation
 * defensible (03 § 10.2).
 *
 * <p>The shortcut of {@link com.crisil.eir.domain.MaterialityTier#TIER_3} — contractual rate
 * plus straight-line accretion of net fees — is proportionate, but 03 § 10.2 is emphatic that
 * it must be <em>evidenced</em>: "'We approximated because it was immaterial' is a complete
 * answer only when the materiality assessment exists on paper with a number attached." This
 * type is that paper, and it carries all three of the section's requirements rather than a
 * boolean saying somebody once did the work:
 *
 * <ol>
 *   <li><b>A solved-versus-approximated comparison on a representative sample.</b> Held as the
 *       two rates themselves ({@link #solvedEir}, {@link #approximatedEir}) plus the
 *       {@link #sampleSize} they were struck over — not as a pre-computed delta. A record that
 *       stored only the delta could carry a number inconsistent with its own operands, and the
 *       delta is the figure the Board threshold is set against, so it is the one figure that
 *       must not be assertable by hand.
 *   <li><b>The delta, documented, against a Board-approved threshold.</b>
 *       {@link #boardApprovedThresholdBps}, and {@link #isWithinThreshold()} is the comparison.
 *   <li><b>Annual re-performance.</b> {@link #ANNUAL_WINDOW}, {@link #expiresOn()} and
 *       {@link #isInDateOn(LocalDate)}. An out-of-date test demotes the population to Tier 2,
 *       which is invariant TG-1 and the whole subject of {@link EquivalenceTestGate}.
 * </ol>
 *
 * <p><b>The comparison is at effective annual, never per period.</b> {@link Rate} is explicit
 * that its two annualisations are not interchangeable, and a solved monthly EIR set beside an
 * approximated annual quote has no meaningful periodic difference at all. Taking
 * {@link Rate#effectiveAnnualBps()} on both sides makes the delta the economically meaningful
 * spread regardless of how either side happened to be compounded, which is the only form in
 * which a single Board threshold can govern a whole book.
 *
 * <p><b>The delta is compared in absolute value.</b> A shortcut that understates income by
 * 40 bps is exactly as indefensible as one that overstates it by 40 bps; a signed threshold
 * would wave through half the failures. {@link #deltaBps()} keeps the sign, because which
 * direction the approximation errs in is worth reading in a workpaper, and
 * {@link #isWithinThreshold()} discards it.
 *
 * @param populationId              the Tier 3 population this test was performed over; the key
 *                                  {@link EquivalenceTestGate} looks up
 * @param performedOn               the date the comparison was struck — the start of the annual
 *                                  window, not the date it was written up
 * @param sampleSize                contracts in the representative sample; never zero
 * @param solvedEir                 the rate the full solver produced on the sample
 * @param approximatedEir           the rate the Tier 3 shortcut produced on the same sample
 * @param boardApprovedThresholdBps the approved tolerance, in basis points of effective annual
 *                                  rate; never negative
 * @param approvedBy                who approved the threshold this test was measured against
 */
public record EquivalenceTestRecord(
    String populationId,
    LocalDate performedOn,
    int sampleSize,
    Rate solvedEir,
    Rate approximatedEir,
    BigDecimal boardApprovedThresholdBps,
    String approvedBy) {

    /**
     * The re-performance period of 03 § 10.2 item 3, and of control C-10 in 07 § 4.
     *
     * <p>A {@link Period} of one year rather than 365 days, so that
     * {@code LocalDate.plusYears} handles the leap year: a test performed on 2028-02-29 expires
     * on 2029-02-28, which is the answer an annual programme run on financial year-ends
     * expects. A 365-day window would drift a day per leap year and eventually push an
     * on-schedule test out of date.
     */
    public static final Period ANNUAL_WINDOW = Period.ofYears(1);

    public EquivalenceTestRecord {
        Objects.requireNonNull(populationId, "populationId");
        Objects.requireNonNull(performedOn, "performedOn");
        Objects.requireNonNull(solvedEir, "solvedEir");
        Objects.requireNonNull(approximatedEir, "approximatedEir");
        Objects.requireNonNull(boardApprovedThresholdBps, "boardApprovedThresholdBps");
        Objects.requireNonNull(approvedBy, "approvedBy");
        // Stripped, because the population id is a join key: EquivalenceTestGate matches a
        // subject to its test on file by exact string, so one leading space in a feed demotes a
        // population to Tier 2 and raises a TG-1 breach that no amount of re-performing the
        // test will ever clear. Whitespace only — not case-folded, because two ids differing in
        // case may well be two populations, and silently merging them would be a worse defect
        // than failing to match them.
        populationId = populationId.strip();
        if (populationId.isBlank()) {
            throw new IllegalArgumentException(
                "an equivalence test names the population it was performed over; TG-1 is asserted"
                    + " per population and an unnamed test cannot be matched to one");
        }
        // 03 section 10.2 item 1 says "representative sample". A test performed over no
        // contracts is not a small sample, it is the absence of the comparison the section
        // requires, and it must not be storable as evidence that the comparison happened.
        if (sampleSize < 1) {
            throw new IllegalArgumentException(
                "equivalence test for " + populationId + " has sample size " + sampleSize
                    + "; a comparison on no contracts is not a representative sample, it is no"
                    + " comparison at all");
        }
        if (boardApprovedThresholdBps.signum() < 0) {
            throw new IllegalArgumentException(
                "equivalence test for " + populationId + " carries a negative threshold "
                    + boardApprovedThresholdBps.toPlainString()
                    + " bps; the delta is compared in absolute value, so a negative tolerance is"
                    + " a threshold nothing can satisfy");
        }
        if (approvedBy.isBlank()) {
            throw new IllegalArgumentException(
                "equivalence test for " + populationId + " names nobody who approved its"
                    + " threshold; ACPIR forbids manual override (ADR-0008), which makes the"
                    + " approval record the only evidence the tolerance was ever set");
        }
    }

    /**
     * The signed spread between the solved rate and the approximated one, in basis points of
     * effective annual rate. Positive where the shortcut understates the true EIR.
     */
    public BigDecimal deltaBps() {
        return solvedEir.effectiveAnnualBps().subtract(approximatedEir.effectiveAnnualBps());
    }

    /** The magnitude of the delta, which is what the Board threshold governs. */
    public BigDecimal absoluteDeltaBps() {
        return deltaBps().abs();
    }

    /** Whether the documented delta sits inside the approved tolerance (03 § 10.2 item 2). */
    public boolean isWithinThreshold() {
        return absoluteDeltaBps().compareTo(boardApprovedThresholdBps) <= 0;
    }

    /**
     * By how many basis points the delta overshoots the approved tolerance, or zero where it
     * does not. The deviation {@link EquivalenceTestGate} reports on a threshold breach.
     */
    public BigDecimal excessOverThresholdBps() {
        BigDecimal excess = absoluteDeltaBps().subtract(boardApprovedThresholdBps);
        return excess.signum() > 0 ? excess : BigDecimal.ZERO;
    }

    /** The last date this test still permits the Tier 3 shortcut. */
    public LocalDate expiresOn() {
        return performedOn.plus(ANNUAL_WINDOW);
    }

    /**
     * Whether this test is in date on {@code asOf} — the whole content of invariant TG-1,
     * "Tier 3 equivalence test in date".
     *
     * <p><b>The anniversary itself is in date.</b> A test performed on 2027-03-31 is current
     * through 2028-03-31 inclusive and stale from 2028-04-01. The boundary is pinned this way
     * because the alternative makes an annual programme structurally non-compliant: a bank that
     * re-performs every test on 31 March, exactly as "annual re-performance" asks, would find
     * every population out of date for the one day before the new test lands, and would have to
     * re-perform at 364-day intervals to stay clean. Nothing in 03 § 10.2 or control C-10 asks
     * for that, and a rule that penalises the schedule it prescribes is the wrong reading.
     *
     * <p>A test dated after {@code asOf} is <em>not</em> handled here — it is in date by this
     * arithmetic, and it is still no evidence at all as at the reporting date.
     * {@link EquivalenceTestGate} refuses it, for the reason given there.
     */
    public boolean isInDateOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !asOf.isAfter(expiresOn());
    }

    /** Whether this test had been performed at all as at {@code asOf}. */
    public boolean wasPerformedBy(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !performedOn.isAfter(asOf);
    }

    /**
     * Days by which the annual window has been overrun on {@code asOf}, or zero while in date.
     * Reported as the TG-1 deviation so a control report says how stale, not merely that it is.
     */
    public long daysOverdueOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        long overdue = ChronoUnit.DAYS.between(expiresOn(), asOf);
        return Math.max(overdue, 0L);
    }

    /** A one-line audit sentence naming this test, its delta and its window. */
    public String describe() {
        return "equivalence test for " + populationId + " performed " + performedOn
            + " over " + sampleSize + " contracts, delta " + deltaBps().toPlainString()
            + " bps against a threshold of " + boardApprovedThresholdBps.toPlainString()
            + " bps approved by " + approvedBy + ", in date to " + expiresOn();
    }
}
