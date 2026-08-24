package com.crisil.eir.calc.routing;

import static com.crisil.eir.calc.routing.RoutingFixtures.CONTRACTUAL;
import static com.crisil.eir.calc.routing.RoutingFixtures.CONTRACTUAL_BALANCE_AT_MONTH_12;
import static com.crisil.eir.calc.routing.RoutingFixtures.EMI;
import static com.crisil.eir.calc.routing.RoutingFixtures.GCA_AT_MONTH_12;
import static com.crisil.eir.calc.routing.RoutingFixtures.MONTHLY;
import static com.crisil.eir.calc.routing.RoutingFixtures.ORIGINAL_EIR;
import static com.crisil.eir.calc.routing.RoutingFixtures.bd;
import static com.crisil.eir.calc.routing.RoutingFixtures.level;
import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.calc.amort.CatchUpCalculator;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.projection.Annuity;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Mechanism;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The two part-prepayment shapes (specification 6.5), and the rule that the variant comes
 * from the <strong>event</strong>.
 *
 * <p>Both variants reduce the balance by the cash received. They diverge on the remaining
 * schedule and therefore on whether anything hits P&amp;L now: a tenor reduction
 * re-projects and continues, while an EMI reduction has changed the flow <em>pattern</em>,
 * which is a revision of estimated receipts and so a B5.4.6 catch-up at the original EIR.
 *
 * <p>Which one applies is a contractual term or a borrower election at the counter, and it
 * is never inferred from the resulting schedule (FR-509). Inference fails in both
 * directions and quietly, and the fixtures below are built to show it: the tenor-reduced
 * schedule here ends in a stub instalment smaller than the EMI, which is exactly what an
 * EMI reduction looks like from the outside. Guessing wrong is the difference between a
 * catch-up posted and a catch-up missed, on an event that occurs thousands of times a month
 * in a retail mortgage book.
 */
class PartPrepaymentVariantTest {

    /** A 100,000 part-prepayment at month 12 of reference case 1. */
    private static final Money PREPAYMENT = Money.inr("100000");

    /** Contractual balance after the cash: 529,815.61 - 100,000. */
    private static final Money CONTRACTUAL_AFTER = CONTRACTUAL_BALANCE_AT_MONTH_12.minus(PREPAYMENT);

    /** EIR-leg carrying amount after the cash: 528,407.32 - 100,000. */
    private static final Money GCA_AFTER = GCA_AT_MONTH_12.minus(PREPAYMENT);

    /** The stub that closes the tenor-reduced schedule after nine full EMIs. */
    private static final Money FINAL_STUB = Money.inr("29364.65");

    private static final Rate ORIGINAL_RATE = Rate.monthly(ORIGINAL_EIR);

    @Test
    @DisplayName("both variants reduce the balance by the same cash: the divergence is the schedule")
    void bothVariantsTakeTheCash() {
        assertThat(CONTRACTUAL_AFTER.amount()).isEqualByComparingTo(bd("429815.61"));
        assertThat(GCA_AFTER.amount()).isEqualByComparingTo(bd("428407.32"));

        // The unamortised fee is untouched by the cash — both legs fell by 100,000, so their
        // difference is what it was. What changes it is the remaining life the fee has left to
        // amortise over, and that is what the two variants disagree about.
        assertThat(CONTRACTUAL_AFTER.minus(GCA_AFTER).amount()).isEqualByComparingTo(bd("1408.29"));
    }

    @Test
    @DisplayName("TENOR_REDUCED re-projects and books no catch-up, whatever a restatement would say")
    void tenorReducedProducesNoCatchUp() {
        // Nine full EMIs and a 29,364.65 stub retire 429,815.61 at 1% a month: the tenor has
        // gone from twelve periods to ten, and the instalment has not moved.
        FlowVector tenorReduced = level(EMI, 9, FINAL_STUB);
        assertThat(tenorReduced.size()).isEqualTo(10);

        assertThat(PartPrepaymentVariant.TENOR_REDUCED.mechanism()).isEqualTo(Mechanism.NONE);
        assertThat(PartPrepaymentVariant.TENOR_REDUCED.requiresCatchUp()).isFalse();
        assertThat(PartPrepaymentVariant.TENOR_REDUCED.requiresReprojection()).isTrue();

        // Mechanism.NONE means no rate consequence and no restatement — not "nothing
        // happens". The carrying amount still fell by the cash and the schedule is still
        // re-projected.
        CatchUpResult notBooked =
            CatchUpCalculator.restate(ORIGINAL_RATE, GCA_AFTER, tenorReduced, MONTHLY);
        assertThat(notBooked.presentedCatchUp().amount()).isEqualByComparingTo(bd("468.64"));

        // And that figure is what the variant declines to recognise. The original EIR still
        // discounts the remaining flows closely enough that no restatement is warranted, so
        // the difference stays in the yield over the remaining ten periods rather than being
        // taken to P&L today. The engine does not book it because the event said
        // TENOR_REDUCED — not because the number was small.
        assertThat(notBooked.catchUp().abs().amount()).isLessThan(PREPAYMENT.amount());
    }

