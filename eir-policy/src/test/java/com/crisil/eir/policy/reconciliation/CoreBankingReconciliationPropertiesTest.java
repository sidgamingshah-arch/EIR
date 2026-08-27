package com.crisil.eir.policy.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.policy.approval.ApprovalRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

/**
 * RC-1 over generated populations, recomputed from the control's definition.
 *
 * <p>The unit tests pin the control on a fixture drawn from reference case 1, which is the right way
 * to pin an accounting reading and the wrong way to establish that an <em>aggregate</em> is right. A
 * total is exactly the kind of figure that is correct on a three-contract fixture and wrong on a
 * book: an accumulator that rounds in the wrong place, an {@code abs} applied after the sum instead
 * of before, a filter that drops a category, a shortcut that holds for the ordering the fixture
 * happens to use.
 *
 * <p><b>No expected value here comes from running the code under test.</b> Each property recomputes
 * the answer from RC-1's definition, arranged deliberately differently from the implementation.
 * {@link #rcOneIsTheReconstructionOfTheEngineFigure} is the clearest case: the implementation
 * computes {@code (engine − cbs) − explained} and asks whether the residual is nil, while the
 * property asks the question a reconciliation actually poses — does the book of record plus the
 * stated causes <em>reproduce</em> the engine's figure to the paise — as
 * {@code engine − (cbs + explained)}, and recomputes which explanations count from the four
 * conditions rather than by calling {@code isEffective()}.
 */
class CoreBankingReconciliationPropertiesTest {

    private static final int PERIOD = 202705;
    private static final LocalDate CHECKED_ON = LocalDate.of(2027, 6, 4);
    private static final String FEED = "CBS-EOD-202705-E01";

    /** Reference case 1 period 2 contractual interest, at working precision. */
    private static final BigDecimal ENGINE = new BigDecimal("9629.2653");

    private static String contractId(int index) {
        // Zero-padded so that lexical order — the order the aggregator publishes lines in — and
        // generation order agree, which keeps a shrunk counterexample readable.
        return String.format("C%03d", index);
    }

    private static ContractualLegInterest engineLine(int index) {
        return new ContractualLegInterest(contractId(index), PERIOD, Money.of(ENGINE, Money.INR));
    }

    private static CbsBilledInterest cbsLine(int index, BigDecimal amount) {
        return new CbsBilledInterest(contractId(index), PERIOD, Money.of(amount, Money.INR), FEED);
    }

    private static BigDecimal presented(BigDecimal value) {
        return Precision.round(value, 2);
    }

    // ------------------------------------------------------------------ RC-1 as a reconstruction

