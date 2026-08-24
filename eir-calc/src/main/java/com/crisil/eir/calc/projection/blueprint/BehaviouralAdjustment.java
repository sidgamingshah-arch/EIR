package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The output of stage five of the blueprint pipeline
 * (<a href="../../../../../../../../../docs/09-cashflow-structures.md">09 § 4</a>):
 * the contractual ladder, the expected ladder the behavioural overlay produced, and
 * the recorded reason the two differ — or the recorded reason they do not.
 *
 * <p><b>Why this is a value and not just a returned ladder.</b> The expected leg on
 * its own cannot be audited. "Expected life is 116 months" is a number; "an 8%
 * annual CPR, converted to a single-period mortality of 0.69243826% at twelve
 * periods a year, compresses a 240-month contractual ladder to 116 months and a
 * principal-weighted life of 4.90 years" is a number with its derivation attached,
 * and only the second survives a review of the assumption. 09 § 2.7 makes the same
 * point about {@code Contractual}: "recorded as a policy choice, not an absence of
 * assumption", because the two produce identical numbers and very different audit
 * outcomes. A method that returned only a ladder could not express the difference.
 *
 * <p><b>Principal-weighted average life.</b> {@link #principalWeightedLifeYears()}
 * is {@code sum(t x principal_t) / sum(principal_t)}, converted to years — the
 * market convention, weighted by <em>principal</em> and not by total cash. The two
 * are easy to confuse and are not close: cash-weighting a mortgage drags the figure
 * towards the interest-heavy early periods on an amortising loan and towards the
 * late ones on a bullet, and the resulting number compares to no published WAL
 * anywhere. Fixture O6 quotes 12.88 years at nil CPR against a 240-month
 * (20-year) contractual term, which is the principal-weighted figure; a
 * cash-weighted computation on the same ladder is a different number and reporting
 * it as WAL is simply a mislabelled statistic.
 *
 * <p><b>ST-3 across the overlay.</b> A behavioural overlay moves principal
 * <em>in time</em>, never <em>in amount</em>: a prepayment brings principal forward,
 * a rollover pushes it back, a utilisation curve reshapes the path between, and in
 * every case the sum recovered is the sum advanced. That claim is asserted here as
 * well as inside {@link InstalmentLadder#of}, because the two checks fail for
 * different reasons — the ladder's own ST-3 catches an overlay that lost principal
 * against the notional, and this one catches an overlay whose expected leg no
 * longer ties to the <em>contractual</em> leg it is supposed to be a restatement
 * of. On a two-leg movement schedule it is the second that reconciles.
 *
 * @param overlay                   the overlay applied, retained so the assumption
 *     travels with the figures it produced
 * @param contractual               the ladder that went in
 * @param expected                  the ladder that came out; the same instance as
 *     {@code contractual} where the overlay alters nothing
 * @param recordedBasis             human-readable statement of the assumption and
 *     its derivation, for the disclosure and the audit file
 * @param expectedLifePeriods       period ordinal of the last expected rung
 * @param contractualLifePeriods    period ordinal of the last contractual rung
 * @param periodsPerYear            compounding frequency the overlay worked at
 * @param principalWeightedLifeYears principal-weighted average life of the expected
 *     leg, in years
 * @param invariants                the cross-leg ST-3 result followed by the
 *     expected ladder's own
 */
public record BehaviouralAdjustment(
    BehaviouralOverlay overlay,
    InstalmentLadder contractual,
    InstalmentLadder expected,
    String recordedBasis,
    int expectedLifePeriods,
    int contractualLifePeriods,
    int periodsPerYear,
    BigDecimal principalWeightedLifeYears,
    List<InvariantResult> invariants) {

    public BehaviouralAdjustment {
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(recordedBasis, "recordedBasis");
        Objects.requireNonNull(principalWeightedLifeYears, "principalWeightedLifeYears");
        Objects.requireNonNull(invariants, "invariants");
        if (recordedBasis.isBlank()) {
            throw new IllegalArgumentException(
                "state the basis of the expectation; a blank basis is indistinguishable from"
                    + " never having formed one, which is the distinction 09 § 2.7 exists to keep");
        }
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
        }
        if (expectedLifePeriods < 1 || contractualLifePeriods < 1) {
            throw new IllegalArgumentException(
                "lives are 1-based period ordinals, got expected " + expectedLifePeriods
                    + " and contractual " + contractualLifePeriods);
        }
        invariants = List.copyOf(invariants);
    }

    /**
     * Builds an adjustment and asserts ST-3 across the two legs.
     *
     * <p>The lives are read off the ladders rather than passed in, so a caller
     * cannot report a life the ladder does not have.
     */
    public static BehaviouralAdjustment of(
        BehaviouralOverlay overlay,
        InstalmentLadder contractual,
        InstalmentLadder expected,
        String recordedBasis,
        int periodsPerYear) {

        Objects.requireNonNull(contractual, "contractual");
        Objects.requireNonNull(expected, "expected");
        List<InvariantResult> checks = new ArrayList<>();
        checks.add(InvariantResult.ofMoney(
            InvariantId.ST_3,
            "principal recovered on the expected leg equals principal recovered on the"
                + " contractual leg — a behavioural overlay moves principal in time, not in amount",
            principalRecovered(contractual),
            principalRecovered(expected)));
        checks.addAll(expected.invariants());
        return new BehaviouralAdjustment(
            overlay,
            contractual,
            expected,
            recordedBasis,
            lastPeriod(expected),
            lastPeriod(contractual),
            periodsPerYear,
            principalWeightedLifeYears(expected, periodsPerYear),
            checks);
    }

    /**
     * {@code sum(t x principal_t) / sum(principal_t)}, in years — the market
     * convention for weighted average life.
     *
     * <p>The retained terminal balance is weighted at the final period. A
     * residual value on a lease is principal the lessor recovers on the last day
     * and nowhere else, so omitting it would shorten the reported life of every
     * balloon and every residual-value structure by exactly the weight of the
     * largest principal flow in the schedule.
     *
     * <p>Where the net principal recovered is nil the average is undefined and zero
     * is returned rather than a division raised from inside a reporting helper. That
     * is a revolver whose expected drawdown exactly offsets its expected repayment
     * over the behavioural life — a real, if uncommon, curve — and it has no
     * weighted life to quote.
     */
    public static BigDecimal principalWeightedLifeYears(InstalmentLadder ladder, int periodsPerYear) {
        Objects.requireNonNull(ladder, "ladder");
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be >= 1, got " + periodsPerYear);
        }
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            BigDecimal periods = BigDecimal.valueOf(rung.periodIndex());
            weighted = weighted.add(
                rung.principal().amount().multiply(periods, Precision.WORKING), Precision.WORKING);
            total = total.add(rung.principal().amount(), Precision.WORKING);
        }
        BigDecimal terminalPeriods = BigDecimal.valueOf(lastPeriod(ladder));
        weighted = weighted.add(
            ladder.terminalBalance().amount().multiply(terminalPeriods, Precision.WORKING),
            Precision.WORKING);
        total = total.add(ladder.terminalBalance().amount(), Precision.WORKING);
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return weighted
            .divide(total, Precision.WORKING)
            .divide(BigDecimal.valueOf(periodsPerYear), Precision.WORKING);
    }

    /** Principal the ladder recovers, retained terminal balance included. */
    public static Money principalRecovered(InstalmentLadder ladder) {
        Money recovered = Money.zero(ladder.currency());
        for (InstalmentLadder.Rung rung : ladder.rungs()) {
            recovered = recovered.plus(rung.principal());
        }
        return recovered.plus(ladder.terminalBalance());
    }

    /** Whether the overlay reshaped the flows at all. */
    public boolean altered() {
        return overlay.altersFlows();
    }

    /**
     * Expected life as a fraction of contractual life.
     *
     * <p>The single figure a behavioural policy committee argues about, because
     * fee recognition moves faster than it does: 09 § 2.7 records that compressing
     * a 20-year mortgage to 8 years multiplies year-one fee recognition by 3.73
     * rather than by 2.5, since declining-balance amortisation front-loads on top
     * of the shorter horizon. The ratio is therefore a <em>lower</em> bound on the
     * income effect and must never be quoted as the effect itself.
     */
    public BigDecimal lifeRatio() {
        return BigDecimal.valueOf(expectedLifePeriods)
            .divide(BigDecimal.valueOf(contractualLifePeriods), Precision.WORKING);
    }

    public boolean allSatisfied() {
        return invariants.stream().allMatch(InvariantResult::satisfied);
    }

    private static int lastPeriod(InstalmentLadder ladder) {
        return ladder.rungs().get(ladder.rungs().size() - 1).periodIndex();
    }
}
