package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How the contractual rate behaves over the instrument's life.
 *
 * <p><b>This is the join between projection and routing.</b> Every variant declares
 * the {@link RateDriver} that a change to it emits, and the projector does nothing
 * more with it than carry it. Whether that driver means reset or catch-up is decided
 * by the versioned routing table, not here — see ADR-0006.
 *
 * <p>Keeping the decision out of this file is the whole point. The IASB is actively
 * amending the rule the mechanics are borrowed from, so a change to the treatment
 * must be a policy version rather than an edit to a projector. What the projector
 * knows is what each rate component <em>compensates for</em>; that is durable
 * whatever the wording becomes.
 */
public sealed interface RateProfile {

    /** The rate in force for a given 1-based period ordinal. */
    Rate rateForPeriod(int periodIndex);

    /** Fixed or floating, for the routing table's rate-type override. */
    RateType rateType();

    /**
     * What a change to this component compensates for.
     *
     * <p>For {@link Fixed} there is no change by the instrument's own terms, so a
     * renegotiation is {@code NEGOTIATED} and runs the modification test. That is
     * the trap row in the decision table: a fixed-rate loan whose rate is
     * renegotiated is not a benchmark reset, even though both look like the rate
     * moved.
     */
    RateDriver driverOnChange();

    String label();

    /** One rate for the whole life. */
    record Fixed(Rate rate) implements RateProfile {

        public Fixed {
            Objects.requireNonNull(rate, "rate");
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            return rate;
        }

        @Override
        public RateType rateType() {
            return RateType.FIXED;
        }

        @Override
        public RateDriver driverOnChange() {
            return RateDriver.NEGOTIATED;
        }

        @Override
        public String label() {
            return "FIXED";
        }
    }

    /**
     * Repricing off a benchmark by the instrument's own terms — EBLR, MCLR,
     * repo-linked.
     *
     * <p>EBLR resets can be monthly or quarterly, so naively re-solving the whole
     * schedule per account per reset is computationally brutal at Indian retail
     * volumes. The B5.4.4 next-repricing-date election exists to remove that cost by
     * leaving no unamortised fee to carry across the reset.
     */
    record Floating(
        String benchmarkId,
        BigDecimal spreadBps,
        List<LocalDate> resetDates,
        Rate currentRate) implements RateProfile {

        public Floating {
            Objects.requireNonNull(benchmarkId, "benchmarkId");
            Objects.requireNonNull(spreadBps, "spreadBps");
            Objects.requireNonNull(resetDates, "resetDates");
            Objects.requireNonNull(currentRate, "currentRate");
            if (benchmarkId.isBlank()) {
                throw new IllegalArgumentException("benchmarkId must name the benchmark");
            }
            resetDates = List.copyOf(resetDates);
            for (int i = 1; i < resetDates.size(); i++) {
                if (!resetDates.get(i).isAfter(resetDates.get(i - 1))) {
                    throw new IllegalArgumentException(
                        "reset dates must be strictly ascending; " + resetDates.get(i)
                            + " does not follow " + resetDates.get(i - 1));
                }
            }
        }

        /** The next reset strictly after {@code asOf}, or empty where none remains. */
        public Optional<LocalDate> nextResetAfter(LocalDate asOf) {
            Objects.requireNonNull(asOf, "asOf");
            for (LocalDate date : resetDates) {
                if (date.isAfter(asOf)) {
                    return Optional.of(date);
                }
            }
            return Optional.empty();
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            return currentRate;
        }

        @Override
        public RateType rateType() {
            return RateType.FLOATING;
        }

        @Override
        public RateDriver driverOnChange() {
            return RateDriver.TIME_VALUE_OF_MONEY;
        }

        @Override
        public String label() {
            return "FLOATING(" + benchmarkId + " + " + spreadBps.toPlainString() + "bp)";
        }
    }

    /**
     * A floating rate bounded by a cap, a floor, or both.
     *
     * <p>A binding cap changes the cash flows but is not itself a re-estimation
     * event: the benchmark moved, the cap is part of the projection, and the driver
     * stays {@code TIME_VALUE_OF_MONEY}. Treating a cap binding as its own event
     * would fabricate a catch-up out of a contractual term that was there all along.
     */
    record FloatingWithCollar(Floating base, BigDecimal cap, BigDecimal floor) implements RateProfile {