    /**
     * RC-1 passes exactly where the CBS figure plus the effective explanations reproduces the
     * engine's contractual interest to the paise, for every contract; and its deviation is the total
     * absolute shortfall.
     *
     * <p>Differences are generated at four decimal places spanning ±500 so the population contains
     * sub-paise differences that must tie (the LMS_AUTHORITATIVE case, where the CBS bills a
     * presented figure the engine carries unrounded), differences straddling a rounding boundary,
     * and differences large enough that no rounding argument applies. Claims are generated
     * independently of the differences, so most explanations do <em>not</em> match the difference
     * they claim to explain — which is the state the control has to catch rather than the state it
     * hopes for.
     */
    @Property(tries = 500)
    void rcOneIsTheReconstructionOfTheEngineFigure(
        @ForAll @Size(min = 1, max = 8)
        List<@BigRange(min = "-500", max = "500") @Scale(4) BigDecimal> differences,
        @ForAll @Size(min = 1, max = 8)
        List<@BigRange(min = "-500", max = "500") @Scale(2) BigDecimal> claims,
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 3) Integer> shapes) {
        int contracts = Math.min(differences.size(), Math.min(claims.size(), shapes.size()));

        List<ContractualLegInterest> engineLines = new ArrayList<>();
        List<CbsBilledInterest> cbsLines = new ArrayList<>();
        List<DifferenceExplanation> explanations = new ArrayList<>();
        for (int index = 0; index < contracts; index++) {
            BigDecimal cbs = ENGINE.subtract(differences.get(index), Precision.WORKING);
            engineLines.add(engineLine(index));
            cbsLines.add(cbsLine(index, cbs));
            explanations.add(explanation(index, claims.get(index), shapes.get(index)));
        }

        // The definition, recomputed. "Explained" is re-derived from the four conditions on an
        // explanation rather than from isEffective(), and the residual is arranged as a
        // reconstruction of the engine's figure from the book of record plus the stated causes.
        BigDecimal expectedAbsolute = BigDecimal.ZERO;
        BigDecimal expectedNet = BigDecimal.ZERO;
        for (int index = 0; index < contracts; index++) {
            BigDecimal explained = shapes.get(index) == 0 ? claims.get(index) : BigDecimal.ZERO;
            BigDecimal cbs = ENGINE.subtract(differences.get(index), Precision.WORKING);
            // NOT reduced to presentation scale. This recomputation used to wrap the residual in
            // presented(), which encoded the per-line rounding the implementation then did — so
            // the property agreed with the code by sharing its defect. 03 section 5.7 forbids
            // resolving a residue by tolerance, and the rule that accounts for the billing-scale
            // difference lives on the input, in ContractualLegInterest.fromContractualLeg.
            BigDecimal residual =
                ENGINE.subtract(cbs.add(explained, Precision.WORKING), Precision.WORKING);
            expectedAbsolute = expectedAbsolute.add(residual.abs());
            expectedNet = expectedNet.add(residual);
        }

        CoreBankingReconciliation recon = CoreBankingReconciliation.over(
            PERIOD, Money.INR, engineLines, cbsLines, explanations);
        InvariantResult result = recon.tiesToCoreBanking();

        assertThat(result.id()).isEqualTo(InvariantId.RC_1);
        assertThat(result.satisfied())
            .as("%d contracts; the definition leaves %s unexplained. detail: %s",
                contracts, expectedAbsolute, result.detail())
            .isEqualTo(expectedAbsolute.signum() == 0);
        assertThat(recon.totalAbsoluteUnexplainedDifference().amount())
            .as("total absolute residual over %d contracts", contracts)
            .isEqualByComparingTo(expectedAbsolute);
        assertThat(recon.netUnexplainedDifference().amount())
            .isEqualByComparingTo(expectedNet);
        if (!result.satisfied()) {
            assertThat(result.deviation())
                .as("the deviation is the total absolute, not the net")
                .isEqualByComparingTo(expectedAbsolute);
        }
    }

    /**
     * The four shapes an explanation comes in. Only shape 0 is entitled to reduce anything, and the
     * other three are the ways a close produces something that reads as an explanation and is not
     * one: unapproved, self-approved, and a reason code with no statement behind it.
     */
    private static DifferenceExplanation explanation(int index, BigDecimal claim, int shape) {
        String contractId = contractId(index);
        Money amount = Money.of(claim, Money.INR);
        String narrative = "CBS bills on the 5th; the accrual runs to month-end.";
        return switch (shape) {
            case 0 -> DifferenceExplanation.approved(contractId, PERIOD,
                DifferenceReason.BILLING_DAY_TIMING, amount, narrative, "recon.preparer",
                ApprovalRecord.by("recon.checker", CHECKED_ON));
            case 1 -> DifferenceExplanation.prepared(contractId, PERIOD,
                DifferenceReason.BILLING_DAY_TIMING, amount, narrative, "recon.preparer");
            case 2 -> new DifferenceExplanation(contractId, PERIOD,
                DifferenceReason.BILLING_DAY_TIMING, amount, narrative, "recon.preparer",
                ApprovalRecord.by("RECON.PREPARER", CHECKED_ON));
            default -> DifferenceExplanation.approved(contractId, PERIOD,
                DifferenceReason.ROUNDING_CONVENTION, amount, "", "recon.preparer",
                ApprovalRecord.by("recon.checker", CHECKED_ON));
        };
    }

    // ------------------------------------------------------------------ the deviation is absolute

    /**
     * Two breaks in opposite directions never net to a pass.
     *
     * <p>The property behind the choice of a total <em>absolute</em> deviation. An engine
     * over-accruing on one product and under-accruing by the same amount on another is two live
     * breaks and a zero net, and a signed deviation reports it as reconciled. Generated over a
     * range that includes the smallest representable break, one paise, because that is where a
     * deviation computed the wrong way is most likely to survive review.
     */
    @Property(tries = 300)
    void oppositeBreaksNeverNetToAPass(
        @ForAll @BigRange(min = "0.01", max = "50000.00") @Scale(2) BigDecimal breakAmount) {
        CoreBankingReconciliation recon = CoreBankingReconciliation.over(
            PERIOD, Money.INR,
            List.of(engineLine(1), engineLine(2)),
            List.of(
                cbsLine(1, ENGINE.subtract(breakAmount, Precision.WORKING)),
                cbsLine(2, ENGINE.add(breakAmount, Precision.WORKING))),
            List.of());

        InvariantResult result = recon.tiesToCoreBanking();
        assertThat(recon.netUnexplainedDifference().amount())
            .as("the two breaks cancel exactly, by construction")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.satisfied())
            .as("%s in each direction is two breaks, not none", breakAmount)
            .isFalse();
        assertThat(result.deviation())
            .isEqualByComparingTo(breakAmount.add(breakAmount));
    }

    // ------------------------------------------- an explanation is a quantity, not a permission

    /**
     * An explanation whose amount is not the difference never ties the contract, whatever it says
     * and whoever signed it.
     *
     * <p>The property that stops an explanation being a note: without it, any difference can be
     * waved through by attaching a reason code and a sentence. Both figures are generated at paise
     * scale and constrained to differ, so the mismatch is always at least one paise and never a
     * rounding artefact — and the narrative is generated too, because the length of the prose is
     * exactly the thing that must not matter.
     */
    @Property(tries = 300)
    void anExplanationThatIsNotTheDifferenceNeverTies(
        @ForAll @BigRange(min = "-5000.00", max = "5000.00") @Scale(2) BigDecimal difference,
        @ForAll @BigRange(min = "-5000.00", max = "5000.00") @Scale(2) BigDecimal claim,
        @ForAll @StringLength(min = 1, max = 60) String narrative) {
        if (difference.compareTo(claim) == 0) {
            return;
        }
        // Prefixed with a fixed token so that a generated string of pure whitespace still leaves a
        // non-blank narrative: the property is about the amount, and an accidentally blank
        // narrative would make the explanation ineffective for an unrelated reason.
        DifferenceExplanation explanation = DifferenceExplanation.approved(
            contractId(1), PERIOD, DifferenceReason.BILLING_DAY_TIMING,
            Money.of(claim, Money.INR),
            "CBS timing: " + narrative, "recon.preparer",
            ApprovalRecord.by("recon.checker", CHECKED_ON));

        CoreBankingReconciliation recon = CoreBankingReconciliation.over(
            PERIOD, Money.INR,
            List.of(engineLine(1)),
            List.of(cbsLine(1, ENGINE.subtract(difference, Precision.WORKING))),
            List.of(explanation));

        InvariantResult result = recon.tiesToCoreBanking();
        assertThat(explanation.isEffective())
            .as("the explanation is properly made; only its amount is wrong")
            .isTrue();
        assertThat(result.satisfied())
            .as("difference %s against a claim of %s", difference, claim)
            .isFalse();
        assertThat(result.deviation())
            .as("the residual is the difference less the claim")
            .isEqualByComparingTo(difference.subtract(claim).abs());
        // Misstatement is now over-claiming or claiming in the wrong direction — not merely
        // claiming less than the difference. A short claim is PARTIAL ATTRIBUTION: several
        // explanations per contract are normal, so a claim covering part of a difference with the
        // rest under investigation is the ordinary mid-close shape, and the heading it used to
        // trigger says the preparer's method is wrong on every other contract in the book.
        boolean overshoots = claim.abs().compareTo(difference.abs()) > 0;
        boolean wrongDirection = claim.signum() != 0 && difference.signum() != 0
            && claim.signum() != difference.signum();
        assertThat(recon.misstatedExplanations().size())
            .as("difference %s, claim %s: overshoots=%s wrongDirection=%s",
                difference, claim, overshoots, wrongDirection)
            .isEqualTo(overshoots || wrongDirection ? 1 : 0);
        assertThat(recon.lines().getFirst().isPartlyAttributed())
            .as("the complement: a claim that is neither an overshoot nor the wrong way round is"
                + " work in progress, and every generated pair is one or the other")
            .isEqualTo(!(overshoots || wrongDirection));
    }

    // ------------------------------------------------------------------ presence is not a zero

    /**
     * Adding a contract that only one source presents never improves the reconciliation: the
     * deviation rises by the whole of that contract's amount, in either direction.
     *
     * <p>RC-1's definition read as a monotonicity. The implementation this guards against is the
     * one everybody writes — for each engine line, look up the CBS figure, and on a miss skip the
     * contract — under which this property fails in the {@code ENGINE_ONLY} direction and the
     * deviation does not move at all. It is generated in both directions because the two are
     * different code paths and only one of them is the obvious one.
     */
    @Property(tries = 300)
    void aOneSidedContractNeverImprovesTheReconciliation(
        @ForAll @BigRange(min = "0.01", max = "50000.00") @Scale(2) BigDecimal amount,
        @ForAll @IntRange(min = 0, max = 1) int direction,
        @ForAll @IntRange(min = 0, max = 4) int tiedContracts) {
        List<ContractualLegInterest> engineLines = new ArrayList<>();
        List<CbsBilledInterest> cbsLines = new ArrayList<>();
        for (int index = 0; index < tiedContracts; index++) {
            engineLines.add(engineLine(index));
            cbsLines.add(cbsLine(index, ENGINE));
        }
        CoreBankingReconciliation before = CoreBankingReconciliation.over(
            PERIOD, Money.INR, engineLines, cbsLines, List.of());
        assertThat(before.tiesToCoreBanking().satisfied())
            .as("the base population ties by construction: identical figures on both sides")
            .isTrue();

        int oneSided = 900;
        List<ContractualLegInterest> withEngine = new ArrayList<>(engineLines);
        List<CbsBilledInterest> withCbs = new ArrayList<>(cbsLines);
        if (direction == 0) {
            withEngine.add(new ContractualLegInterest(
                contractId(oneSided), PERIOD, Money.of(amount, Money.INR)));
        } else {
            withCbs.add(cbsLine(oneSided, amount));
        }
        CoreBankingReconciliation after = CoreBankingReconciliation.over(
            PERIOD, Money.INR, withEngine, withCbs, List.of());

        assertThat(after.contractsReconciled())
            .as("the union grew by one, whichever source presented it")
            .isEqualTo(tiedContracts + 1);
        assertThat(after.tiesToCoreBanking().satisfied())
            .as("%s presented by one source only is a break of %s, not a line that ties",
                amount, amount)
            .isFalse();
        assertThat(after.tiesToCoreBanking().deviation())
            .isEqualByComparingTo(amount);
        assertThat(after.presenceBreaks()).hasSize(1);
        assertThat(after.presenceBreaks().getFirst().presence())
            .isEqualTo(direction == 0 ? SourcePresence.ENGINE_ONLY : SourcePresence.CBS_ONLY);
    }

    // ------------------------------------------------------------------ four eyes, one rule

    /**
     * A preparer signing off their own explanation is never effective, under any casing or padding
     * of the same identity.
     *
     * <p>The rule is {@code FourEyes}, and this asserts the property that made a single home for it
     * necessary: three of the four places the comparison was written stripped and case-folded and
     * the fourth did not, so the same two identities were the same person in three places and two
     * people in the fourth. An explanation row arriving from a close spreadsheet is precisely the
     * second channel where a case or whitespace variant appears.
     */
    @Property(tries = 200)
    void selfApprovalUnderAnyCasingIsIneffective(
        @ForAll @IntRange(min = 0, max = 3) int casing,
        @ForAll @IntRange(min = 0, max = 3) int leftPad,
        @ForAll @IntRange(min = 0, max = 3) int rightPad,
        @ForAll @BigRange(min = "0.01", max = "5000.00") @Scale(2) BigDecimal difference) {
        String identity = "Recon.Preparer";
        String variant = switch (casing) {
            case 0 -> identity;
            case 1 -> identity.toUpperCase(Locale.ROOT);
            case 2 -> identity.toLowerCase(Locale.ROOT);
            default -> "rECON.pREPARER";
        };
        variant = " ".repeat(leftPad) + variant + " ".repeat(rightPad);

        DifferenceExplanation selfApproved = new DifferenceExplanation(
            contractId(1), PERIOD, DifferenceReason.BILLING_DAY_TIMING,
            Money.of(difference, Money.INR),
            "CBS bills on the 5th; the accrual runs to month-end.",
            identity, ApprovalRecord.by(variant, CHECKED_ON));

        assertThat(selfApproved.isSelfApproved())
            .as("checker %s against preparer %s", variant, identity)
            .isTrue();
        assertThat(selfApproved.isEffective()).isFalse();

        CoreBankingReconciliation recon = CoreBankingReconciliation.over(
            PERIOD, Money.INR,
            List.of(engineLine(1)),
            List.of(cbsLine(1, ENGINE.subtract(difference, Precision.WORKING))),
            List.of(selfApproved));

        InvariantResult result = recon.tiesToCoreBanking();
        assertThat(result.satisfied())
            .as("a self-approved explanation is one person's assertion; the difference stands")
            .isFalse();
        assertThat(result.deviation())
            .as("the whole difference, not the part the explanation claimed")
            .isEqualByComparingTo(difference);
    }
}