    @Test
    @DisplayName("EMI_REDUCED restates at the original EIR and produces a catch-up")
    void emiReducedProducesACatchUp() {
        // Twelve periods held, instalment recomputed on the reduced balance: 38,188.60.
        Money reducedInstalment = Annuity.billedInstalment(CONTRACTUAL_AFTER, CONTRACTUAL.periodic(), 12);
        assertThat(reducedInstalment.amount()).isEqualByComparingTo(bd("38188.60"));
        FlowVector emiReduced = level(reducedInstalment, 12);

        assertThat(PartPrepaymentVariant.EMI_REDUCED.mechanism()).isEqualTo(Mechanism.CATCH_UP);
        assertThat(PartPrepaymentVariant.EMI_REDUCED.requiresCatchUp()).isTrue();
        assertThat(PartPrepaymentVariant.EMI_REDUCED.requiresReprojection()).isFalse();

        CatchUpResult restatement =
            CatchUpCalculator.restate(ORIGINAL_RATE, GCA_AFTER, emiReduced, MONTHLY);

        // The flow pattern changed, so the receipts were re-estimated, so the balance is
        // restated at the retained rate and the difference is recognised now.
        assertThat(restatement.presentedCatchUp().amount()).isEqualByComparingTo(bd("265.90"));
        assertThat(restatement.restatedGca().atPresentationScale().amount())
            .isEqualByComparingTo(bd("428673.22"));
        assertThat(restatement.isIncome()).isTrue();
        // CU-1 holds: this is a restatement, so the rate did not move.
        assertThat(restatement.isClean()).isTrue();
        assertThat(restatement.eirAfter().periodic()).isEqualTo(restatement.eirBefore().periodic());
    }

    @Test
    @DisplayName("the variant decides, not the size of the number a restatement would produce")
    void theEventDecidesAndNotTheArithmetic() {
        FlowVector tenorReduced = level(EMI, 9, FINAL_STUB);
        FlowVector emiReduced = level(
            Annuity.billedInstalment(CONTRACTUAL_AFTER, CONTRACTUAL.periodic(), 12), 12);

        Money wouldBeOnTenorReduction =
            CatchUpCalculator.restate(ORIGINAL_RATE, GCA_AFTER, tenorReduced, MONTHLY).catchUp();
        Money bookedOnEmiReduction =
            CatchUpCalculator.restate(ORIGINAL_RATE, GCA_AFTER, emiReduced, MONTHLY).catchUp();

        // The restatement the tenor reduction would have produced is the LARGER of the two —
        // 468.64 against 265.90 — and it is the one that is not booked. So "restate where the
        // difference is material" is not the rule and could not be: the rule is the variant on
        // the event, and any implementation that decided on the magnitude would get this pair
        // exactly backwards.
        assertThat(wouldBeOnTenorReduction.abs().amount())
            .isGreaterThan(bookedOnEmiReduction.abs().amount());
        assertThat(PartPrepaymentVariant.TENOR_REDUCED.requiresCatchUp()).isFalse();
        assertThat(PartPrepaymentVariant.EMI_REDUCED.requiresCatchUp()).isTrue();
    }

    @Test
    @DisplayName("a tenor-reduced schedule LOOKS like an EMI reduction, and inference would misread it")
    void inferenceFromTheScheduleFailsInBothDirections() {
        // The tenor-reduced schedule's last instalment is 29,364.65 against an EMI of
        // 47,073.47. An implementation that inspected the resulting schedule and concluded
        // "the instalment got smaller, therefore EMI_REDUCED" would post a 468.64 catch-up
        // that the event never authorised.
        FlowVector tenorReduced = level(EMI, 9, FINAL_STUB);
        assertThat(tenorReduced.flows().getLast().amount().amount()).isLessThan(EMI.amount());
        assertThat(tenorReduced.flows().getFirst().amount().amount())
            .isEqualByComparingTo(EMI.amount());

        // And the other direction: an EMI reduction on a loan whose instalment was already
        // stepping down is indistinguishable from a schedule that always looked like that.
        // Both misreadings are silent, which is why there is deliberately no factory here that
        // takes two schedules and decides — the election has to come from the source system.
        assertThat(Arrays.stream(PartPrepaymentVariant.class.getDeclaredMethods())
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .map(Method::getName))
            .containsOnly("values", "valueOf");
    }

    @ParameterizedTest
    @EnumSource(PartPrepaymentVariant.class)
    @DisplayName("the two variants are exhaustive and their consequences do not overlap")
    void theVariantsPartitionTheOutcomes(PartPrepaymentVariant variant) {
        // Exactly one of the two consequences applies to each variant. A third state — both,
        // or neither — would mean an event that reduced a balance and left the accounting
        // undecided.
        assertThat(variant.requiresCatchUp()).isNotEqualTo(variant.requiresReprojection());
        assertThat(variant.mechanism())
            .isEqualTo(variant.requiresCatchUp() ? Mechanism.CATCH_UP : Mechanism.NONE);
        assertThat(PartPrepaymentVariant.values()).hasSize(2);
    }

    @Test
    @DisplayName("neither variant re-solves the rate: a part-prepayment is not a B5.4.5 event")
    void neitherVariantResolvesTheRate() {
        for (PartPrepaymentVariant variant : PartPrepaymentVariant.values()) {
            assertThat(variant.mechanism()).isNotEqualTo(Mechanism.RESET);
            assertThat(variant.mechanism()).isNotEqualTo(Mechanism.DERECOGNITION);
            assertThat(variant.mechanism()).isNotEqualTo(Mechanism.MODIFICATION_TEST);
        }
    }
}