        public FloatingWithCollar {
            Objects.requireNonNull(base, "base");
            if (cap == null && floor == null) {
                throw new IllegalArgumentException(
                    "a collar needs a cap or a floor; with neither use Floating");
            }
            if (cap != null && floor != null && cap.compareTo(floor) < 0) {
                throw new IllegalArgumentException(
                    "cap " + cap.toPlainString() + " is below floor " + floor.toPlainString());
            }
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            BigDecimal periodic = base.rateForPeriod(periodIndex).periodic();
            if (cap != null && periodic.compareTo(cap) > 0) {
                periodic = cap;
            }
            if (floor != null && periodic.compareTo(floor) < 0) {
                periodic = floor;
            }
            return Rate.periodic(periodic, base.currentRate().periodsPerYear());
        }

        @Override
        public RateType rateType() {
            return RateType.FLOATING;
        }

        @Override
        public RateDriver driverOnChange() {
            return RateDriver.TIME_VALUE_OF_MONEY;
        }

        @Override
        public String label() {
            return "FLOATING_WITH_COLLAR";
        }
    }

    /**
     * A pre-determined coupon ladder — a step-up bond.
     *
     * <p>The steps are known at inception and unrelated to any market rate, so a
     * change routes to a catch-up rather than a reset. The April 2026 tentative
     * decision puts exactly this outside B5.4.5: it compensates for neither the time
     * value of money nor credit risk.
     */
    record StepCoupon(List<CouponStep> ladder) implements RateProfile {

        public StepCoupon {
            Objects.requireNonNull(ladder, "ladder");
            if (ladder.isEmpty()) {
                throw new IllegalArgumentException("a coupon ladder needs at least one step");
            }
            // Strictly ascending, exactly as Floating validates its reset dates. Without
            // this, rateForPeriod below is order-dependent and silently wrong: it keeps the
            // LAST rung whose fromPeriod has been reached, so a ladder supplied in
            // descending order makes the period-1 rung win at every index and the coupon
            // never steps at all. [(13, 1.0%), (7, 0.9%), (1, 0.8%)] returned 0.8% at
            // period 8 and at period 20. A descending coupon table is an ordinary
            // ingestion order, and duplicate fromPeriod values were equally unguarded.
            //
            // This is also the profile whose changes route to a B5.4.6 catch-up rather than
            // a B5.4.5 reset, so a ladder that never steps restates the carrying amount at
            // a rate the contract never bore — the defect CU-1 exists to make loud, arrived
            // at from the projection side instead.
            for (int i = 1; i < ladder.size(); i++) {
                if (ladder.get(i).fromPeriod() <= ladder.get(i - 1).fromPeriod()) {
                    throw new IllegalArgumentException(
                        "a coupon ladder must be strictly ascending in fromPeriod; step " + i
                            + " starts at period " + ladder.get(i).fromPeriod() + " after a step at "
                            + ladder.get(i - 1).fromPeriod() + ". rateForPeriod keeps the last rung"
                            + " reached, so an out-of-order ladder does not step");
                }
            }
            ladder = List.copyOf(ladder);
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            Rate applicable = ladder.get(0).rate();
            for (CouponStep step : ladder) {
                if (step.fromPeriod() <= periodIndex) {
                    applicable = step.rate();
                }
            }
            return applicable;
        }

        @Override
        public RateType rateType() {
            return RateType.FIXED;
        }

        @Override
        public RateDriver driverOnChange() {
            return RateDriver.STEP_UP_PREDETERMINED;
        }

        @Override
        public String label() {
            return "STEP_COUPON(" + ladder.size() + " steps)";
        }
    }

