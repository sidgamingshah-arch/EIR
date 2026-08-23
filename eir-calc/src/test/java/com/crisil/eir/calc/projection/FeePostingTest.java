package com.crisil.eir.calc.projection;

import static com.crisil.eir.calc.projection.CaseFixtures.DISBURSEMENT;
import static com.crisil.eir.calc.projection.CaseFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two rejections at the fee boundary, and why each is a filter rather than a
 * judgement.
 *
 * <p>Both are enforced at construction, which is the only place they can be. A rule
 * the projector applied later would leave a window in which a rejected posting
 * existed as a value and could be summed, logged or persisted by something that had
 * not thought about it.
 */
class FeePostingTest {

    @Test
    @DisplayName("a posting classified EXCLUDED_BY_DIRECTION cannot be constructed: penal charges")
    void penalChargesAreRejectedAtTheBoundary() {
        // Under RBI's 2023 framework penal amounts are *charges*, not penal interest:
        // not capitalised, bearing no further interest, and therefore incapable of
        // entering the amortisation schedule or the gross carrying amount at all
        // (calculation specification 3.4). Legacy core banking systems routinely book
        // penal amounts into the interest ledger, which is why this is a hard filter
        // with a positive assertion each period (invariant PC-1) rather than a rule the
        // rule set could be configured out of.
        assertThatThrownBy(() -> FeePosting.received("PENAL_CHARGE", Money.inr("2500"), DISBURSEMENT,
            FeeClassification.EXCLUDED_BY_DIRECTION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Penal charges")
            .hasMessageContaining("charges, not penal interest")
            .hasMessageContaining("PC_1");

        // The same on the paid side, and through the canonical constructor: there is no
        // route in.
        assertThatThrownBy(() -> FeePosting.paid("PENAL_CHARGE", Money.inr("2500"), DISBURSEMENT,
            FeeClassification.EXCLUDED_BY_DIRECTION, "OTHER"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot enter any EIR cash flow stream or the gross carrying amount");
        assertThatThrownBy(() -> new FeePosting("PENAL_CHARGE", Money.inr("2500"), DISBURSEMENT,
            FeeClassification.EXCLUDED_BY_DIRECTION, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Penal charges");
        assertThat(FeeClassification.EXCLUDED_BY_DIRECTION.entersCarryingAmount()).isFalse();
    }

    @Test
    @DisplayName("an integral cost with a blank cost function is rejected: the ACPIR 53 selling line")
    void integralCostNeedsItsCostFunction() {
        // ACPIR 53 draws the line at *selling*, not processing: an incentive paid to an
        // employee acting as a selling agent is a capitalisable transaction cost, while
        // the salary of the credit-appraisal team is internal administrative cost and is
        // excluded. Source HR and cost-centre data is structured along neither, so the
        // attribute is mandatory and its absence fails the posting instead of defaulting
        // to either treatment (FR-203).
        assertThatThrownBy(() -> FeePosting.paid("DSA_COMMISSION", Money.inr("10000"), DISBURSEMENT,
            FeeClassification.INTEGRAL, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("carries no cost_function")
            .hasMessageContaining("ACPIR 53")
            .hasMessageContaining("fails rather than defaulting to either treatment");
        assertThatThrownBy(() -> new FeePosting("DSA_COMMISSION", Money.inr("-10000"), DISBURSEMENT,
            FeeClassification.INTEGRAL, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("carries no cost_function");
    }

    @Test
    @DisplayName("the cost-function vocabulary is closed and ordered")
    void costFunctionVocabularyIsClosed() {
        // Ordered so that a rejection message reads the same on every run: an
        // exception-queue entry is a record, and a record that varies between identical
        // runs is not one.
        assertThat(FeePosting.COST_FUNCTIONS).containsExactly("SELLING", "PROCESSING", "ADMIN", "OTHER");
        assertThatThrownBy(() -> FeePosting.paid("DSA_COMMISSION", Money.inr("10000"), DISBURSEMENT,
            FeeClassification.INTEGRAL, "SALES"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be one of [SELLING, PROCESSING, ADMIN, OTHER]");
        // Case-insensitive and trimmed on the way in, then normalised, because ingestion
        // feeds are not tidy and a rejected posting for a lower-case value would be noise.
        assertThat(FeePosting.paid("DSA_COMMISSION", Money.inr("10000"), DISBURSEMENT,
            FeeClassification.INTEGRAL, " selling ").costFunction()).isEqualTo("SELLING");
    }

    @Test
    @DisplayName("the record does not re-decide which cost function capitalises")
    void classificationIsTheRuleSetsJobAndIsNotSecondGuessed() {
        // An INTEGRAL posting carrying ADMIN is a rule-set defect and belongs in the rule
        // set's own control. Re-testing it here would put the same judgement in two
        // places and let them disagree, so the posting is accepted and the trace carries
        // the attribute that makes the defect visible.
        FeePosting adminButIntegral = FeePosting.paid("APPRAISAL_TEAM", Money.inr("7000"),
            DISBURSEMENT, FeeClassification.INTEGRAL, "ADMIN");

        assertThat(adminButIntegral.costFunction()).isEqualTo("ADMIN");
        assertThat(adminButIntegral.entersInitialCarryingAmount()).isTrue();
    }

    @Test
    @DisplayName("sign carries direction: a fee received is positive and a cost paid is negative")
    void signCarriesDirection() {
        FeePosting received = FeePosting.received("PROCESSING_FEE", Money.inr("15000"),
            DISBURSEMENT, FeeClassification.INTEGRAL);
        // paid() takes the amount as quoted and signs it, so an ingestion feed that
        // supplies unsigned magnitudes cannot accidentally book a cost as income.
        FeePosting paidFromPositive = FeePosting.paid("DSA_COMMISSION", Money.inr("10000"),
            DISBURSEMENT, FeeClassification.INTEGRAL, "SELLING");
        FeePosting paidFromNegative = FeePosting.paid("DSA_COMMISSION", Money.inr("-10000"),
            DISBURSEMENT, FeeClassification.INTEGRAL, "SELLING");

        assertThat(received.amount().isPositive()).isTrue();
        assertThat(paidFromPositive.amount().amount()).isEqualByComparingTo(bd("-10000"));
        assertThat(paidFromNegative.amount().amount()).isEqualByComparingTo(bd("-10000"));
        assertThat(received.amount().plus(paidFromPositive.amount()).amount())
            .as("15,000 received against 10,000 paid nets to the 5,000 of integral fee that lifts "
                + "the Case 1 EIR 56.6 basis points above the contractual effective rate")
            .isEqualByComparingTo(bd("5000"));
    }

    @Test
    @DisplayName("a commitment fee cannot be constructed without its drawdown assessment")
    void commitmentFeeNeedsADrawdownProbability() {
        // ACPIR 52 omits IFRS 9 B5.4.2(b)'s probable-drawdown condition, so read
        // literally every commitment fee defers — including on facilities that were never
        // going to draw. The numeric threshold is per product in policy; the posting only
        // carries the assessment, and refuses to exist without it (FR-204).
        assertThatThrownBy(() -> FeePosting.commitment("COMMITMENT_FEE", Money.inr("3000"),
            DISBURSEMENT, FeeClassification.OVER_COMMITMENT_PERIOD, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("drawdownProbability");
        assertThatThrownBy(() -> FeePosting.commitment("COMMITMENT_FEE", Money.inr("3000"),
            DISBURSEMENT, FeeClassification.OVER_COMMITMENT_PERIOD, bd("1.5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be in [0,1]");
        assertThat(FeePosting.commitment("COMMITMENT_FEE", Money.inr("3000"), DISBURSEMENT,
            FeeClassification.INTEGRAL, bd("0.85")).entersInitialCarryingAmount()).isTrue();
        assertThat(FeePosting.commitment("COMMITMENT_FEE", Money.inr("3000"), DISBURSEMENT,
            FeeClassification.OVER_COMMITMENT_PERIOD, bd("0.10")).entersInitialCarryingAmount())
            .isFalse();
    }

    @Test
    @DisplayName("a blank fee code is rejected: an unmapped posting cannot be traced")
    void feeCodeIsMandatory() {
        assertThatThrownBy(() -> FeePosting.received("  ", Money.inr("100"), DISBURSEMENT,
            FeeClassification.INTEGRAL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("feeCode must not be blank");
    }

    @Test
    @DisplayName("contingent charges reach the projector as AS_INCURRED and stay out of the carrying amount")
    void contingentChargesDoNotEnterTheCarryingAmount() {
        // Prepayment penalty, late fee, bounce charge: excluded from the projection at
        // inception regardless of being contractually specified, and recognised in the
        // period the event occurs (3.3, FR-206).
        assertThat(FeePosting.received("FORECLOSURE_CHARGE", Money.inr("5000"), DISBURSEMENT,
            FeeClassification.AS_INCURRED).entersInitialCarryingAmount()).isFalse();
        assertThat(FeePosting.received("BOUNCE_CHARGE", Money.inr("500"), DISBURSEMENT,
            FeeClassification.AS_INCURRED).entersInitialCarryingAmount()).isFalse();
    }
}
