package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ScheduleShape;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two places where the schema's vocabulary and the engine's do not match one-to-one.
 *
 * <p>Every expected value is written out from V1's own {@code CHECK} lists and from the enums, by
 * hand. Neither mapping is derivable — {@code ANNUITY} and {@code ANNUITY_EMI} are the same thing
 * under two names, {@code STEP_SCHEDULE} is two shapes under one name, and two of the eleven product
 * strategies are no shape at all — so the mapping is a decision per value and this is where the
 * decisions are recorded.
 */
class VocabularyMappingTest {

    @Nested
    @DisplayName("compounding basis to periods a year")
    class Compounding {

        @Test
        @DisplayName("the six regular frequencies, by hand")
        void regularFrequencies() {
            assertThat(CompoundingBasis.periodsPerYear("WEEKLY")).isEqualTo(52);
            assertThat(CompoundingBasis.periodsPerYear("FORTNIGHTLY")).isEqualTo(26);
            assertThat(CompoundingBasis.periodsPerYear("MONTHLY")).isEqualTo(12);
            assertThat(CompoundingBasis.periodsPerYear("QUARTERLY")).isEqualTo(4);
            assertThat(CompoundingBasis.periodsPerYear("HALF_YEARLY")).isEqualTo(2);
            assertThat(CompoundingBasis.periodsPerYear("ANNUAL")).isEqualTo(1);
        }

        @Test
        @DisplayName("calendar months per period, where there is a whole number of them")
        void monthsPerPeriod() {
            assertThat(CompoundingBasis.monthsInPeriod("MONTHLY")).isEqualTo(1);
            assertThat(CompoundingBasis.monthsInPeriod("QUARTERLY")).isEqualTo(3);
            assertThat(CompoundingBasis.monthsInPeriod("HALF_YEARLY")).isEqualTo(6);
            assertThat(CompoundingBasis.monthsInPeriod("ANNUAL")).isEqualTo(12);
        }

        @Test
        @DisplayName("weekly, fortnightly, seasonal and custom have no months-per-period answer")
        void irregularFrequenciesRefuseAMonthCount() {
            // 52 weekly periods do not land on 12 month boundaries, and a seasonal facility sized
            // to a harvest has periods of unequal calendar length by construction. Returning 12 or
            // 6 here would place every such contract on a fabricated calendar and make its period
            // ordinal wrong by a variable amount — which no reconciliation attributes to a units
            // bug. ContractTerms.monthsInPeriod refuses the same case.
            for (String basis : new String[] {"WEEKLY", "FORTNIGHTLY", "SEASONAL", "CUSTOM"}) {
                assertThatThrownBy(() -> CompoundingBasis.monthsInPeriod(basis))
                    .as("%s must not be given a months-per-period answer", basis)
                    .isInstanceOf(PersistenceFailure.class)
                    .hasMessageContaining("no whole number of");
            }
        }

        @Test
        @DisplayName("an unlisted frequency does not fall back to monthly")
        void unknownFrequencyIsRefused() {
            // The dangerous default. Falling back to monthly would rescale every rate on the
            // contract by the ratio of the two frequencies, and the contract would look ordinary.
            assertThatThrownBy(() -> CompoundingBasis.periodsPerYear("BI_MONTHLY"))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("BI_MONTHLY")
                .hasMessageContaining("must not fall back to monthly");
        }
    }

    @Nested
    @DisplayName("projection strategy to schedule shape")
    class Strategies {

        @Test
        @DisplayName("the seven that map by name or by synonym")
        void directMappings() {
            assertThat(ProjectionStrategies.shapeFor("ANNUITY", null))
                .isEqualTo(ScheduleShape.ANNUITY_EMI);
            assertThat(ProjectionStrategies.shapeFor("BULLET", null))
                .isEqualTo(ScheduleShape.BULLET);
            assertThat(ProjectionStrategies.shapeFor("INTEREST_ONLY_BULLET", null))
                .isEqualTo(ScheduleShape.INTEREST_ONLY_BULLET);
            assertThat(ProjectionStrategies.shapeFor("BALLOON", null))
                .isEqualTo(ScheduleShape.BALLOON);
            assertThat(ProjectionStrategies.shapeFor("REVOLVING", null))
                .isEqualTo(ScheduleShape.REVOLVING);
            assertThat(ProjectionStrategies.shapeFor("DISCOUNT_INSTRUMENT", null))
                .isEqualTo(ScheduleShape.DISCOUNT_INSTRUMENT);
            assertThat(ProjectionStrategies.shapeFor("TRANCHED", null))
                .isEqualTo(ScheduleShape.TRANCHED);
        }

