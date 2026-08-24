package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.RateDriver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * How expected cash flows differ from contractual ones.
 *
 * <p>Every variant emits {@code BEHAVIOURAL_ESTIMATE} on revision, so a curve change
 * routes to a catch-up rather than a reset. It is the entity's own estimate that
 * moved, not a market rate.
 *
 * <p>This is the highest-leverage dimension in the model. Compressing assumed life
 * on a 20-year mortgage from 20 years to 8 multiplies year-one fee recognition by
 * 3.73 times — more than the inverse of the life ratio, because declining-balance
 * amortisation front-loads on top of the shorter horizon. And a revision applies
 * across every affected contract at once. That is why a curve change is governed as
 * a policy version with a mandatory impact preview rather than as a parameter update.
 *
 * <p>One consequence worth stating, because it decides where the engine spends its
 * close window: a behavioural revision is a P&amp;L event <b>only for an instrument
 * held away from par</b> (invariant ST-9). With no unamortised premium or discount
 * there is nothing for a change in speed to accelerate — the revised flows always
 * discount to the outstanding balance at the contractual rate. Catch-up processing
 * therefore keys on that balance, not on an assumption having moved.
 */
public sealed interface BehaviouralOverlay {

    String label();

    /** Whether the overlay truncates or reshapes the contractual flows at all. */
    boolean altersFlows();

    /** The driver a revision to this overlay emits. */
    default RateDriver driverOnRevision() {
        return RateDriver.BEHAVIOURAL_ESTIMATE;
    }

    /**
     * Expected equals contractual.
     *
     * <p>Recorded as an explicit variant rather than a null, because "we used
     * contractual life" and "we never considered life" produce identical numbers and
     * very different audit outcomes. This is the former, stated.
     *
     * @param basis why contractual life is the expectation — immateriality, absence
     *     of a reliable estimate, or a product where prepayment is not permitted
     */
    record Contractual(String basis) implements BehaviouralOverlay {

        public Contractual {
            Objects.requireNonNull(basis, "basis");
            if (basis.isBlank()) {
                throw new IllegalArgumentException(
                    "state why contractual life is the expectation; a blank basis is"
                        + " indistinguishable from never having considered it");
            }
        }

        @Override
        public String label() {
            return "CONTRACTUAL";
        }

        @Override
        public boolean altersFlows() {
            return false;
        }
    }

    /** A single annual conditional prepayment rate. */
    record ConstantPrepaymentRate(BigDecimal annualCpr) implements BehaviouralOverlay {

        public ConstantPrepaymentRate {
            Objects.requireNonNull(annualCpr, "annualCpr");
            if (annualCpr.signum() < 0 || annualCpr.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException(
                    "annualCpr is a fraction in [0,1), got " + annualCpr.toPlainString());
            }
        }

        @Override
        public String label() {
            return "CPR(" + annualCpr.toPlainString() + ")";
        }

        @Override
        public boolean altersFlows() {
            return annualCpr.signum() > 0;
        }
    }

    /** A prepayment curve varying by period — vintage and segment behaviour. */
    record CprVector(List<BigDecimal> annualCprByPeriod) implements BehaviouralOverlay {

        public CprVector {
            Objects.requireNonNull(annualCprByPeriod, "annualCprByPeriod");
            if (annualCprByPeriod.isEmpty()) {
                throw new IllegalArgumentException("a CPR vector needs at least one point");
            }
            for (BigDecimal cpr : annualCprByPeriod) {
                if (cpr.signum() < 0 || cpr.compareTo(BigDecimal.ONE) >= 0) {
                    throw new IllegalArgumentException(
                        "each CPR is a fraction in [0,1), got " + cpr.toPlainString());
                }
            }
            annualCprByPeriod = List.copyOf(annualCprByPeriod);
        }

        /** The CPR for a 1-based period, holding the last point flat beyond the vector. */
        public BigDecimal forPeriod(int periodIndex) {
            if (periodIndex < 1) {
                throw new IllegalArgumentException("periodIndex is 1-based, got " + periodIndex);
            }
            int index = Math.min(periodIndex, annualCprByPeriod.size()) - 1;
            return annualCprByPeriod.get(index);
        }

        @Override
        public String label() {
            return "CPR_VECTOR(" + annualCprByPeriod.size() + " points)";
        }

        @Override
        public boolean altersFlows() {
            return annualCprByPeriod.stream().anyMatch(c -> c.signum() > 0);
        }
    }

    /**
     * A short facility expected to roll — WCDL, gold loans, bill discounting.
     *
     * <p>Feeds the ACPIR 46(2)(ii) test on whether a renewal process is genuinely
     * substantive, which is the same question as whether a rolled facility is a
     * series of new instruments or one revolving facility in substance.
     */
    record RolloverAssumption(int expectedRollovers, BigDecimal rolloverProbability)
        implements BehaviouralOverlay {

        public RolloverAssumption {
            Objects.requireNonNull(rolloverProbability, "rolloverProbability");
            if (expectedRollovers < 0) {
                throw new IllegalArgumentException(
                    "expectedRollovers must not be negative, got " + expectedRollovers);
            }
            if (rolloverProbability.signum() < 0 || rolloverProbability.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(
                    "rolloverProbability is a fraction in [0,1], got "
                        + rolloverProbability.toPlainString());
            }
        }

        @Override
        public String label() {
            return "ROLLOVER(" + expectedRollovers + " @ " + rolloverProbability.toPlainString() + ")";
        }

        @Override
        public boolean altersFlows() {
            return expectedRollovers > 0;
        }
    }

    /**
     * Behavioural life and utilisation for a revolver.
     *
     * <p>ACPIR 46(2)(iii) mandates the supporting analysis for a card book —
     * historical default patterns, drawdown behaviour, and the effectiveness of limit
     * reduction, suspension or cancellation. The UK experience is the cautionary
     * tale: spreading card economics over a modelled behavioural life creates a
     * balance-sheet item whose entire magnitude is an estimate, and it has been a
     * recurring source of restatement.
     *
     * @param behaviouralLifeMonths modelled life
     * @param utilisationCurve      expected drawn fraction by period
     * @param analysisReference     the ACPIR 46(2)(iii) analysis supporting the life
     */
    record RevolverBehaviour(
        int behaviouralLifeMonths,
        List<BigDecimal> utilisationCurve,
        String analysisReference) implements BehaviouralOverlay {

        public RevolverBehaviour {
            Objects.requireNonNull(utilisationCurve, "utilisationCurve");
            Objects.requireNonNull(analysisReference, "analysisReference");
            if (behaviouralLifeMonths < 1) {
                throw new IllegalArgumentException(
                    "behaviouralLifeMonths must be >= 1, got " + behaviouralLifeMonths);
            }
            if (analysisReference.isBlank()) {
                throw new IllegalArgumentException(
                    "a modelled behavioural life must reference the analysis supporting it"
                        + " (ACPIR 46(2)(iii)); an unevidenced life is the defect this field exists"
                        + " to prevent");
            }
            utilisationCurve = List.copyOf(utilisationCurve);
        }

        @Override
        public String label() {
            return "REVOLVER_BEHAVIOUR(" + behaviouralLifeMonths + "m)";
        }

        @Override
        public boolean altersFlows() {
            return true;
        }
    }
}
