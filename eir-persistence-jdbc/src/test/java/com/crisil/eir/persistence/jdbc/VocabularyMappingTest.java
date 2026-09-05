package com.crisil.eir.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ScheduleShape;
import java.math.BigDecimal;
import java.time.Period;
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
    @DisplayName("compounding basis to periods a year and to a calendar step")
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
        @DisplayName("the calendar step from one due date to the next, for the six fixed steps")
        void stepPerPeriod() {
            // Every figure below is derived by hand from the definition of the frequency, not read
            // back out of the switch. Two units are in play and which one a frequency uses is the
            // whole reason this returns a Period rather than a month count:
            //
            //   WEEKLY       a week is seven days by definition, so the step is 7 days.
            //                Cross-check against periodsPerYear: 52 x 7 = 364 days, which is not
            //                12 months, which is why there is no month answer for this value.
            //   FORTNIGHTLY  a fortnight is two weeks, so 2 x 7 = 14 days. 26 x 14 = 364 likewise.
            //   MONTHLY      12 periods a year over 12 months a year = 1 month.
            //   QUARTERLY    12 / 4  = 3 months.
            //   HALF_YEARLY  12 / 2  = 6 months.
            //   ANNUAL       12 / 1  = 12 months.
            //
            // The day-based pair is load-bearing downstream and not a cosmetic choice:
            // PeriodId.elapsedPeriods branches on step.toTotalMonths() > 0 and walks the schedule
            // in months on the true side and in days on the false side. A weekly step expressed in
            // months could only be 0 (rejected there as "a period step must be at least one month
            // or one day") or 1 (placing 52 instalments a year on 12 due dates).
            assertThat(CompoundingBasis.stepOf("WEEKLY")).isEqualTo(Period.ofDays(7));
            assertThat(CompoundingBasis.stepOf("FORTNIGHTLY")).isEqualTo(Period.ofDays(14));
            assertThat(CompoundingBasis.stepOf("MONTHLY")).isEqualTo(Period.ofMonths(1));
            assertThat(CompoundingBasis.stepOf("QUARTERLY")).isEqualTo(Period.ofMonths(3));
            assertThat(CompoundingBasis.stepOf("HALF_YEARLY")).isEqualTo(Period.ofMonths(6));
            // Twelve months, and this line deliberately rejects Period.ofYears(1) — the input that
            // makes it fail. Period.equals compares years, months and days field by field and does
            // not normalise, so ofYears(1) is not equal to ofMonths(12) even though both total
            // twelve months. CompoundingBasis.stepOf asks for the month form so that "every
            // month-based step is expressed in one unit and PeriodId's arithmetic has a single
            // case to handle", and that is the contract being pinned here: the years form would
            // still total 12 today only because elapsedPeriods happens to call toTotalMonths(),
            // and getMonths() anywhere in that arithmetic would silently read 0.
            assertThat(CompoundingBasis.stepOf("ANNUAL")).isEqualTo(Period.ofMonths(12));
        }

        @Test
        @DisplayName("SEASONAL and CUSTOM still have no calendar step; the other two now do")
        void irregularFrequenciesRefuseAStep() {
            // WHAT THIS TEST USED TO ASSERT, AND WHY TWO OF THE FOUR CHANGED SIDE.
            //
            // Before CompoundingBasis.stepOf replaced CompoundingBasis.monthsInPeriod, this test
            // asserted that ALL FOUR of WEEKLY, FORTNIGHTLY, SEASONAL and CUSTOM were refused,
            // with a message containing "no whole number of". Two of the four stopped being
            // refused, and the reason is a defect that was actually hit, recorded in stepOf's
            // javadoc: the old method could only answer in whole calendar months, so it refused
            // the two frequencies that are not measured in months at all, and a weekly contract
            // "resolved a perfectly good openingState and [was] then refused inside
            // ContractPeriodSource". That javadoc used to say the run died rather than
            // quarantining under FR-905; it does not, and the claim has been corrected there --
            // the barrier catches it and every weekly loan was quarantined instead.
            //
            // So the old expectation was an artefact of the return type, not a fact about weekly
            // contracts. Weekly and fortnightly retail collection is ordinary — daily-wage-earner
            // microfinance, a KCC harvest-cycle facility — and V1's
            // contract_version_compounding_basis_ck admits both, so those rows were legitimate and
            // the refusal, not the data, was the bug. Seven and fourteen days are exact calendar
            // steps needing no month arithmetic, so refusing them protected nothing. Their side of
            // the contract is now asserted positively in stepPerPeriod above; deleting the four-way
            // loop without asserting the two returns would have dropped the control entirely.
            //
            // The other two are still refused, and that refusal is real rather than a gap. A
            // facility sized to a harvest has periods of unequal calendar length BY CONSTRUCTION,
            // so there is no single step to return. Returning 12 or 6 would place every such
            // contract on a fabricated calendar and make its period ordinal wrong by a variable
            // amount, which no reconciliation attributes to a units bug.
            for (String basis : new String[] {"SEASONAL", "CUSTOM"}) {
                assertThatThrownBy(() -> CompoundingBasis.stepOf(basis))
                    .as("%s must not be given a calendar step", basis)
                    // ContractDataCondition, where this test previously asserted the supertype
                    // PersistenceFailure. ContractDataCondition does extend PersistenceFailure, so
                    // the old assertion was not false — it was too weak to catch the confusion the
                    // subtype exists to prevent. PersistenceFailure's own javadoc scopes it to
                    // "the adapter's environment being broken", and one mis-vocabularised row is
                    // not that; the ports that may answer "nothing" catch exactly
                    // ContractDataCondition, so a refusal raised as a bare PersistenceFailure
                    // would escape openingState's FR-905 quarantine and abandon the close. The
                    // input that makes this line fail is that exact regression.
                    .isInstanceOf(ContractDataCondition.class)
                    .hasMessageContaining(basis)
                    .hasMessageContaining("has no fixed step")
                    .hasMessageContaining("unequal length by construction")
                    // The message must also name where the schedule does come from, or the
                    // operator reading the quarantine line has no next action:
                    // cashflow_schedule.source = 'LMS_AUTHORITATIVE', FR-102.
                    .hasMessageContaining("FR-102");
            }
            // And the refusal is scoped to the step alone — periodsPerYear still answers for both.
            // A rate has to be wrapped in something, and the count of instalments a year is a fact
            // about the schedule even where their spacing is not uniform. 12 is not derived here:
            // it is the count a seasonal facility is conventionally reported on, which
            // CompoundingBasis records as "the least misleading available answer". Asserting it
            // matters because if this accessor began to throw too, a seasonal contract would fail
            // before openingState could answer, and would be quarantined with a vocabulary error
            // instead of measured — the same defect as the weekly one above, on a different value.
            assertThat(CompoundingBasis.periodsPerYear("SEASONAL")).isEqualTo(12);
            assertThat(CompoundingBasis.periodsPerYear("CUSTOM")).isEqualTo(12);
        }

        @Test
        @DisplayName("an unlisted frequency does not fall back to monthly, on either accessor")
        void unknownFrequencyIsRefused() {
            // The dangerous default. Falling back to monthly would rescale every rate on the
            // contract by the ratio of the two frequencies, and the contract would look ordinary.
            // BI_MONTHLY is the input because it is plausible rather than absurd: it is a real
            // word for a real frequency and it is not one of the eight V1's
            // contract_version_compounding_basis_ck admits. It is also ambiguous in ordinary usage
            // — every two months is 6 periods a year, twice a month is 24 — and neither reading is
            // 12, so a monthly fallback would be wrong whichever one the source system meant.
            // Wrong in the way that does not show up, too: contractual_rate is read as the rate
            // for ONE PERIOD (ContractTermsReader states that storage convention), so counting 12
            // periods where the contract has 6 compounds the same periodic rate twice as many
            // times a year — overstating the yield — while every due date after the first lands in
            // the wrong month.
            //
            // BOTH accessors, because they are two switch statements with two default branches and
            // this test previously exercised only periodsPerYear. That left stepOf free to fall
            // through to a monthly step for an unrecognised value, which would put the contract on
            // a monthly calendar as well: a wrong period ordinal on top of a wrongly scaled rate,
            // with nothing on the contract looking unusual. The input that makes each line fail is
            // a default branch that returns instead of throwing.
            assertThatThrownBy(() -> CompoundingBasis.periodsPerYear("BI_MONTHLY"))
                .isInstanceOf(ContractDataCondition.class)
                .hasMessageContaining("BI_MONTHLY")
                .hasMessageContaining("is not one of the eight values")
                .hasMessageContaining("must not fall back to monthly");
            assertThatThrownBy(() -> CompoundingBasis.stepOf("BI_MONTHLY"))
                .isInstanceOf(ContractDataCondition.class)
                .hasMessageContaining("BI_MONTHLY")
                .hasMessageContaining("is not one of the eight values")
                .hasMessageContaining("must not fall back to monthly");
        }

        @Test
        @DisplayName("every value V1's check constraint admits is answered or refused by name")
        void allEightFrequenciesAreAccountedFor() {
            // Written out by hand from V1's contract_version_compounding_basis_ck, the same
            // coverage argument as allElevenStrategiesAreAccountedFor below: a value that fell
            // through to the default branch would be reported as "not one of the eight values"
            // when it is in fact one of them, which sends the reader to the constraint or to a
            // data fix instead of to this switch. Six of the eight are answered by stepOf and two
            // are refused for their own reason; all eight are answered by periodsPerYear.
            String[] admitted = {
                "WEEKLY", "FORTNIGHTLY", "MONTHLY", "QUARTERLY", "HALF_YEARLY", "ANNUAL",
                "SEASONAL", "CUSTOM"};
            assertThat(admitted).hasSize(8);
            for (String basis : admitted) {
                assertThat(CompoundingBasis.periodsPerYear(basis))
                    .as("%s is one of the eight, so periodsPerYear must answer for it", basis)
                    .isPositive();
                try {
                    CompoundingBasis.stepOf(basis);
                } catch (ContractDataCondition e) {
                    assertThat(e)
                        .as("%s must be refused for its own reason, not as an unknown value", basis)
                        .hasMessageNotContaining("is not one of the eight values");
                }
            }
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
