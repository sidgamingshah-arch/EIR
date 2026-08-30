package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.calc.projection.ScheduleShape;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * {@code product.projection_strategy} to {@link ScheduleShape}.
 *
 * <p>The two vocabularies were written independently and they do not match. V1 admits eleven values
 * on the product master; {@code ScheduleShape} has ten. This class is the mapping, and it is a class
 * rather than a {@code valueOf} because three of the eleven do not map by name and two do not map at
 * all.
 *
 * <p><b>The three that need a decision, and the decision:</b>
 *
 * <ul>
 *   <li>{@code ANNUITY} → {@link ScheduleShape#ANNUITY_EMI}. Same thing, different word.
 *   <li>{@code MORATORIUM} → {@link ScheduleShape#ANNUITY_EMI}. A moratorium is not a repayment
 *       shape; it is leading periods with no principal repayment on top of one.
 *       {@code ContractTerms.moratoriumPeriods} carries it, sourced from
 *       {@code contract_version.moratorium_months}, and the instalments after the moratorium are an
 *       annuity. Mapping it to its own shape would need a projector that does not exist.
 *   <li>{@code EXTERNAL_SCHEDULE} → {@link ScheduleShape#STRUCTURED}, whose javadoc is the
 *       definition: "an irregular contractual schedule that no formula reproduces; supply it
 *       externally". FR-102 prefers an LMS-supplied schedule, and
 *       {@code cashflow_schedule.source = 'LMS_AUTHORITATIVE'} is how it arrives.
 * </ul>
 *
 * <p><b>{@code STEP_SCHEDULE} needs the step factor, and refuses without it.</b> {@code ScheduleShape}
 * splits the ladder into {@link ScheduleShape#STEP_UP} and {@link ScheduleShape#STEP_DOWN}, and the
 * product master does not say which — only the multiplier does. A factor above 1 climbs, below 1
 * falls. Defaulting to {@code STEP_UP} would put every step-down contract on a rising instalment
 * ladder: the present value of the projected flows would be wrong from inception, the solved EIR
 * would be wrong with it, and nothing on the contract would look unusual. So a
 * {@code STEP_SCHEDULE} product whose contract version carries no {@code step_factor} is refused by
 * name.
 *
 * <p><b>{@code REPRICING_SHORTCUT} has no shape and is refused.</b> It is the IFRS 9 B5.4.4
 * next-repricing election (FR-508), which V1 keeps coherent with
 * {@code product_b544_implies_next_repricing_ck}. It selects a <em>horizon</em> — amortise to the
 * next repricing date rather than to maturity — not a repayment profile, so there is no
 * {@code ScheduleShape} it corresponds to and there is no projector in {@code eir-calc} for it.
 * Refusing by name is the honest outcome; guessing {@code ANNUITY_EMI} would amortise fees to
 * maturity on precisely the products that elected not to, which V1 calls out as the failure that
 * "overstates year-1 income and reconciles against nothing".
 */
public final class ProjectionStrategies {

    private ProjectionStrategies() {
    }

    /**
     * The projector's shape for a product strategy.
     *
     * @param projectionStrategy the {@code product.projection_strategy} value
     * @param stepFactor         {@code contract_version_schedule_anchor.step_factor}, or null
     * @throws PersistenceFailure where the strategy has no shape, or a ladder has no factor
     */
    public static ScheduleShape shapeFor(String projectionStrategy, BigDecimal stepFactor) {
        String strategy = normalise(projectionStrategy);
        return switch (strategy) {
            case "ANNUITY", "MORATORIUM" -> ScheduleShape.ANNUITY_EMI;
            case "BULLET" -> ScheduleShape.BULLET;
            case "INTEREST_ONLY_BULLET" -> ScheduleShape.INTEREST_ONLY_BULLET;
            case "BALLOON" -> ScheduleShape.BALLOON;
            case "REVOLVING" -> ScheduleShape.REVOLVING;
            case "DISCOUNT_INSTRUMENT" -> ScheduleShape.DISCOUNT_INSTRUMENT;
            case "TRANCHED" -> ScheduleShape.TRANCHED;
            case "EXTERNAL_SCHEDULE" -> ScheduleShape.STRUCTURED;
            case "STEP_SCHEDULE" -> ladder(stepFactor);
            case "REPRICING_SHORTCUT" -> throw new PersistenceFailure(
                "product strategy REPRICING_SHORTCUT selects the B5.4.4 amortisation horizon"
                    + " (FR-508), not a repayment profile, and eir-calc has no projector for it."
                    + " Mapping it to ANNUITY_EMI would amortise fees to maturity on the products"
                    + " that elected not to, which V1 records as the failure that 'overstates"
                    + " year-1 income and reconciles against nothing'");
            default -> throw new PersistenceFailure(
                "projection strategy '" + projectionStrategy + "' is not one of the eleven values"
                    + " V1's product_projection_strategy_ck admits; V1 states the intent — 'the"
                    + " value list matches the projectors that exist, so an unroutable product"
                    + " fails at load rather than at solve time'");
        };
    }

    private static ScheduleShape ladder(BigDecimal stepFactor) {
        if (stepFactor == null) {
            throw new PersistenceFailure(
                "a STEP_SCHEDULE product needs contract_version_schedule_anchor.step_factor to"
                    + " choose between ScheduleShape.STEP_UP and STEP_DOWN. There is no safe"
                    + " default: a step-down contract projected as a step-up has the wrong present"
                    + " value from inception, so the solved EIR is wrong and nothing on the"
                    + " contract looks unusual");
        }
        if (stepFactor.compareTo(BigDecimal.ONE) == 0) {
            // A factor of exactly one is a flat ladder, which is an annuity. Not an error — but not
            // a step either, and calling it STEP_UP would send it to a projector that multiplies by
            // one at every step and reports a ladder in the trace that the contract does not have.
            return ScheduleShape.ANNUITY_EMI;
        }
        return stepFactor.compareTo(BigDecimal.ONE) > 0
            ? ScheduleShape.STEP_UP
            : ScheduleShape.STEP_DOWN;
    }

    private static String normalise(String projectionStrategy) {
        Objects.requireNonNull(projectionStrategy, "projectionStrategy");
        return projectionStrategy.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
