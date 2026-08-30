package com.crisil.eir.api.modules.approximations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One performed quarterly back-test of a pool EIR against contract-level computation (03 § 10.1).
 *
 * <p>03 § 10.1 attaches this to pool-level measurement in the same breath as permitting it:
 * "<b>Mandatory quarterly back-test</b> against contract-level computation on a statistical
 * sample, with a materiality threshold that, when breached, forces the pool to contract-level
 * measurement." So a pool in force is a shortcut, and this is the only thing that defends it. A
 * pool with no in-date back-test is measured collectively on nothing.
 *
 * <p><b>Modelled on {@code EquivalenceTestRecord} deliberately, and it is the same control at a
 * different frequency.</b> The Tier 3 equivalence test is an annual solved-versus-approximated
 * comparison against a Board threshold; this is a quarterly one. Both are date arithmetic over a
 * window plus a magnitude against a tolerance, and the two are reported side by side in this
 * register, so they are shaped alike on purpose — a reader comparing a Tier 3 population's
 * evidence with a pool's should not have to learn two vocabularies.
 *
 * <p><b>No default threshold is declared here, and that absence is deliberate.</b> 03 § 10.1 says
 * "a materiality threshold" and publishes no number, exactly as 03 § 10.3 publishes a mechanism
 * and no deep-discount share. {@code EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE}
 * declares a policy default for its case and says so; this one does not, because the consequence
 * of the two defaults runs in opposite directions. Setting the deep-discount share too low
 * catches more instruments and costs Tier 2 measurement — more expensive and more correct. A
 * back-test threshold guessed too high would <em>pass</em> pools that should have been forced to
 * contract level, so a default here would manufacture the permission it is supposed to test. The
 * threshold is therefore a required constructor argument, and a pool whose back-test carries no
 * approved threshold cannot be recorded at all.
 *
 * @param poolId         the pool back-tested
 * @param performedOn    the date the comparison was struck
 * @param sampleSize     contracts in the statistical sample; never zero
 * @param varianceBps    the signed pool-versus-contract-level spread, in basis points
 * @param thresholdBps   the approved materiality threshold in basis points; never negative
 * @param approvedBy     who approved that threshold
 */
public record PoolBackTest(
    String poolId,
    LocalDate performedOn,
    int sampleSize,
    BigDecimal varianceBps,
    BigDecimal thresholdBps,
    String approvedBy) {

    /**
     * The re-performance window of 03 § 10.1 — "mandatory quarterly back-test".
     *
     * <p>A {@link Period} of three months rather than 91 days, for the reason
     * {@code EquivalenceTestRecord.ANNUAL_WINDOW} gives for using a year rather than 365 days: a
     * programme run on calendar quarter-ends is what "quarterly" asks for, and a day-count window
     * drifts against it. A test performed 30 November is current through 28 February.
     */
    public static final Period QUARTERLY_WINDOW = Period.ofMonths(3);

    public PoolBackTest {
        Objects.requireNonNull(poolId, "poolId");
        Objects.requireNonNull(performedOn, "performedOn");
        Objects.requireNonNull(varianceBps, "varianceBps");
        Objects.requireNonNull(thresholdBps, "thresholdBps");
        Objects.requireNonNull(approvedBy, "approvedBy");
        // Stripped for the reason EquivalenceTestRecord strips its population id: this is the key
        // the register joins a back-test to a PoolDefinition on, and one leading space in a feed
        // makes a pool look un-back-tested when the evidence is sitting right there.
        poolId = poolId.strip();
        if (poolId.isBlank()) {
            throw new IllegalArgumentException(
                "a back-test names the pool it was performed over; the 03 § 10.1 control is"
                    + " asserted per pool and an unnamed test cannot be matched to one");
        }
        if (sampleSize < 1) {
            throw new IllegalArgumentException(
                "back-test for " + poolId + " has sample size " + sampleSize + "; 03 § 10.1 asks"
                    + " for a statistical sample, and a comparison over no contracts is not a"
                    + " small sample but the absence of the comparison");
        }
        if (thresholdBps.signum() < 0) {
            throw new IllegalArgumentException(
                "back-test for " + poolId + " carries a negative threshold "
                    + thresholdBps.toPlainString() + " bps; the variance is compared in absolute"
                    + " value, so a negative tolerance is a threshold nothing can satisfy");
        }
        if (approvedBy.isBlank()) {
            throw new IllegalArgumentException(
                "back-test for " + poolId + " names nobody who approved its threshold; the"
                    + " approval record is the only evidence the tolerance was ever set");
        }
    }

    /**
     * The magnitude of the variance, which is what the threshold governs.
     *
     * <p>Absolute for the reason {@code EquivalenceTestRecord} gives: a pool EIR that understates
     * income by 40 bps is exactly as indefensible as one overstating it by 40 bps, and a signed
     * comparison would wave through half the breaches. {@link #varianceBps} keeps the sign,
     * because the direction is worth reading in a workpaper.
     */
    public BigDecimal absoluteVarianceBps() {
        return varianceBps.abs();
    }

    /** Whether the variance sits inside the approved threshold (03 § 10.1). */
    public boolean isWithinThreshold() {
        return absoluteVarianceBps().compareTo(thresholdBps) <= 0;
    }

    /** By how many basis points the variance overshoots the threshold, or zero where it does not. */
    public BigDecimal excessOverThresholdBps() {
        BigDecimal excess = absoluteVarianceBps().subtract(thresholdBps);
        return excess.signum() > 0 ? excess : BigDecimal.ZERO;
    }

    /** The last date this back-test still defends pool-level measurement. */
    public LocalDate expiresOn() {
        return performedOn.plus(QUARTERLY_WINDOW);
    }

    /**
     * Whether this back-test is in date on {@code asOf}.
     *
     * <p>The window end is itself in date, for the reason {@code EquivalenceTestRecord} pins the
     * anniversary in date: a bank that re-performs every back-test on the quarter-end, exactly as
     * "quarterly" asks, must not find every pool out of date for the day before the new test
     * lands.
     */
    public boolean isInDateOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !asOf.isAfter(expiresOn());
    }

    /** Whether this back-test had been performed at all as at {@code asOf}. */
    public boolean wasPerformedBy(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !performedOn.isAfter(asOf);
    }

    /** Days the quarterly window has been overrun on {@code asOf}, or zero while in date. */
    public long daysOverdueOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        long overdue = ChronoUnit.DAYS.between(expiresOn(), asOf);
        return Math.max(overdue, 0L);
    }

    /** Whether this back-test both is current and passed — the only state that defends the pool. */
    public boolean defendsPoolMeasurementOn(LocalDate asOf) {
        return wasPerformedBy(asOf) && isInDateOn(asOf) && isWithinThreshold();
    }

    /** A one-line audit sentence naming the test, its variance and its window. */
    public String describe() {
        return "quarterly back-test of pool " + poolId + " performed " + performedOn + " over "
            + sampleSize + " contracts, variance " + varianceBps.toPlainString()
            + " bps against a threshold of " + thresholdBps.toPlainString() + " bps approved by "
            + approvedBy + ", in date to " + expiresOn();
    }
}
