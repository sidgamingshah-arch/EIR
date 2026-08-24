package com.crisil.eir.calc.projection.blueprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.projection.ResiduePolicy;
import com.crisil.eir.calc.projection.Tranche;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The eight orthogonal dimensions as value types: what each one refuses to carry,
 * and the tags it emits to the rest of the engine.
 *
 * <p>Three things are being pinned here, and none of them is line coverage.
 *
 * <p><b>Refusals.</b> These records are the ingestion boundary. A dimension that
 * accepts a contradictory input hands the contradiction to a solver several stages
 * later, where it surfaces as an arithmetic failure with nothing left to say which
 * contract produced it. Every guard below is therefore tested for the message it
 * gives as well as the fact that it fires — the message is the whole value of
 * catching it here rather than there.
 *
 * <p><b>The {@link RateDriver} tag.</b> {@link RateProfile} declares what a change
 * to each variant <em>compensates for</em>, and that tag is the only join between
 * projection and the ADR-0006 routing table. Nothing else in the engine re-derives
 * it. A wrong tag is silent: a {@code STEP_UP_PREDETERMINED} mislabelled
 * {@code TIME_VALUE_OF_MONEY} converts a B5.4.6 catch-up into a B5.4.5 reset, and
 * reference cases 3 and 4 are the same instrument in the same month with a 627.42
 * charge in one and nothing in the other. The driver tests therefore assert the tag
 * <em>and</em> the mechanism the shipped routing table maps it to, because the tag
 * on its own is just an enum constant and the mechanism is the money.
 *
 * <p><b>The two lives.</b> EIR expected life (ACPIR 51) and the ECL horizon
 * (ACPIR 46(1)) are separate fields, and neither may be derived from the other:
 * doc 09 § 3.3 fixture O5 is a 5-year bond extendable by 3, where the horizon is 8
 * years and the expected life is possibly 5, two numbers 37.0 bp apart from one
 * contractual feature. {@link ExpectedLifeDetermination} and
 * {@link BehaviouralOverlay} are where that distinction lives, and the tests below
 * assert that a divergence is <em>reported</em> rather than reconciled away — the
 * failure mode is not a rejected contract, it is a silently collapsed pair, after
 * which no recomputation can tell a correct historical figure from a wrong one.
 *
 * <p>Figures come from doc 09 § 6 (fixtures S1–S6, O5) and the ScheduleBlueprint
 * coherence rules. Where a figure is arithmetic rather than a quoted fixture, the
 * arithmetic is stated in a comment next to it.
 */
class BlueprintDimensionsTest {