        @Test
        @DisplayName("MORATORIUM is an annuity; the moratorium is a term, not a shape")
        void moratoriumIsAnAnnuity() {
            // ContractTerms carries moratoriumPeriods separately, sourced from
            // contract_version.moratorium_months. Giving the moratorium its own shape would need a
            // projector that does not exist.
            assertThat(ProjectionStrategies.shapeFor("MORATORIUM", null))
                .isEqualTo(ScheduleShape.ANNUITY_EMI);
        }

        @Test
        @DisplayName("EXTERNAL_SCHEDULE is STRUCTURED, which is that shape's definition")
        void externalScheduleIsStructured() {
            // ScheduleShape.STRUCTURED: "an irregular contractual schedule that no formula
            // reproduces; supply it externally".
            assertThat(ProjectionStrategies.shapeFor("EXTERNAL_SCHEDULE", null))
                .isEqualTo(ScheduleShape.STRUCTURED);
        }

        @Test
        @DisplayName("a step ladder is STEP_UP above 1 and STEP_DOWN below it")
        void ladderDirectionComesFromTheFactor() {
            assertThat(ProjectionStrategies.shapeFor("STEP_SCHEDULE", new BigDecimal("1.100000")))
                .isEqualTo(ScheduleShape.STEP_UP);
            assertThat(ProjectionStrategies.shapeFor("STEP_SCHEDULE", new BigDecimal("0.900000")))
                .isEqualTo(ScheduleShape.STEP_DOWN);
        }

        @Test
        @DisplayName("a factor of exactly one is a flat ladder, which is an annuity")
        void aFlatLadderIsAnAnnuity() {
            // Not an error, and not a step either: a projector multiplying by one at every step
            // would report a ladder in the trace that the contract does not have.
            assertThat(ProjectionStrategies.shapeFor("STEP_SCHEDULE", BigDecimal.ONE))
                .isEqualTo(ScheduleShape.ANNUITY_EMI);
            // And the same value at a different scale, because a NUMERIC(20,12) column returns
            // 1.000000000000 and BigDecimal.equals would say that is not one.
            assertThat(ProjectionStrategies.shapeFor("STEP_SCHEDULE",
                new BigDecimal("1.000000000000"))).isEqualTo(ScheduleShape.ANNUITY_EMI);
        }

        @Test
        @DisplayName("a step ladder with no factor is refused, not defaulted to STEP_UP")
        void ladderWithoutAFactorIsRefused() {
            // The defect this catches: a step-down contract projected as a step-up has the wrong
            // present value from inception, so the solved EIR is wrong, and nothing on the contract
            // looks unusual.
            assertThatThrownBy(() -> ProjectionStrategies.shapeFor("STEP_SCHEDULE", null))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("STEP_UP and STEP_DOWN")
                .hasMessageContaining("no safe default");
        }

        @Test
        @DisplayName("REPRICING_SHORTCUT has no shape and says why")
        void repricingShortcutIsRefused() {
            // FR-508's B5.4.4 election selects an amortisation horizon, not a repayment profile.
            // Guessing ANNUITY_EMI would amortise fees to maturity on exactly the products that
            // elected not to — V1: "overstates year-1 income and reconciles against nothing".
            assertThatThrownBy(() -> ProjectionStrategies.shapeFor("REPRICING_SHORTCUT", null))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("FR-508")
                .hasMessageContaining("no projector");
        }

        @Test
        @DisplayName("an unlisted strategy is refused")
        void unknownStrategyIsRefused() {
            assertThatThrownBy(() -> ProjectionStrategies.shapeFor("AMORTISING", null))
                .isInstanceOf(PersistenceFailure.class)
                .hasMessageContaining("AMORTISING");
        }

        @Test
        @DisplayName("every value V1's check constraint admits is either mapped or refused by name")
        void allElevenStrategiesAreAccountedFor() {
            // Written out by hand from V1's product_projection_strategy_ck. The point is coverage:
            // a strategy that fell through to the default branch would be reported as "not one of
            // the eleven" when it is in fact one of them, which sends the reader to the wrong file.
            String[] admitted = {
                "ANNUITY", "BULLET", "INTEREST_ONLY_BULLET", "BALLOON", "STEP_SCHEDULE",
                "MORATORIUM", "REVOLVING", "DISCOUNT_INSTRUMENT", "TRANCHED",
                "REPRICING_SHORTCUT", "EXTERNAL_SCHEDULE"};
            assertThat(admitted).hasSize(11);
            for (String strategy : admitted) {
                try {
                    ProjectionStrategies.shapeFor(strategy, new BigDecimal("1.500000000000"));
                } catch (PersistenceFailure e) {
                    assertThat(e)
                        .as("%s must be refused for its own reason, not as an unknown value",
                            strategy)
                        .hasMessageNotContaining("is not one of the eleven values");
                }
            }
        }
    }
}