    /**
     * A margin ratchet keyed to a covenant, a rating, or a sustainability KPI.
     *
     * <p>The driver differs by trigger, and that difference is the point: a credit
     * ratchet and an ESG ratchet are mechanically identical and route identically
     * today, but they stay distinguishable so that a wording change affecting only
     * one of them is a mapping-table edit rather than a code change.
     *
     * <p>An ESG feature also has to clear SPPI first. A rate adjustment contingent on
     * an emissions target can meet SPPI where it does not significantly alter the
     * interest rate; a coupon indexed to a carbon <em>price</em> index fails, because
     * the variable is unrelated to basic lending risks — and an SPPI failure on an
     * asset means FVTPL and no EIR at all.
     */
    record Ratchet(List<RatchetTier> tiers, RatchetTrigger trigger, Rate currentRate)
        implements RateProfile {

        public Ratchet {
            Objects.requireNonNull(tiers, "tiers");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(currentRate, "currentRate");
            if (tiers.isEmpty()) {
                throw new IllegalArgumentException("a ratchet needs at least one tier");
            }
            tiers = List.copyOf(tiers);
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            return currentRate;
        }

        @Override
        public RateType rateType() {
            return RateType.FLOATING;
        }

        @Override
        public RateDriver driverOnChange() {
            return trigger == RatchetTrigger.SUSTAINABILITY_KPI
                ? RateDriver.ESG_LINKED
                : RateDriver.CREDIT_RATCHET_PREDETERMINED;
        }

        @Override
        public String label() {
            return "RATCHET(" + trigger + ", " + tiers.size() + " tiers)";
        }
    }

    /** Principal or coupon indexed to inflation. */
    record InflationIndexed(String indexId, Rate realRate) implements RateProfile {

        public InflationIndexed {
            Objects.requireNonNull(indexId, "indexId");
            Objects.requireNonNull(realRate, "realRate");
            if (indexId.isBlank()) {
                throw new IllegalArgumentException("indexId must name the index");
            }
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            return realRate;
        }

        @Override
        public RateType rateType() {
            return RateType.FLOATING;
        }

        @Override
        public RateDriver driverOnChange() {
            return RateDriver.TIME_VALUE_OF_MONEY;
        }

        @Override
        public String label() {
            return "INFLATION_INDEXED(" + indexId + ")";
        }
    }

    /**
     * A contractual repricing of the credit spread to prevailing market.
     *
     * <p>Distinct from {@link Floating}, which reprices the benchmark. Under the
     * April 2026 tentative decision both reset, because both compensate for
     * something B5.4.5 covers — but they stay separate drivers so that the narrower
     * Alternative A reading, which excludes borrower-specific credit spread, remains
     * expressible as a mapping change rather than a rebuild.
     */
    record MarketSpreadReset(List<LocalDate> resetDates, Rate currentRate) implements RateProfile {

        public MarketSpreadReset {
            Objects.requireNonNull(resetDates, "resetDates");
            Objects.requireNonNull(currentRate, "currentRate");
            resetDates = List.copyOf(resetDates);
        }

        @Override
        public Rate rateForPeriod(int periodIndex) {
            return currentRate;
        }

        @Override
        public RateType rateType() {
            return RateType.FLOATING;
        }

        @Override
        public RateDriver driverOnChange() {
            return RateDriver.CREDIT_RISK_MARKET;
        }

        @Override
        public String label() {
            return "MARKET_SPREAD_RESET";
        }
    }

    /** One rung of a {@link StepCoupon} ladder. */
    record CouponStep(int fromPeriod, Rate rate) {

        public CouponStep {
            Objects.requireNonNull(rate, "rate");
            if (fromPeriod < 1) {
                throw new IllegalArgumentException("fromPeriod is 1-based, got " + fromPeriod);
            }
        }
    }

    /** One tier of a {@link Ratchet}. */
    record RatchetTier(String condition, BigDecimal marginBps) {

        public RatchetTier {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(marginBps, "marginBps");
            if (condition.isBlank()) {
                throw new IllegalArgumentException("a ratchet tier must state its condition");
            }
        }
    }

    /** What moves a {@link Ratchet}. */
    enum RatchetTrigger {
        FINANCIAL_COVENANT,
        EXTERNAL_RATING,
        SUSTAINABILITY_KPI
    }
}