    /** Reference case 1's disbursement date. Nothing in this file reads a clock. */
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 4, 1);

    /** 12% p.a. nominal, monthly compounding: 1% per month, never 12% divided by 12. */
    private static final Rate ONE_PERCENT_MONTHLY = Rate.monthly(new BigDecimal("0.01"));

    /** The mapping shipped with the engine — specification 6.1, ADR-0006 baseline. */
    private static final RoutingTable ROUTING = RoutingTable.currentDefault();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Mechanism mechanismOf(RateProfile profile) {
        return ROUTING.mechanismFor(profile.driverOnChange());
    }

    private static RateProfile.Floating mclr() {
        return new RateProfile.Floating(
            "MCLR-1Y",
            bd("250"),
            List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)),
            ONE_PERCENT_MONTHLY);
    }

    // ------------------------------------------------------------------- 2.1

    @Nested
    @DisplayName("disbursement: how the principal reaches the borrower")
    class Disbursements {

        @Test
        @DisplayName("a single advance is a positive amount, and its notional is that amount")
        void singleAdvanceIsPositive() {
            // Zero and negative are both refused rather than normalised. A facility loaded
            // with the wrong sign would project a receipt at inception, which the solver
            // would happily strike a rate over — see Tranche, which keeps its amount
            // unsigned for the same reason.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.Single(VALUE_DATE, Money.inr("0")))
                .withMessageContaining("a disbursement advances a positive amount");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.Single(VALUE_DATE, Money.inr("-1000000")));
            assertThatNullPointerException()
                .isThrownBy(() -> new DisbursementProfile.Single(null, Money.inr("1000000")));

            DisbursementProfile single =
                new DisbursementProfile.Single(VALUE_DATE, Money.inr("1000000"));
            assertThat(single.notional()).isEqualTo(Money.inr("1000000"));
            assertThat(single.label()).isEqualTo("SINGLE");
        }

        @Test
        @DisplayName("a tranched profile needs a projected schedule and a non-negative tolerance")
        void tranchedNeedsAProjectedSchedule() {
            // "With one draw use Single" is the substance of the guard: an empty projected
            // list would make notional() index position 0 of nothing, and ST-6 (Σ tranche
            // draws = notional) would compare a notional against no draws at all.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.Tranched(List.of(), List.of(), bd("0.05")))
                .withMessageContaining("with one draw use Single");
            // A negative tolerance would trigger a DISBURSEMENT_TIMING re-estimation on
            // every draw, which is precisely the book-churn the cumulative test exists to
            // avoid (FR-512).
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.Tranched(
                    List.of(Tranche.of(VALUE_DATE, 0, Money.inr("400000"))), List.of(), bd("-0.01")))
                .withMessageContaining("tolerance must not be negative");
        }

        @Test
        @DisplayName("projected and drawn are separate figures; notional never reads the actual draws")
        void projectedAndDrawnAreSeparateFigures() {
            // 400,000 + 350,000 + 250,000 = 1,000,000 projected at financial closure,
            // against 400,000 actually drawn. The two must not be confused: ST-6 checks
            // the projected ladder against notional, and a notional() that summed actual
            // would report a 400,000 facility for the whole construction period and then
            // grow — a moving notional that no invariant could anchor.
            List<Tranche> projected = List.of(
                Tranche.of(VALUE_DATE, 0, Money.inr("400000")),
                Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("350000")),
                Tranche.of(LocalDate.of(2027, 4, 1), 12, Money.inr("250000")));
            DisbursementProfile.Tranched drawnOnce = new DisbursementProfile.Tranched(
                projected, List.of(projected.get(0)), bd("0.05"));

            assertThat(drawnOnce.notional()).isEqualTo(Money.inr("1000000"));
            assertThat(drawnOnce.drawnToDate()).isEqualTo(Money.inr("400000"));
            assertThat(drawnOnce.label()).isEqualTo("TRANCHED(3 projected, 1 drawn)");

            // Before first drawdown the facility still has its full projected notional.
            DisbursementProfile.Tranched undrawn =
                new DisbursementProfile.Tranched(projected, List.of(), bd("0.05"));
            assertThat(undrawn.notional()).isEqualTo(Money.inr("1000000"));
            assertThat(undrawn.drawnToDate()).isEqualTo(Money.zero(Money.INR));
        }

        @Test
        @DisplayName("a revolver's utilisation is a fraction of the limit, and its notional is the drawn part")
        void utilisationDrivenBoundsTheFractionAndScalesTheLimit() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.UtilisationDriven(
                    Money.inr("0"), bd("0.60")))
                .withMessageContaining("limit must be positive");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.UtilisationDriven(
                    Money.inr("1000000"), bd("-0.01")))
                .withMessageContaining("averageUtilisation is a fraction in [0,1]");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new DisbursementProfile.UtilisationDriven(
                    Money.inr("1000000"), bd("1.01")))
                .withMessageContaining("averageUtilisation is a fraction in [0,1]");

            // The interval is closed at both ends: a fully drawn limit is an ordinary
            // overdraft and an undrawn one is an ordinary sanctioned facility.
            assertThat(new DisbursementProfile.UtilisationDriven(Money.inr("1000000"), BigDecimal.ONE)
                .notional()).isEqualTo(Money.inr("1000000"));
            assertThat(new DisbursementProfile.UtilisationDriven(Money.inr("1000000"), BigDecimal.ZERO)
                .notional()).isEqualTo(Money.zero(Money.INR));

            // 1,000,000 x 0.60 = 600,000. The notional is the expected drawn balance, not
            // the sanctioned limit: ACPIR 54 permits an approximation on a revolver
            // precisely because there is no drawdown schedule, and striking a rate on the
            // undrawn limit would spread the fee over money that never left the bank.
            DisbursementProfile revolver =
                new DisbursementProfile.UtilisationDriven(Money.inr("1000000"), bd("0.60"));
            assertThat(revolver.notional()).isEqualTo(Money.inr("600000"));
            assertThat(revolver.notional()).isNotEqualTo(Money.inr("1000000"));
            assertThat(revolver.label()).isEqualTo("UTILISATION_DRIVEN");
        }
    }

    // ------------------------------------------------------------------- 2.2

    @Nested
    @DisplayName("principal: how the principal is returned")
    class Principals {

        @Test
        @DisplayName("a balloon terminal amount is positive, and the message names the alternatives")
        void balloonTerminalAmountIsPositive() {
            // A zero terminal amount is not a degenerate balloon, it is a different
            // product, and ST-5 would then require the ladder to amortise "to exactly
            // zero" through a code path built to leave a lump outstanding.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.Balloon(Money.inr("0")))
                .withMessageContaining("BulletAtMaturity")
                .withMessageContaining("LevelAnnuity");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.Balloon(Money.inr("-400000")));

            // Doc 09 S2: balloon 400,000 at period 24. The label carries the figure
            // because the computation trace has to say which balloon was projected.
            assertThat(new PrincipalProfile.Balloon(Money.inr("400000")).label())
                .isEqualTo("BALLOON(INR 400000)");
        }

        @Test
        @DisplayName("only a balloon reports a terminal lump")
        void onlyABalloonCarriesATerminalLump() {
            // hasTerminalLump() is what tells the ladder builder to amortise toward a
            // figure rather than to zero (ST-5). A false positive silently withholds
            // principal the borrower has already repaid; a false negative amortises away
            // the lump the borrower still owes. Both are wrong by the size of the balloon,
            // which is why the flag is asserted across every variant rather than on the
            // one that returns true.
            assertThat(new PrincipalProfile.Balloon(Money.inr("400000")).hasTerminalLump()).isTrue();
            assertThat(new PrincipalProfile.LevelAnnuity().hasTerminalLump()).isFalse();
            assertThat(new PrincipalProfile.EqualPrincipal().hasTerminalLump()).isFalse();
            assertThat(new PrincipalProfile.BulletAtMaturity().hasTerminalLump()).isFalse();
            assertThat(new PrincipalProfile.NoneUntilMaturity().hasTerminalLump()).isFalse();
            assertThat(new PrincipalProfile.StepLadder(
                bd("1.10"), 6, PrincipalProfile.StepDirection.UP).hasTerminalLump()).isFalse();
            assertThat(new PrincipalProfile.Sculpted(List.of(
                new PrincipalProfile.PrincipalStep(1, Money.inr("1000000")))).hasTerminalLump())
                .isFalse();
        }

        @Test
        @DisplayName("a sculpted ladder needs at least one step, and totals what its rungs repay")
        void sculptedLadderTotalsItsRungs() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.Sculpted(List.of()))
                .withMessageContaining("at least one step");

            // A zero rung is the point of sculpting: project finance sized to projected
            // free cash flow has periods that repay nothing, and refusing them would force
            // the caller to fake a token repayment. Negative is refused — a sculpted step
            // repays principal, it does not advance it; that is the disbursement profile's
            // job and mixing the two is how a facility ends up with two notionals.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.PrincipalStep(1, Money.inr("-1")))
                .withMessageContaining("non-negative");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.PrincipalStep(0, Money.inr("1000")))
                .withMessageContaining("periodIndex is 1-based");

            // 300,000 + 0 + 700,000 = 1,000,000, which is what ST-3 compares against the
            // principal advanced.
            PrincipalProfile.Sculpted sculpted = new PrincipalProfile.Sculpted(List.of(
                new PrincipalProfile.PrincipalStep(1, Money.inr("300000")),
                new PrincipalProfile.PrincipalStep(2, Money.zero(Money.INR)),
                new PrincipalProfile.PrincipalStep(3, Money.inr("700000"))));
            assertThat(sculpted.total()).isEqualTo(Money.inr("1000000"));
            assertThat(sculpted.label()).isEqualTo("SCULPTED(3 steps)");
        }

        @Test
        @DisplayName("a step ladder refuses a direction its factor contradicts")
        void stepLadderRefusesAContradictoryDirection() {
            // The guard is not pedantry. STEP_UP_PREDETERMINED is the driver a change to
            // this ladder emits, and the direction is what a reviewer reads to decide
            // whether the instalment stream that was projected is the one the contract
            // describes. A factor of 0.90 labelled UP would produce a declining ladder
            // under a rising label, and the label is what goes in the trace.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.StepLadder(
                    bd("0.90"), 6, PrincipalProfile.StepDirection.UP))
                .withMessageContaining("direction UP with factor 0.90 steps down");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.StepLadder(
                    bd("1.10"), 6, PrincipalProfile.StepDirection.DOWN))
                .withMessageContaining("direction DOWN with factor 1.10 steps up");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.StepLadder(
                    bd("0"), 6, PrincipalProfile.StepDirection.UP))
                .withMessageContaining("factor must be positive");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new PrincipalProfile.StepLadder(
                    bd("1.10"), 0, PrincipalProfile.StepDirection.UP))
                .withMessageContaining("everyNPeriods must be >= 1");

            // Doc 09 S3: +10% every 6 periods, base instalment 40,861.10 stepping to
            // 44,947.21. The factor and the period count both go into the label because a
            // step ladder with the right factor on the wrong cadence is a different
            // instrument with the same rate.
            assertThat(new PrincipalProfile.StepLadder(
                bd("1.10"), 6, PrincipalProfile.StepDirection.UP).label())
                .isEqualTo("STEP_UP(x1.10 every 6)");

            // The boundary: a factor of exactly one satisfies both directions, because
            // neither comparison is strict. It is a ladder that steps nothing, and the
            // type permits it in both directions — stated here so that the guard's actual
            // reach is on the record rather than assumed to be tighter than it is.
            assertThat(new PrincipalProfile.StepLadder(
                BigDecimal.ONE, 6, PrincipalProfile.StepDirection.UP).label())
                .isEqualTo("STEP_UP(x1 every 6)");
            assertThat(new PrincipalProfile.StepLadder(
                BigDecimal.ONE, 6, PrincipalProfile.StepDirection.DOWN).label())
                .isEqualTo("STEP_DOWN(x1 every 6)");
        }
    }

    // ------------------------------------------------------------------- 2.3

    @Nested
    @DisplayName("interest servicing: when interest leaves, and whether it compounds")
    class Servicing {

        @Test
        @DisplayName("capitalisation is the only servicing that compounds")
        void onlyCapitalisationCompounds() {
            // Doc 09 S5 against S6, same loan and same 12-period holiday: capitalised
            // interest of 126,825.03 against a simple accrual of 120,000.00, an EIR of
            // 1.02108050% per month against 0.99527906%, 34.6 bp apart. compounds() is the
            // single boolean that separates those two numbers, and every other consumer in
            // the engine keys off it rather than re-deriving the treatment. A flipped flag
            // on DeferredSimple would price a FITL as an education loan.
            assertThat(new InterestServicing.CapitalisedEachPeriod().compounds()).isTrue();
            assertThat(new InterestServicing.ServicedEachPeriod().compounds()).isFalse();
            assertThat(new InterestServicing.DeferredSimple(LocalDate.of(2027, 4, 1)).compounds())
                .isFalse();
            assertThat(new InterestServicing.DiscountedUpfront().compounds()).isFalse();
            assertThat(new InterestServicing.ServicedThenCombined(6).compounds()).isFalse();

            assertThat(new InterestServicing.DeferredSimple(LocalDate.of(2027, 4, 1)).label())
                .isEqualTo("DEFERRED_SIMPLE(2027-04-01)");
        }

        @Test
        @DisplayName("an interest-only phase spans at least one period, and a deferral names its settlement date")
        void servicingRefusesDegenerateInputs() {
            // Zero interest-only periods is ServicedEachPeriod under a different name, and
            // the message says so. Two spellings of one profile is how a shape enum starts
            // to grow combinations again.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new InterestServicing.ServicedThenCombined(0))
                .withMessageContaining("for none use ServicedEachPeriod");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new InterestServicing.ServicedThenCombined(-6));
            // The lump has to fall due somewhere: without a date there is no flow to
            // discount and the accrued interest would simply vanish from the vector.
            assertThatNullPointerException()
                .isThrownBy(() -> new InterestServicing.DeferredSimple(null));
        }

        @Test
        @DisplayName("the compounding flag is what gates the moratorium pairing, in both directions")
        void theCompoundingFlagGatesTheMoratoriumPairing() {
            // Servicing and moratorium are separate dimensions, but not every pair means
            // anything, and this is the pair worth money: a FULL_INTEREST_CAPITALISED
            // holiday serviced on a simple-deferral basis, or the reverse. ST-11 rejects
            // both at construction by reading InterestServicing.compounds(), so this
            // asserts the flag through the composition that consumes it rather than only
            // as a boolean — a correct flag with the rule deleted is the same defect as a
            // flipped flag.
            assertThatThrownBy(() -> blueprint(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.DeferredSimple(LocalDate.of(2027, 4, 1)),
                Moratorium.fullyCapitalised(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incoherent blueprint (ST-11)")
                .hasMessageContaining("requires servicing that compounds")
                .hasMessageContaining("34.6 bp");

            assertThatThrownBy(() -> blueprint(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                new Moratorium(12, Moratorium.MoratoriumKind.FULL_INTEREST_DEFERRED_SIMPLE,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not compound");

            // The matched pair is an education loan, and it composes.
            assertThat(blueprint(
                new PrincipalProfile.LevelAnnuity(),
                new InterestServicing.CapitalisedEachPeriod(),
                Moratorium.fullyCapitalised(12)).describe())
                .contains("CAPITALISED_EACH_PERIOD")
                .contains("MORATORIUM(12 FULL_INTEREST_CAPITALISED)");
        }
    }

    // ------------------------------------------------------------------- 2.4

    @Nested
    @DisplayName("moratorium: kind and term effect")
    class Moratoria {

        @Test
        @DisplayName("zero periods and the NONE kind are the same statement, so neither may appear alone")
        void zeroPeriodsAndTheNoneKindAgree() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new Moratorium(-1, Moratorium.MoratoriumKind.NONE,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM))
                .withMessageContaining("periods must not be negative");
            // A PRINCIPAL_ONLY holiday of zero periods reads as a holiday in every
            // downstream trace while suspending nothing.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new Moratorium(0, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM))
                .withMessageContaining("must be kind NONE");
            // And the reverse: six periods of nothing in particular. The message asks for
            // the kind rather than guessing, because PRINCIPAL_ONLY and
            // FULL_INTEREST_CAPITALISED are 34.6 bp apart on the same six periods.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new Moratorium(6, Moratorium.MoratoriumKind.NONE,
                    Moratorium.MoratoriumTermEffect.EXTEND_TERM))
                .withMessageContaining("state the kind the holiday actually is");
        }

        @Test
        @DisplayName("the factories do not degrade to none() when handed zero periods")
        void factoriesRefuseZeroPeriods() {
            // The tempting behaviour is for principalOnly(0) to return none(). It would
            // hide an ingestion defect: a moratorium field that arrived as zero because it
            // was absent from the feed is not the same fact as a contract with no holiday,
            // and only one of the two should reach a projection unchallenged.
            assertThatIllegalArgumentException().isThrownBy(() -> Moratorium.principalOnly(0));
            assertThatIllegalArgumentException().isThrownBy(() -> Moratorium.fullyCapitalised(0));

            assertThat(Moratorium.none().isPresent()).isFalse();
            assertThat(Moratorium.none().kind()).isEqualTo(Moratorium.MoratoriumKind.NONE);
            assertThat(Moratorium.principalOnly(6).isPresent()).isTrue();
            assertThat(Moratorium.principalOnly(6).kind())
                .isEqualTo(Moratorium.MoratoriumKind.PRINCIPAL_ONLY);
            assertThat(Moratorium.fullyCapitalised(12).kind())
                .isEqualTo(Moratorium.MoratoriumKind.FULL_INTEREST_CAPITALISED);
        }

        @Test
        @DisplayName("every factory chooses EXTEND_TERM, so any other term effect must be stated explicitly")
        void everyFactoryChoosesExtendTerm() {
            // This is a trap, and pinning it is the point. The term effect changes the
            // flow vector: extending maturity by the holiday, compressing the remaining
            // instalments to hold maturity, and pushing the arrears into a terminal lump
            // are three different cash-flow shapes and therefore three different EIRs from
            // one holiday. A caller who wanted COMPRESS_REMAINING and reached for the
            // convenience factory gets EXTEND_TERM silently, because there is no factory
            // for the other two.
            assertThat(Moratorium.none().termEffect())
                .isEqualTo(Moratorium.MoratoriumTermEffect.EXTEND_TERM);
            assertThat(Moratorium.principalOnly(6).termEffect())
                .isEqualTo(Moratorium.MoratoriumTermEffect.EXTEND_TERM);
            assertThat(Moratorium.fullyCapitalised(12).termEffect())
                .isEqualTo(Moratorium.MoratoriumTermEffect.EXTEND_TERM);

            // The distinction is not decorative: two holidays that agree on periods and
            // kind and differ only in term effect are different values, so a projector
            // that ignored the field could not produce the same result for both.
            assertThat(Moratorium.principalOnly(6)).isNotEqualTo(new Moratorium(
                6, Moratorium.MoratoriumKind.PRINCIPAL_ONLY,
                Moratorium.MoratoriumTermEffect.COMPRESS_REMAINING));
        }

        @ParameterizedTest
        @EnumSource(value = Moratorium.MoratoriumKind.class, names = "NONE", mode =
            EnumSource.Mode.EXCLUDE)
        @DisplayName("kind and term effect are deliberately uncoupled: every real kind takes every effect")
        void everyKindAcceptsEveryTermEffect(Moratorium.MoratoriumKind kind) {
            // Uncoupled on purpose. A principal holiday can extend the term or compress the
            // remaining instalments; a capitalising holiday can balloon its arrears. The
            // combinations are real products, so the record carries the pair rather than an
            // enum of blessed pairings — the same reasoning that keeps servicing and
            // moratorium apart. This test fails the day someone adds a coupling rule here,
            // which is the point: the coherence rules that do exist live in
            // ScheduleBlueprint, where the other six dimensions are visible.
            for (Moratorium.MoratoriumTermEffect effect : Moratorium.MoratoriumTermEffect.values()) {
                Moratorium holiday = new Moratorium(6, kind, effect);
                assertThat(holiday.isPresent()).isTrue();
                assertThat(holiday.kind()).isEqualTo(kind);
                assertThat(holiday.termEffect()).isEqualTo(effect);
            }
        }
    }

    // ------------------------------------------------------------------- 2.5

    @Nested
    @DisplayName("rate profile: the RateDriver tag that joins projection to ADR-0006 routing")
    class RateDrivers {

        @Test
        @DisplayName("a renegotiated fixed rate is a modification, not a reset — the trap row")
        void fixedRateChangeIsANegotiation() {
            // The decision table's trap: a fixed-rate loan whose rate is renegotiated and
            // an EBLR reset both "look like the rate moved", and they are a modification
            // and a reset respectively. Fixed has no change by the instrument's own terms,
            // so any change to it arrived by negotiation and has to run the substantiality
            // test rather than either mechanism.
            RateProfile fixed = new RateProfile.Fixed(ONE_PERCENT_MONTHLY);

            assertThat(fixed.driverOnChange()).isEqualTo(RateDriver.NEGOTIATED);
            assertThat(mechanismOf(fixed)).isEqualTo(Mechanism.MODIFICATION_TEST);
            assertThat(fixed.rateType()).isEqualTo(RateType.FIXED);
            // The router's validity check reads this: a market driver cannot arise on a
            // fixed instrument by its own terms.
            assertThat(fixed.driverOnChange().isMarketMovement()).isFalse();
            assertThat(fixed.rateForPeriod(1)).isEqualTo(ONE_PERCENT_MONTHLY);
            assertThat(fixed.rateForPeriod(240)).isEqualTo(ONE_PERCENT_MONTHLY);
        }

        @Test
        @DisplayName("benchmark and index movement compensate for the time value of money and reset")
        void benchmarkMovementResets() {
            RateProfile floating = mclr();
            RateProfile collared = new RateProfile.FloatingWithCollar(mclr(), bd("0.012"), bd("0.008"));
            RateProfile indexed = new RateProfile.InflationIndexed("CPI-IW", ONE_PERCENT_MONTHLY);

            for (RateProfile profile : List.of(floating, collared, indexed)) {
                assertThat(profile.driverOnChange())
                    .as("driver for %s", profile.label())
                    .isEqualTo(RateDriver.TIME_VALUE_OF_MONEY);
                assertThat(mechanismOf(profile))
                    .as("mechanism for %s", profile.label())
                    .isEqualTo(Mechanism.RESET);
                assertThat(profile.rateType()).isEqualTo(RateType.FLOATING);
                assertThat(profile.driverOnChange().isMarketMovement()).isTrue();
            }

            // The collar is a contractual term that was there all along, not an event. A
            // binding cap changes the cash flows and the driver stays TIME_VALUE_OF_MONEY;
            // tagging the binding separately would fabricate a catch-up out of a term the
            // instrument always had.
            assertThat(collared.driverOnChange()).isEqualTo(floating.driverOnChange());
        }

        @Test
        @DisplayName("a pre-determined coupon step is not a market movement and catches up")
        void predeterminedStepsCatchUp() {
            // The April 2026 tentative decision puts this outside B5.4.5: a step-up bond's
            // ladder compensates for neither the time value of money nor credit risk, so it
            // routes to a B5.4.6 catch-up. This is the exact swap the tag exists to prevent
            // — mislabel it TIME_VALUE_OF_MONEY and the same event books nothing instead of
            // a restatement.
            RateProfile stepped = new RateProfile.StepCoupon(List.of(
                new RateProfile.CouponStep(1, Rate.monthly(bd("0.008"))),
                new RateProfile.CouponStep(7, Rate.monthly(bd("0.009")))));

            assertThat(stepped.driverOnChange()).isEqualTo(RateDriver.STEP_UP_PREDETERMINED);
            assertThat(mechanismOf(stepped)).isEqualTo(Mechanism.CATCH_UP);
            assertThat(mechanismOf(stepped)).isNotEqualTo(Mechanism.RESET);
            // Known at inception and unrelated to any benchmark, so the instrument is
            // FIXED and its driver is not a market movement. Both halves matter: the
            // router pairs the rate type with the driver, and FLOATING here would let a
            // reset reading through on an instrument that never reprices.
            assertThat(stepped.rateType()).isEqualTo(RateType.FIXED);
            assertThat(stepped.driverOnChange().isMarketMovement()).isFalse();
        }

        @Test
        @DisplayName("the ratchet trigger picks between two drivers that route alike today")
        void theRatchetTriggerDecidesTheDriver() {
            List<RateProfile.RatchetTier> tiers = List.of(
                new RateProfile.RatchetTier("net debt / EBITDA <= 3.0x", bd("-25")),
                new RateProfile.RatchetTier("net debt / EBITDA > 3.0x", bd("25")));

            RateProfile covenant = new RateProfile.Ratchet(
                tiers, RateProfile.RatchetTrigger.FINANCIAL_COVENANT, ONE_PERCENT_MONTHLY);
            RateProfile rating = new RateProfile.Ratchet(
                tiers, RateProfile.RatchetTrigger.EXTERNAL_RATING, ONE_PERCENT_MONTHLY);
            RateProfile esg = new RateProfile.Ratchet(
                tiers, RateProfile.RatchetTrigger.SUSTAINABILITY_KPI, ONE_PERCENT_MONTHLY);

            assertThat(covenant.driverOnChange())
                .isEqualTo(RateDriver.CREDIT_RATCHET_PREDETERMINED);
            assertThat(rating.driverOnChange()).isEqualTo(RateDriver.CREDIT_RATCHET_PREDETERMINED);
            assertThat(esg.driverOnChange()).isEqualTo(RateDriver.ESG_LINKED);

            // All three route to a catch-up under the shipped table, and that is exactly
            // why the drivers must stay distinct: a collapse to one tag would be invisible
            // today and would make the next wording change — one that moves ESG alone — a
            // code change instead of a mapping edit. The assertion that the two tags differ
            // is the one that fails on a collapse; the mechanism assertions on their own
            // would not.
            assertThat(esg.driverOnChange()).isNotEqualTo(covenant.driverOnChange());
            assertThat(mechanismOf(covenant)).isEqualTo(Mechanism.CATCH_UP);
            assertThat(mechanismOf(rating)).isEqualTo(Mechanism.CATCH_UP);
            assertThat(mechanismOf(esg)).isEqualTo(Mechanism.CATCH_UP);
            assertThat(esg.label()).isEqualTo("RATCHET(SUSTAINABILITY_KPI, 2 tiers)");
        }

        @Test
        @DisplayName("a market credit-spread reset carries its own driver, distinct from benchmark repricing")
        void marketSpreadResetIsItsOwnDriver() {
            RateProfile spread = new RateProfile.MarketSpreadReset(
                List.of(LocalDate.of(2027, 4, 1)), ONE_PERCENT_MONTHLY);

            assertThat(spread.driverOnChange()).isEqualTo(RateDriver.CREDIT_RISK_MARKET);
            assertThat(mechanismOf(spread)).isEqualTo(Mechanism.RESET);
            // Both reset today. The distinction is kept so that the narrower Alternative A
            // reading — which excludes borrower-specific credit spread from B5.4.5 — stays
            // expressible as a reroute of one driver. Collapsing the two would route
            // identically today and would make that reading unreachable without a rebuild,
            // so this inequality is the assertion doing the work.
            assertThat(spread.driverOnChange()).isNotEqualTo(mclr().driverOnChange());
            assertThat(mechanismOf(spread)).isEqualTo(mechanismOf(mclr()));
            assertThat(spread.driverOnChange().isMarketMovement()).isTrue();
        }
    }

    // ------------------------------------------------------------------- 2.5 (mechanics)

    @Nested
    @DisplayName("rate profile: the rate actually in force")
    class RateMechanics {

        @Test
        @DisplayName("a floating profile names its benchmark and orders its resets strictly")
        void floatingNamesItsBenchmarkAndOrdersItsResets() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.Floating(
                    "  ", bd("250"), List.of(), ONE_PERCENT_MONTHLY))
                .withMessageContaining("benchmarkId must name the benchmark");

            // Strictly ascending, so a repeated date is refused as well as an inverted
            // pair. Two resets on one date would give nextResetAfter two answers, and the
            // B5.4.4 election amortises to whichever it returned.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.Floating("MCLR-1Y", bd("250"),
                    List.of(LocalDate.of(2027, 4, 1), LocalDate.of(2026, 10, 1)),
                    ONE_PERCENT_MONTHLY))
                .withMessageContaining("reset dates must be strictly ascending");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.Floating("MCLR-1Y", bd("250"),
                    List.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)),
                    ONE_PERCENT_MONTHLY))
                .withMessageContaining("does not follow");

            assertThat(mclr().label()).isEqualTo("FLOATING(MCLR-1Y + 250bp)");
        }

        @Test
        @DisplayName("the next reset is strictly after the as-of date, and runs out at the end")
        void nextResetIsStrictlyAfterTheAsOfDate() {
            RateProfile.Floating floating = mclr();

            // Strictly after. A reset dated on the measurement date has already happened,
            // and returning it would have the B5.4.4 shortcut amortise the unamortised fee
            // to a date that is not in the future — a zero-length amortisation period,
            // which recognises the whole balance at once for no reason the ledger can
            // explain.
            assertThat(floating.nextResetAfter(LocalDate.of(2026, 10, 1)))
                .contains(LocalDate.of(2027, 4, 1));
            assertThat(floating.nextResetAfter(LocalDate.of(2026, 9, 30)))
                .contains(LocalDate.of(2026, 10, 1));
            assertThat(floating.nextResetAfter(VALUE_DATE)).contains(LocalDate.of(2026, 10, 1));
            // Past the last reset there is nothing to amortise to, and the caller has to
            // handle that rather than be handed a stale date.
            assertThat(floating.nextResetAfter(LocalDate.of(2027, 4, 1))).isEmpty();
            assertThat(floating.nextResetAfter(LocalDate.of(2030, 1, 1)))
                .isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("a collar needs a bound, clamps in both directions, and keeps the base frequency")
        void collarClampsInBothDirections() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.FloatingWithCollar(mclr(), null, null))
                .withMessageContaining("with neither use Floating");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.FloatingWithCollar(
                    mclr(), bd("0.005"), bd("0.009")))
                .withMessageContaining("is below floor");

            // The base rate is 1% per period. Cap 0.8% binds downward, floor 1.2% binds
            // upward, and a collar that straddles the rate leaves it alone. Note that the
            // bounds are compared against the *periodic* rate, which is what
            // rateForPeriod returns.
            assertThat(new RateProfile.FloatingWithCollar(mclr(), bd("0.008"), null)
                .rateForPeriod(1).periodic()).isEqualByComparingTo("0.008");
            assertThat(new RateProfile.FloatingWithCollar(mclr(), null, bd("0.012"))
                .rateForPeriod(1).periodic()).isEqualByComparingTo("0.012");
            assertThat(new RateProfile.FloatingWithCollar(mclr(), bd("0.02"), bd("0.005"))
                .rateForPeriod(1).periodic()).isEqualByComparingTo("0.01");
            // Cap equal to floor is a pinned rate, which is a real term and is accepted.
            assertThat(new RateProfile.FloatingWithCollar(mclr(), bd("0.009"), bd("0.009"))
                .rateForPeriod(1).periodic()).isEqualByComparingTo("0.009");

            // The clamped rate keeps the base's compounding frequency. A Rate that lost it
            // would annualise as an effective annual rate — 0.8% instead of 10.03% — and
            // the loss would be silent because both are valid Rates.
            assertThat(new RateProfile.FloatingWithCollar(mclr(), bd("0.008"), null)
                .rateForPeriod(1).periodsPerYear()).isEqualTo(12);
        }

        @Test
        @DisplayName("a coupon rung is in force from its own period, inclusive")
        void aCouponRungIsInForceFromItsOwnPeriod() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.StepCoupon(List.of()))
                .withMessageContaining("at least one step");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.CouponStep(0, ONE_PERCENT_MONTHLY))
                .withMessageContaining("fromPeriod is 1-based");

            // Steps at periods 1, 7 and 13 — doc 09 S3's every-six-periods cadence.
            RateProfile.StepCoupon ladder = new RateProfile.StepCoupon(List.of(
                new RateProfile.CouponStep(1, Rate.monthly(bd("0.008"))),
                new RateProfile.CouponStep(7, Rate.monthly(bd("0.009"))),
                new RateProfile.CouponStep(13, Rate.monthly(bd("0.010")))));

            assertThat(ladder.rateForPeriod(1).periodic()).isEqualByComparingTo("0.008");
            assertThat(ladder.rateForPeriod(6).periodic()).isEqualByComparingTo("0.008");
            // The boundary. A step "from period 7" is in force *in* period 7; an exclusive
            // comparison here would bill period 7 at the old coupon and shift the whole
            // ladder one period late, understating income for the life of the bond by one
            // period of the step.
            assertThat(ladder.rateForPeriod(7).periodic()).isEqualByComparingTo("0.009");
            assertThat(ladder.rateForPeriod(12).periodic()).isEqualByComparingTo("0.009");
            assertThat(ladder.rateForPeriod(13).periodic()).isEqualByComparingTo("0.010");
            // Held flat past the last rung: a 24-period bond with three steps does not run
            // out of coupon at period 19.
            assertThat(ladder.rateForPeriod(24).periodic()).isEqualByComparingTo("0.010");
            assertThat(ladder.label()).isEqualTo("STEP_COUPON(3 steps)");
        }

        @Test
        @Disabled("DEFECT: StepCoupon neither validates nor sorts its ladder, and its lookup is"
            + " order-dependent. See the comment below.")
        @DisplayName("DEFECT: a coupon ladder supplied out of order returns the wrong rate")
        void aCouponLadderSuppliedOutOfOrderReturnsTheWrongRate() {
            // The same ladder as above, supplied newest-first — the order a coupon-schedule
            // table sorted by date descending arrives in.
            //
            // rateForPeriod walks the list and keeps the last rung whose fromPeriod is at
            // or below the index, so on a descending list the *earliest* matching rung wins
            // instead of the latest. Past period 1 every rung matches, so the period-1 rung
            // is always the one that survives the walk: this ladder returns 0.008 for
            // period 8, for period 20, and for every period of the bond's life. The ladder
            // does not merely mis-step, it never steps at all. Nothing refuses the input and
            // nothing sorts it: RateProfile.Floating validates its reset dates as strictly
            // ascending and ScheduleCalendar validates its custom due dates the same way,
            // but StepCoupon validates only that the ladder is non-empty.
            //
            // Consequence: the contractual rate is wrong for the whole post-step tail of a
            // step-up bond, and this profile is the one whose changes route to a B5.4.6
            // catch-up — so the restatement is computed by discounting revised flows at a
            // rate the contract never bore. It is silent: every rung is individually valid
            // and the label still reads STEP_COUPON(3 steps).
            //
            // Two fixes close it. A constructor guard mirroring Floating ("coupon steps
            // must be strictly ascending") is the one consistent with this package, and
            // would additionally reject a duplicated fromPeriod, which today resolves to
            // whichever rung appears last. Sorting internally would also work but accepts a
            // contradictory ladder silently, which the house style refuses elsewhere. This
            // test is written against the order-independent-lookup reading: under the
            // constructor-guard fix it errors on the first statement instead of failing on
            // the assertion, and should then be rewritten as a refusal test.
            RateProfile.StepCoupon descending = new RateProfile.StepCoupon(List.of(
                new RateProfile.CouponStep(13, Rate.monthly(bd("0.010"))),
                new RateProfile.CouponStep(7, Rate.monthly(bd("0.009"))),
                new RateProfile.CouponStep(1, Rate.monthly(bd("0.008")))));

            assertThat(descending.rateForPeriod(8).periodic()).isEqualByComparingTo("0.009");
            assertThat(descending.rateForPeriod(20).periodic()).isEqualByComparingTo("0.010");
        }

        @Test
        @DisplayName("an identifier or a condition that names nothing is refused")
        void identifiersMustNameSomething() {
            // A blank benchmark, index or ratchet condition survives ingestion and dies in
            // review: the projection runs, the trace says the rate reprices off "" and
            // nobody can say against what. These records are the only place that can still
            // refuse it.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.InflationIndexed("", ONE_PERCENT_MONTHLY))
                .withMessageContaining("indexId must name the index");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.RatchetTier("   ", bd("25")))
                .withMessageContaining("must state its condition");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateProfile.Ratchet(
                    List.of(), RateProfile.RatchetTrigger.EXTERNAL_RATING, ONE_PERCENT_MONTHLY))
                .withMessageContaining("at least one tier");

            assertThat(new RateProfile.InflationIndexed("CPI-IW", ONE_PERCENT_MONTHLY).label())
                .isEqualTo("INFLATION_INDEXED(CPI-IW)");
        }
    }

    // ------------------------------------------------------------------- 2.8

    @Nested
    @DisplayName("schedule calendar: when instalments fall due")
    class Calendars {

        private static final LocalDate SATURDAY = LocalDate.of(2026, 6, 6);
        private static final LocalDate SUNDAY = LocalDate.of(2026, 6, 7);
        private static final LocalDate MONDAY = LocalDate.of(2026, 6, 8);
        private static final LocalDate FRIDAY = LocalDate.of(2026, 6, 5);

        @Test
        @DisplayName("a seasonal or custom frequency cannot infer its own due dates")
        void seasonalAndCustomRequireExplicitDates() {
            // KCC and agricultural term loans are dated to the harvest. There is no period
            // length to derive, so the dates are supplied or the calendar is refused — a
            // monthly-index approximation on a crop-cycle loan is a wrong rate, not a
            // rough one.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScheduleCalendar(
                    ScheduleCalendar.Frequency.SEASONAL,
                    ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of()))
                .withMessageContaining("requires explicit due dates")
                .withMessageContaining("crop-cycle");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScheduleCalendar(
                    ScheduleCalendar.Frequency.CUSTOM,
                    ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                    ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of()));
            // The factory takes the same route rather than defaulting to something monthly.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleCalendar.seasonal(List.of()));

            assertThat(ScheduleCalendar.seasonal(List.of(
                LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15))).customDueDates())
                .hasSize(2);
        }

        @Test
        @DisplayName("custom due dates are strictly ascending")
        void customDueDatesAreStrictlyAscending() {
            // Out of order or repeated, a supplied schedule produces a period of zero or
            // negative length, and actual-date discounting would raise (1+r) to a
            // non-positive year fraction on a schedule that looked perfectly ordinary.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleCalendar.seasonal(List.of(
                    LocalDate.of(2027, 4, 15), LocalDate.of(2026, 10, 15))))
                .withMessageContaining("strictly ascending");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ScheduleCalendar.seasonal(List.of(
                    LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15))))
                .withMessageContaining("does not follow");
        }

        @Test
        @DisplayName("periodic indexing is granted only by a uniform, unadjusted, un-overridden calendar")
        void periodicIndexingNeedsAllFourConditions() {
            // ST-10. Each of the four conditions is a separate way for a due date to move
            // or a period to stop being equal, so each is asserted separately: dropping any
            // one of them licenses ordinal discounting on a schedule whose periods are not
            // the same length, which is the one thing ST-10 forbids.
            assertThat(ScheduleCalendar.monthly().admitsPeriodicIndexing()).isTrue();

            assertThat(new ScheduleCalendar(
                ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.MODIFIED_FOLLOWING, Set.of(),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of())
                .admitsPeriodicIndexing()).isFalse();

            assertThat(new ScheduleCalendar(
                ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.NONE, Set.of(LocalDate.of(2026, 6, 10)),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of())
                .admitsPeriodicIndexing()).isFalse();

            assertThat(ScheduleCalendar.seasonal(List.of(
                LocalDate.of(2026, 10, 15), LocalDate.of(2027, 4, 15)))
                .admitsPeriodicIndexing()).isFalse();

            // A monthly frequency with dates supplied anyway: the supplied list is what
            // bills, so the frequency is no longer evidence that the periods are equal.
            assertThat(new ScheduleCalendar(
                ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH,
                List.of(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 3)))
                .admitsPeriodicIndexing()).isFalse();
        }

        @Test
        @DisplayName("periodsPerYear is a second and separate gate, and weekly passes the first but not it")
        void periodsPerYearIsASeparateGate() {
            // Worth pinning because the two questions read like one. A weekly calendar's
            // periods *are* uniform and unadjusted, so it admits periodic indexing; what it
            // does not have is a whole number of periods in a year, so it reports
            // periodsPerYear() == 0 and FlowVectorAssembler.convention declines the
            // optimisation on that basis instead. Neither method is sufficient alone, and a
            // caller that took admitsPeriodicIndexing() as the whole answer would go on to
            // annualise on a zero.
            assertThat(ScheduleCalendar.Frequency.WEEKLY.periodsPerYear()).isZero();
            assertThat(ScheduleCalendar.Frequency.FORTNIGHTLY.periodsPerYear()).isZero();
            assertThat(ScheduleCalendar.Frequency.SEASONAL.periodsPerYear()).isZero();
            assertThat(ScheduleCalendar.Frequency.CUSTOM.periodsPerYear()).isZero();
            assertThat(ScheduleCalendar.Frequency.MONTHLY.periodsPerYear()).isEqualTo(12);
            assertThat(ScheduleCalendar.Frequency.QUARTERLY.periodsPerYear()).isEqualTo(4);
            assertThat(ScheduleCalendar.Frequency.HALF_YEARLY.periodsPerYear()).isEqualTo(2);
            assertThat(ScheduleCalendar.Frequency.ANNUAL.periodsPerYear()).isEqualTo(1);

            assertThat(new ScheduleCalendar(
                ScheduleCalendar.Frequency.WEEKLY,
                ScheduleCalendar.BusinessDayConvention.NONE, Set.of(),
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of())
                .admitsPeriodicIndexing()).isTrue();

            // Only these two cannot derive their own dates. Weekly can, which is why it is
            // constructible above without a supplied schedule.
            assertThat(ScheduleCalendar.Frequency.SEASONAL.requiresExplicitDates()).isTrue();
            assertThat(ScheduleCalendar.Frequency.CUSTOM.requiresExplicitDates()).isTrue();
            assertThat(ScheduleCalendar.Frequency.WEEKLY.requiresExplicitDates()).isFalse();
            assertThat(ScheduleCalendar.Frequency.MONTHLY.requiresExplicitDates()).isFalse();
        }

        @Test
        @DisplayName("a due date moves only where the convention says it may")
        void adjustmentHappensOnlyUnderAConvention() {
            // 2026-06-06 is a Saturday, 06-08 the Monday, 06-05 the Friday.
            ScheduleCalendar none = ScheduleCalendar.monthly();
            assertThat(none.isBusinessDay(SATURDAY)).isFalse();
            assertThat(none.isBusinessDay(SUNDAY)).isFalse();
            assertThat(none.isBusinessDay(MONDAY)).isTrue();

            // Under NONE the Saturday is returned unchanged even though it is not a
            // business day. That is the contract: the calendar does not quietly roll a due
            // date the convention did not ask it to move, because a moved date is a
            // different accrual period and would void periodic indexing without saying so.
            assertThat(none.adjust(SATURDAY)).isEqualTo(SATURDAY);

            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.FOLLOWING, Set.of())
                .adjust(SATURDAY)).isEqualTo(MONDAY);
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.PRECEDING, Set.of())
                .adjust(SATURDAY)).isEqualTo(FRIDAY);
            // An untouched business day is returned as it stands under any convention.
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.FOLLOWING, Set.of())
                .adjust(MONDAY)).isEqualTo(MONDAY);

            // Holidays are skipped as well as weekends: with the Monday a holiday, FOLLOWING
            // lands on Tuesday the 9th rather than on a closed day.
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.FOLLOWING, Set.of(MONDAY))
                .adjust(SATURDAY)).isEqualTo(LocalDate.of(2026, 6, 9));
        }

        @Test
        @DisplayName("the modified conventions stay inside the month")
        void theModifiedConventionsStayInsideTheMonth() {
            // 2026-10-31 is a Saturday and the last day of October. FOLLOWING would land on
            // Monday 2026-11-02 and push the instalment into the next month, which moves it
            // across a reporting period as well as across a month end; MODIFIED_FOLLOWING
            // rolls back to Friday 2026-10-30 instead.
            LocalDate monthEndSaturday = LocalDate.of(2026, 10, 31);
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.FOLLOWING, Set.of())
                .adjust(monthEndSaturday)).isEqualTo(LocalDate.of(2026, 11, 2));
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.MODIFIED_FOLLOWING, Set.of())
                .adjust(monthEndSaturday)).isEqualTo(LocalDate.of(2026, 10, 30));

            // The mirror: 2026-11-01 is a Sunday and the first of the month. PRECEDING
            // would fall back into October; MODIFIED_PRECEDING rolls forward to Monday.
            LocalDate monthStartSunday = LocalDate.of(2026, 11, 1);
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.PRECEDING, Set.of())
                .adjust(monthStartSunday)).isEqualTo(LocalDate.of(2026, 10, 30));
            assertThat(calendar(ScheduleCalendar.BusinessDayConvention.MODIFIED_PRECEDING, Set.of())
                .adjust(monthStartSunday)).isEqualTo(LocalDate.of(2026, 11, 2));
        }

        @Test
        @DisplayName("an implausible holiday set is refused rather than searched forever")
        void anImplausibleHolidaySetIsRefused()  {
            // A holiday feed that arrives as a whole year of closures is a data defect, and
            // the roll would otherwise walk it a day at a time looking for a business day
            // that is not there. The guard turns an unbounded search into a named failure —
            // and it is the failure that says which contract's calendar is wrong, which is
            // the only thing that makes it actionable.
            Set<LocalDate> everyDay = new HashSet<>();
            for (int day = 0; day < 500; day++) {
                everyDay.add(SATURDAY.plusDays(day));
            }

            assertThatIllegalStateException()
                .isThrownBy(() -> calendar(
                    ScheduleCalendar.BusinessDayConvention.FOLLOWING, everyDay).adjust(SATURDAY))
                .withMessageContaining("no business day found within 400 days")
                .withMessageContaining("the holiday set is implausible");
        }

        private static ScheduleCalendar calendar(
            ScheduleCalendar.BusinessDayConvention convention, Set<LocalDate> holidays) {

            return new ScheduleCalendar(
                ScheduleCalendar.Frequency.MONTHLY, convention, holidays,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of());
        }
    }

    // ------------------------------------------------------------------- 2.7

    @Nested
    @DisplayName("behavioural overlay: how expected flows differ from contractual ones")
    class Behaviour {

        @Test
        @DisplayName("every overlay revision is the entity's own estimate and catches up, never resets")
        void everyOverlayRevisionCatchesUp() {
            // The other half of the ADR-0006 join. A curve change is not a market rate
            // moving, it is the entity revising its own estimate, so it falls outside
            // B5.4.5 and routes to a catch-up. Doc 09 O7 sizes the difference: on the
            // premium note a CPR revision from 10% to 20% at month 24 books -876.38, and
            // under a RESET reading it would book nothing at all.
            List<BehaviouralOverlay> overlays = List.of(
                new BehaviouralOverlay.Contractual("prepayment not permitted by the facility"),
                new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")),
                new BehaviouralOverlay.CprVector(List.of(bd("0.05"), bd("0.10"))),
                new BehaviouralOverlay.RolloverAssumption(3, bd("0.80")),
                new BehaviouralOverlay.RevolverBehaviour(
                    36, List.of(bd("0.60")), "card-book behavioural study 2026-Q1"));

            for (BehaviouralOverlay overlay : overlays) {
                assertThat(overlay.driverOnRevision())
                    .as("driver for %s", overlay.label())
                    .isEqualTo(RateDriver.BEHAVIOURAL_ESTIMATE);
                assertThat(ROUTING.mechanismFor(overlay.driverOnRevision()))
                    .as("mechanism for %s", overlay.label())
                    .isEqualTo(Mechanism.CATCH_UP);
                assertThat(overlay.driverOnRevision().isMarketMovement()).isFalse();
            }
        }

        @Test
        @DisplayName("contractual life must say why it is the expectation")
        void contractualLifeMustStateItsBasis() {
            // "We used contractual life" and "we never considered life" produce identical
            // numbers and opposite audit outcomes. The basis is the only thing that
            // distinguishes them after the fact, so a blank one is refused rather than
            // stored.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.Contractual("   "))
                .withMessageContaining("indistinguishable from never having considered it");
            assertThatNullPointerException()
                .isThrownBy(() -> new BehaviouralOverlay.Contractual(null));

            BehaviouralOverlay stated = new BehaviouralOverlay.Contractual("immaterial prepayment");
            assertThat(stated.altersFlows()).isFalse();
            assertThat(stated.label()).isEqualTo("CONTRACTUAL");
        }

        @Test
        @DisplayName("a prepayment rate is a fraction below one, and a zero rate alters nothing")
        void prepaymentRatesAreFractionsBelowOne() {
            // Half-open at the top: a CPR of 1 prepays the entire pool inside the first
            // year, which leaves no flows to discount, and the guard is what stops that
            // reaching the solver as an empty vector.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.ConstantPrepaymentRate(BigDecimal.ONE))
                .withMessageContaining("fraction in [0,1)");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.ConstantPrepaymentRate(bd("-0.01")));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.CprVector(List.of()))
                .withMessageContaining("at least one point");
            // Every point is checked, not just the first. A curve is loaded a column at a
            // time and the bad cell is rarely at the top.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.CprVector(
                    List.of(bd("0.05"), bd("0.10"), BigDecimal.ONE)))
                .withMessageContaining("each CPR is a fraction in [0,1)");

            // A zero CPR is an explicit assumption that nothing prepays, and it must not
            // reshape the flows: altersFlows() is what BehaviouralAdjuster reads to decide
            // whether to run a roll-forward at all, and a nil-effect roll-forward is how
            // the movement schedule fills with restatements that move nothing.
            assertThat(new BehaviouralOverlay.ConstantPrepaymentRate(BigDecimal.ZERO).altersFlows())
                .isFalse();
            assertThat(new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")).altersFlows())
                .isTrue();
            assertThat(new BehaviouralOverlay.CprVector(List.of(BigDecimal.ZERO, BigDecimal.ZERO))
                .altersFlows()).isFalse();
            assertThat(new BehaviouralOverlay.CprVector(List.of(BigDecimal.ZERO, bd("0.02")))
                .altersFlows()).isTrue();
            // Doc 09 O6 runs 0%, 8%, 15% and 25% on one mortgage; the label carries the
            // rate because those four are four different published EIRs.
            assertThat(new BehaviouralOverlay.ConstantPrepaymentRate(bd("0.08")).label())
                .isEqualTo("CPR(0.08)");
        }

        @Test
        @DisplayName("a CPR vector is one-based and holds its last point flat")
        void aCprVectorIsOneBasedAndHoldsItsLastPointFlat() {
            BehaviouralOverlay.CprVector vector = new BehaviouralOverlay.CprVector(
                List.of(bd("0.02"), bd("0.06"), bd("0.10")));

            assertThat(vector.forPeriod(1)).isEqualByComparingTo("0.02");
            assertThat(vector.forPeriod(2)).isEqualByComparingTo("0.06");
            assertThat(vector.forPeriod(3)).isEqualByComparingTo("0.10");
            // Held flat rather than running out. A vintage curve is supplied for the
            // seasoning ramp and a 240-month mortgage outlives it; returning zero past the
            // end would silently switch the instrument to contractual life at period 4.
            assertThat(vector.forPeriod(4)).isEqualByComparingTo("0.10");
            assertThat(vector.forPeriod(240)).isEqualByComparingTo("0.10");
            // One-based, and it says so rather than reading index -1.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> vector.forPeriod(0))
                .withMessageContaining("periodIndex is 1-based");
            assertThat(vector.label()).isEqualTo("CPR_VECTOR(3 points)");
        }

        @Test
        @DisplayName("a rollover alters flows on the count alone, and deliberately not on the probability")
        void rolloverAltersFlowsOnTheCountNotTheProbability() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RolloverAssumption(-1, bd("0.80")))
                .withMessageContaining("expectedRollovers must not be negative");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RolloverAssumption(3, bd("1.01")))
                .withMessageContaining("fraction in [0,1]");
            // Closed at the top here, unlike a CPR: a facility certain to roll is an
            // ordinary WCDL, and certainty is a legitimate assumption where a CPR of 1 is
            // not a legitimate curve.
            assertThat(new BehaviouralOverlay.RolloverAssumption(3, BigDecimal.ONE).altersFlows())
                .isTrue();

            // The probability is recorded and NOT weighted into the flows — that is
            // BehaviouralAdjuster's documented rule, and it is why altersFlows() reads only
            // the count. Blending a rolled outcome with an unrolled one at a scalar
            // probability would be a second probability-weighting mechanism alongside
            // ExercisePolicy.PROBABILITY_WEIGHTED, with no way to say afterwards which one
            // produced a published rate. So a three-rollover assumption at probability zero
            // still extends the flows, and the probability qualifies the disclosure basis
            // instead. This assertion exists to stop that being "fixed".
            assertThat(new BehaviouralOverlay.RolloverAssumption(3, BigDecimal.ZERO).altersFlows())
                .isTrue();
            // No rollovers is the case that alters nothing, whatever the probability says.
            assertThat(new BehaviouralOverlay.RolloverAssumption(0, BigDecimal.ONE).altersFlows())
                .isFalse();
            assertThat(new BehaviouralOverlay.RolloverAssumption(3, bd("0.80")).label())
                .isEqualTo("ROLLOVER(3 @ 0.80)");
        }

        @Test
        @DisplayName("a modelled revolver life must cite the analysis that supports it")
        void aModelledRevolverLifeMustCiteItsAnalysis() {
            // ACPIR 46(2)(iii). The entire carrying amount of a card book measured on a
            // behavioural life is an estimate, and the UK experience is that this is a
            // recurring source of restatement. An unevidenced life is the defect the field
            // exists to prevent, so it is refused at construction and not at review.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RevolverBehaviour(
                    36, List.of(bd("0.60")), "   "))
                .withMessageContaining("ACPIR 46(2)(iii)")
                .withMessageContaining("an unevidenced life is the defect this field exists");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new BehaviouralOverlay.RevolverBehaviour(
                    0, List.of(bd("0.60")), "card-book behavioural study 2026-Q1"))
                .withMessageContaining("behaviouralLifeMonths must be >= 1");

            BehaviouralOverlay.RevolverBehaviour revolver = new BehaviouralOverlay.RevolverBehaviour(
                36, List.of(bd("0.60"), bd("0.55")), "card-book behavioural study 2026-Q1");
            // Always alters flows: there is no contractual instalment ladder on a revolver
            // to coincide with, so a modelled life is by construction a reshaping.
            assertThat(revolver.altersFlows()).isTrue();
            assertThat(revolver.behaviouralLifeMonths()).isEqualTo(36);
            assertThat(revolver.label()).isEqualTo("REVOLVER_BEHAVIOUR(36m)");
        }
    }

    // ------------------------------------------------------------------- 3

    @Nested
    @DisplayName("expected life: the ACPIR 51 life against the ACPIR 46(1) horizon")
    class TwoLives {

        /**
         * Doc 09 O5: a 5-year bond extendable by 3. Stated 7.755768% p.a., extended
         * 8.125769% — 37.0 bp apart, from one contractual feature. The ECL horizon is
         * the maximum contractual period including the extension, 8 years; the EIR
         * expected life is what is expected, possibly 5.
         */
        private static final Rate STATED = Rate.annualEffective(bd("0.07755768"));

        private static final Rate EXTENDED = Rate.annualEffective(bd("0.08125769"));

        /**
         * Face 1,000,000 held at par, so first-period income is face x the effective
         * annual rate: 1,000,000 x 7.755768% = 77,557.68 and x 8.125769% = 81,257.69.
         * These are inputs to the record, not figures under test — the record does no
         * arithmetic on them — but a controller compares income rather than basis
         * points, so they are stated rather than invented.
         */
        private static final Money STATED_INCOME = Money.inr("77557.68");

        private static final Money EXTENDED_INCOME = Money.inr("81257.69");

        private static final ExpectedLifeDetermination.LifeAlternative TO_STATED =
            new ExpectedLifeDetermination.LifeAlternative(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, STATED, STATED_INCOME);

        private static final ExpectedLifeDetermination.LifeAlternative TO_EXTENDED =
            new ExpectedLifeDetermination.LifeAlternative(
                ExercisePolicy.MOST_LIKELY_OUTCOME, 8, EXTENDED, EXTENDED_INCOME);

        private static InvariantResult resultFor(
            ExpectedLifeDetermination determination, InvariantId id) {

            return determination.invariants().stream()
                .filter(result -> result.id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " was not asserted"));
        }

        @Test
        @DisplayName("the published life must come from one of the computed policies")
        void thePublishedFigureMustBeOneOfTheComputedOnes() {
            // The published rate has to be traceable to a computed alternative. Without
            // this the record could carry a figure that no policy in the list produces,
            // and the disclosure of "which policy produced the published figure" would name
            // a policy that was never run.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExpectedLifeDetermination(
                    ExercisePolicy.EARLIEST_CALL, 5, List.of(TO_STATED), List.of()))
                .withMessageContaining("is absent from the alternatives");
            // An empty list fails the same way rather than passing vacuously.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ExpectedLifeDetermination.of(
                    ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(), false, 8))
                .withMessageContaining("is absent from the alternatives");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExpectedLifeDetermination(
                    ExercisePolicy.CONTRACTUAL_MATURITY, 0, List.of(TO_STATED), List.of()))
                .withMessageContaining("expected life spans at least one period");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExpectedLifeDetermination.LifeAlternative(
                    ExercisePolicy.CONTRACTUAL_MATURITY, 0, STATED, STATED_INCOME))
                .withMessageContaining("lifePeriods must be >= 1");
        }

        @Test
        @DisplayName("chosen() returns the alternative for the recorded policy, not the first one")
        void chosenReturnsTheRecordedPolicysAlternative() {
            // Ordered with the unchosen alternative first, so an implementation that
            // returned alternatives.get(0) would pass a same-order test and fail this one.
            ExpectedLifeDetermination determination = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_EXTENDED, TO_STATED), true, 8);

            assertThat(determination.chosen()).isEqualTo(TO_STATED);
            assertThat(determination.chosen().lifePeriods()).isEqualTo(5);
            assertThat(determination.chosen().eir()).isEqualTo(STATED);
            assertThat(determination.chosen().firstPeriodIncome()).isEqualTo(STATED_INCOME);
        }

        @Test
        @DisplayName("an optioned instrument with one computed policy breaches ST-7")
        void oneAlternativeOnAnOptionedInstrumentBreachesST7() {
            // ST-7. The engine's obligation on an optioned instrument is not to pick the
            // right life — the governing sources disagree, and that disagreement is a Board
            // decision — but to compute both and disclose the difference. A single
            // alternative means the divergence was never quantified, which is the finding
            // rather than the answer.
            ExpectedLifeDetermination single = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED), true, 8);

            InvariantResult st7 = resultFor(single, InvariantId.ST_7);
            assertThat(st7.satisfied()).isFalse();
            assertThat(st7.detail()).contains("divergence was never quantified");
            // One policy computed where two are required: the deviation is the shortfall.
            assertThat(st7.deviation()).isEqualByComparingTo("1");
            assertThat(single.allSatisfied()).isFalse();

            ExpectedLifeDetermination both = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED, TO_EXTENDED), true, 8);
            assertThat(resultFor(both, InvariantId.ST_7).satisfied()).isTrue();
            assertThat(both.allSatisfied()).isTrue();

            // And ST-7 must not fire on an instrument with nothing to exercise. A plain
            // amortising loan has one coherent policy — contractual maturity — and flagging
            // it would bury the real findings in noise from the whole vanilla book.
            ExpectedLifeDetermination vanilla = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED), false, 8);
            assertThat(vanilla.invariants())
                .extracting(InvariantResult::id)
                .containsExactly(InvariantId.ST_8);
            assertThat(vanilla.allSatisfied()).isTrue();
        }

        @Test
        @DisplayName("expected life and the ECL horizon are separate inputs and neither overwrites the other")
        void theTwoLivesAreSeparateInputs() {
            // Doc 09 § 3.3: one extension option, two numbers. The ECL horizon is 8 years
            // because ACPIR 46(1) sets it at the maximum contractual period including
            // extension options; the EIR expected life under ACPIR 51 is 5 because that is
            // what is expected. The pair is legitimate and must survive as a pair.
            ExpectedLifeDetermination fiveOfEight = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED, TO_EXTENDED), true, 8);

            assertThat(fiveOfEight.chosenLifePeriods()).isEqualTo(5);
            assertThat(fiveOfEight.allSatisfied()).isTrue();
            InvariantResult st8 = resultFor(fiveOfEight, InvariantId.ST_8);
            // Both figures are named in the detail. The check compares them; it does not
            // reconcile them, and the record keeps the life it was given.
            assertThat(st8.detail()).contains("expected life 5").contains("ECL horizon 8");
            assertThat(st8.deviation()).isEqualByComparingTo("0");

            // The same life against a horizon that happens to equal it. If the horizon were
            // being written into the life — the collapse this design exists to prevent —
            // one of these two would report the other's number.
            ExpectedLifeDetermination fiveOfFive = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED, TO_EXTENDED), true, 5);
            assertThat(fiveOfFive.chosenLifePeriods()).isEqualTo(5);
            assertThat(resultFor(fiveOfFive, InvariantId.ST_8).detail()).contains("ECL horizon 5");
            assertThat(fiveOfEight.chosenLifePeriods()).isEqualTo(fiveOfFive.chosenLifePeriods());
        }

        @Test
        @DisplayName("a life beyond the ECL horizon is reported, not clamped away")
        void aLifeBeyondTheHorizonIsReportedNotClamped() {
            // A 9-period life against O5's 8-period horizon: an extension counted twice, or
            // a behavioural life lifted from a different product. Nothing can be expected
            // beyond the maximum contractual period, so ST-8 fails — and the determination
            // is still built, carrying the 9. Clamping to 8 or throwing would both destroy
            // the evidence: once the shorter or longer figure has been silently substituted
            // there is no way to tell a correct historical number from a wrong one, which is
            // the whole reason the two lives are separate fields.
            ExpectedLifeDetermination beyond = ExpectedLifeDetermination.of(
                ExercisePolicy.MOST_LIKELY_OUTCOME, 9,
                List.of(TO_STATED, new ExpectedLifeDetermination.LifeAlternative(
                    ExercisePolicy.MOST_LIKELY_OUTCOME, 9, EXTENDED, EXTENDED_INCOME)),
                true, 8);

            assertThat(beyond.chosenLifePeriods()).isEqualTo(9);
            assertThat(beyond.allSatisfied()).isFalse();
            InvariantResult st8 = resultFor(beyond, InvariantId.ST_8);
            assertThat(st8.satisfied()).isFalse();
            assertThat(st8.detail())
                .contains("exceeds the ECL horizon 8")
                .contains("ACPIR 46(1)")
                .contains("MAXIMUM contractual period");
            // 9 - 8 = 1 period of overrun, signed positive because the life is the longer.
            assertThat(st8.deviation()).isEqualByComparingTo("1");
            // ST-7 is satisfied on the same determination: two policies were computed. The
            // two invariants are independent and a breach of one must not mask the other.
            assertThat(resultFor(beyond, InvariantId.ST_7).satisfied()).isTrue();
        }

        @Test
        @DisplayName("the widest divergence is the spread across every alternative computed")
        void widestDivergenceIsTheFullSpread() {
            // Doc 09 O5 states the divergence as 37.0 bp. In full: 8.125769% is 812.5769 bp
            // and 7.755768% is 775.5768 bp, so the spread is 37.0001 bp. This is the number
            // that goes in front of the committee, which is why it is the widest spread and
            // not the distance from the chosen policy to its neighbour.
            ExpectedLifeDetermination pair = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED, TO_EXTENDED), true, 8);
            assertThat(pair.widestDivergenceBps()).isEqualByComparingTo("37.0001");

            // A third policy landing between the two must not change the answer. An
            // implementation differencing consecutive or adjacent entries would report a
            // smaller figure here and would understate the divergence by however many
            // policies were computed.
            ExpectedLifeDetermination three = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5,
                List.of(TO_STATED,
                    new ExpectedLifeDetermination.LifeAlternative(
                        ExercisePolicy.PROBABILITY_WEIGHTED, 6,
                        Rate.annualEffective(bd("0.079")), Money.inr("79000.00")),
                    TO_EXTENDED),
                true, 8);
            assertThat(three.widestDivergenceBps()).isEqualByComparingTo("37.0001");

            // One alternative has nothing to diverge from, and reports zero rather than
            // guessing. On an optioned instrument ST-7 has already said why that is a
            // finding; on an unoptioned one it is simply the truth.
            assertThat(ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, List.of(TO_STATED), false, 8)
                .widestDivergenceBps()).isEqualByComparingTo("0");
        }

        @Test
        @Disabled("DEFECT: the published life is not cross-checked against the chosen"
            + " alternative's life. See the comment below.")
        @DisplayName("DEFECT: the published life may contradict the chosen alternative's own life")
        void thePublishedLifeMayContradictTheChosenAlternative() {
            // chosenPolicy is cross-checked against the alternatives — "the published
            // figure must be one of the computed ones" — but chosenLifePeriods is not. So a
            // determination can publish a 5-period life while the alternative it names as
            // its source says 8. Both halves of the published figure come from the same
            // policy by definition, so this pair cannot both be right.
            //
            // Consequence: ST-8 is then asserted against a life no computed policy supports
            // (5 against a horizon of 8 passes, where the chosen alternative's real 8 would
            // sit exactly on the boundary), and a reader who takes the life from
            // chosen().lifePeriods() and one who takes it from chosenLifePeriods() get
            // different answers from the same record. The ordinary way in is an ingestion
            // path that fills the scalar from one source and the alternatives from another.
            //
            // The fix belongs in the canonical constructor, next to the policy check it
            // mirrors: reject a chosenLifePeriods that differs from chosen().lifePeriods().
            // This test asserts that refusal, so it fails today by not throwing.
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ExpectedLifeDetermination(
                    ExercisePolicy.MOST_LIKELY_OUTCOME, 5, List.of(TO_STATED, TO_EXTENDED),
                    List.of()))
                .withMessageContaining("8");
        }
    }

    // ------------------------------------------------------------------- immutability

    @Nested
    @DisplayName("the dimensions are values, so a caller's list cannot change underneath them")
    class DefensiveCopies {

        @Test
        @DisplayName("mutating the caller's list does not reach an already-built dimension")
        void mutatingTheCallersListDoesNotReachTheDimension() {
            // These records are handed to a solver and to a replay. An aliased list would
            // let the input change between the solve and the re-solve, and DT-1 —
            // deterministic replay — would fail on a contract where the stored inputs look
            // identical. Every list-bearing dimension copies on construction; this asserts
            // it on one of each kind rather than trusting the pattern held everywhere.
            List<Tranche> draws = new ArrayList<>(
                List.of(Tranche.of(VALUE_DATE, 0, Money.inr("400000"))));
            DisbursementProfile.Tranched tranched =
                new DisbursementProfile.Tranched(draws, List.of(), bd("0.05"));

            List<LocalDate> resets = new ArrayList<>(List.of(LocalDate.of(2026, 10, 1)));
            RateProfile.Floating floating =
                new RateProfile.Floating("MCLR-1Y", bd("250"), resets, ONE_PERCENT_MONTHLY);

            Set<LocalDate> holidays = new HashSet<>(Set.of(LocalDate.of(2026, 6, 10)));
            ScheduleCalendar calendar = new ScheduleCalendar(
                ScheduleCalendar.Frequency.MONTHLY,
                ScheduleCalendar.BusinessDayConvention.FOLLOWING, holidays,
                ScheduleCalendar.EndOfMonthRule.SAME_DAY_OF_MONTH, List.of());

            List<BigDecimal> curve = new ArrayList<>(List.of(bd("0.05")));
            BehaviouralOverlay.CprVector vector = new BehaviouralOverlay.CprVector(curve);

            List<ExpectedLifeDetermination.LifeAlternative> alternatives = new ArrayList<>(
                List.of(new ExpectedLifeDetermination.LifeAlternative(
                    ExercisePolicy.CONTRACTUAL_MATURITY, 5,
                    Rate.annualEffective(bd("0.07755768")), Money.inr("77557.68"))));
            ExpectedLifeDetermination determination = ExpectedLifeDetermination.of(
                ExercisePolicy.CONTRACTUAL_MATURITY, 5, alternatives, false, 8);

            draws.add(Tranche.of(LocalDate.of(2026, 10, 1), 6, Money.inr("600000")));
            resets.add(LocalDate.of(2027, 4, 1));
            holidays.add(LocalDate.of(2026, 6, 11));
            curve.add(bd("0.10"));
            alternatives.add(new ExpectedLifeDetermination.LifeAlternative(
                ExercisePolicy.EARLIEST_CALL, 3, Rate.annualEffective(bd("0.07")),
                Money.inr("70000.00")));

            assertThat(tranched.projected()).hasSize(1);
            assertThat(tranched.notional()).isEqualTo(Money.inr("400000"));
            assertThat(floating.resetDates()).hasSize(1);
            assertThat(floating.nextResetAfter(LocalDate.of(2026, 10, 1))).isEmpty();
            assertThat(calendar.holidays()).hasSize(1);
            assertThat(calendar.isBusinessDay(LocalDate.of(2026, 6, 11))).isTrue();
            assertThat(vector.annualCprByPeriod()).hasSize(1);
            assertThat(vector.forPeriod(2)).isEqualByComparingTo("0.05");
            assertThat(determination.alternatives()).hasSize(1);
            // ST-7 was assessed on one alternative and stays assessed on one, whatever the
            // caller does to its list afterwards.
            assertThat(determination.widestDivergenceBps()).isEqualByComparingTo("0");
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * A minimal coherent blueprint varying only the three dimensions under test.
     *
     * <p>Reference case 1's shape — 1,000,000 over 24 monthly periods at 1% — with a
     * single advance, a fixed rate, no options and contractual behaviour, so that the
     * only thing a rejection can be about is the principal, servicing and moratorium
     * triple handed in.
     */
    private static ScheduleBlueprint blueprint(
        PrincipalProfile principal, InterestServicing servicing, Moratorium moratorium) {

        return new ScheduleBlueprint(
            Money.inr("1000000"),
            Money.INR,
            VALUE_DATE,
            VALUE_DATE.plusYears(3),
            new DisbursementProfile.Single(VALUE_DATE, Money.inr("1000000")),
            principal,
            servicing,
            moratorium,
            new RateProfile.Fixed(ONE_PERCENT_MONTHLY),
            OptionSchedule.none(),
            new BehaviouralOverlay.Contractual("prepayment not permitted by the facility"),
            ScheduleCalendar.monthly(),
            DayCountConvention.THIRTY_360_BOND,
            ResiduePolicy.FINAL_PERIOD_PLUG,
            36);
    }
}
