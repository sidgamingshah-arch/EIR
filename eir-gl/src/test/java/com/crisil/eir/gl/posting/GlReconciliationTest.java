package com.crisil.eir.gl.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariant SL-1: sub-ledger contract balances tie to the GL control accounts with zero unexplained
 * difference (FR-803, control C-13, 03 § 9).
 *
 * <h2>The fixture, and where its numbers come from</h2>
 *
 * <p>Three contracts, three control accounts, hand-built so the sums are checkable by eye and
 * cross-checked in python3. Nothing here is read off a run of the code under test.
 *
 * <pre>
 *   1301-LOANS-GCA          C1 1,000,000.00 + C2 2,500,000.00 + C3 750,000.00 = 4,250,000.00
 *   2301-UNAMORTISED-FEE    C1    12,500.00 + C2    31,250.00 + C3   9,375.00 =    53,125.00
 *   1409-INTEREST-SUSPENSE  C3     4,820.55                                   =     4,820.55
 * </pre>
 *
 * <p>The fee balances are 1.25% of each contract's GCA, which is the shape 04 § 2.8's derived
 * {@code unamortised_fee} takes on a fee-bearing book, and the suspense balance sits on C3 alone
 * because suspense is a Stage 3 phenomenon (FR-604).
 *
 * <h2>Why every case below is reachable</h2>
 *
 * <p>The GL balances are supplied, not computed. That is the whole reason these tests can exist: if
 * the GL side were derived from the sub-ledger side there would be no fixture in which they differ,
 * and SL-1 would be a control with no failing input — the shape this codebase has found four times
 * and treats as worse than an absent control.
 */
class GlReconciliationTest {

    private static final int PERIOD = 202804;
    private static final String BOOK = "MAIN";
    private static final String GCA = "1301-LOANS-GCA";
    private static final String SUSPENSE = "1409-INTEREST-SUSPENSE";
    private static final String FEE = "2301-UNAMORTISED-FEE";
    private static final String TB = "TB-202804-FINAL";

    /** The sub-ledger side: nine balances over three contracts and three control accounts. */
    private static List<SubLedgerBalance> subLedger() {
        List<SubLedgerBalance> balances = new ArrayList<>();
        balances.add(SubLedgerBalance.of("C1", GCA, Money.inr("1000000.00")));
        balances.add(SubLedgerBalance.of("C2", GCA, Money.inr("2500000.00")));
        balances.add(SubLedgerBalance.of("C3", GCA, Money.inr("750000.00")));
        balances.add(SubLedgerBalance.of("C1", FEE, Money.inr("12500.00")));
        balances.add(SubLedgerBalance.of("C2", FEE, Money.inr("31250.00")));
        balances.add(SubLedgerBalance.of("C3", FEE, Money.inr("9375.00")));
        balances.add(SubLedgerBalance.of("C3", SUSPENSE, Money.inr("4820.55")));
        return balances;
    }

    private static GlControlAccountBalance gl(String accountCode, String amount) {
        return GlControlAccountBalance.of(accountCode, Money.inr(amount), TB);
    }

    /** The GL agreeing with the sub-ledger on every account. */
    private static List<GlControlAccountBalance> glTying() {
        return List.of(
            gl(GCA, "4250000.00"),
            gl(FEE, "53125.00"),
            gl(SUSPENSE, "4820.55"));
    }

    @Nested
    @DisplayName("a cancelling set of explanations is caught wherever it is filed")
    class CancellingExplanations {

        @Test
        @DisplayName("a cancelling pair on an account that does NOT tie was invisible everywhere")
        void cancellingPairOnANonTyingAccount() {
            // The hole review found, reproduced. The FEE account's sub-ledger sums to 53,125.00
            // (12,500 + 31,250 + 9,375, by hand) against a GL of 53,000.00 — a genuine difference
            // of 125.00. Filed against it: a real 125.00 timing explanation, plus a +500,000.00
            // and a −500,000.00 that cancel.
            //
            // The signed arithmetic ties: 125 + 500,000 − 500,000 = 125 explained against 125 of
            // difference, so SL-1 passes and SHOULD pass — nothing about the account's balance is
            // unaccounted for. But half a million rupees of spurious register entries sat there
            // with the only trace being explanationCount() == 3, because the register check gated
            // on the account already agreeing before explanation.
            List<GlControlAccountBalance> gl = List.of(
                gl(GCA, "4250000.00"), gl(FEE, "53000.00"), gl(SUSPENSE, "4820.55"));
            List<ExplainedDifference> explanations = List.of(
                ExplainedDifference.timing(FEE, Money.inr("125.00"), "late fee batch"),
                ExplainedDifference.timing(FEE, Money.inr("500000.00"), "spurious"),
                ExplainedDifference.timing(FEE, Money.inr("-500000.00"), "spurious reversal"));

            GlReconciliation recon =
                GlReconciliation.of(PERIOD, BOOK, subLedger(), gl, explanations);

            assertThat(recon.tiesToGl().satisfied())
                .as("the money is accounted for, so SL-1 passes — and must, or its deviation stops"
                    + " meaning 'money the two books disagree by'")
                .isTrue();
            assertThat(recon.unmatchedExplanations())
                .as("500,125.00 claimed in gross against 125.00 of difference: the register is"
                    + " where this shows, and it showed nothing before the predicate was widened"
                    + " from 'the account already agrees' to 'the claims exceed the difference'")
                .as("all three explanations on the FEE account surface, because the account is"
                    + " where the register is unmatched and the reader needs the whole set")
                .hasSize(3)
                .allSatisfy(explanation ->
                    assertThat(explanation.accountCode()).isEqualTo(FEE));
        }

        @Test
        @DisplayName("legitimate shapes are not flagged: exact, and partial")
        void legitimateExplanationsAreNotFlagged() {
            // The complement, without which the test above would pass against an implementation
            // that flagged every explained account. One explanation equal to the difference, and
            // one covering part of it, are both ordinary.
            List<GlControlAccountBalance> gl = List.of(
                gl(GCA, "4250000.00"), gl(FEE, "53000.00"), gl(SUSPENSE, "4820.55"));

            GlReconciliation exact = GlReconciliation.of(PERIOD, BOOK, subLedger(), gl,
                List.of(ExplainedDifference.timing(FEE, Money.inr("125.00"), "late fee batch")));
            assertThat(exact.unmatchedExplanations()).isEmpty();
            assertThat(exact.tiesToGl().satisfied()).isTrue();

            GlReconciliation partial = GlReconciliation.of(PERIOD, BOOK, subLedger(), gl,
                List.of(ExplainedDifference.timing(FEE, Money.inr("100.00"), "part of the batch")));
            assertThat(partial.unmatchedExplanations())
                .as("100.00 claimed against 125.00 of difference is partial, not unmatched")
                .isEmpty();
            assertThat(partial.tiesToGl().satisfied())
                .as("and the remaining 25.00 is still an SL-1 breach")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("SL-1 passes where the two independently sourced sides agree")
    class Ties {

        @Test
        @DisplayName("4,250,000.00 / 53,125.00 / 4,820.55 tie, and SL-1 is one result")
        void allAccountsTie() {
            GlReconciliation reconciliation =
                GlReconciliation.of(PERIOD, BOOK, subLedger(), glTying());

            // Hand-derived sums, cross-checked in python3:
            //   1,000,000.00 + 2,500,000.00 + 750,000.00 = 4,250,000.00
            //      12,500.00 +     31,250.00 +   9,375.00 =    53,125.00
            assertThat(reconciliation.lineFor(GCA).orElseThrow().subLedgerTotal())
                .isEqualTo(Money.inr("4250000.00"));
            assertThat(reconciliation.lineFor(FEE).orElseThrow().subLedgerTotal())
                .isEqualTo(Money.inr("53125.00"));
            assertThat(reconciliation.contractCount()).isEqualTo(3);
            assertThat(reconciliation.accountCodes())
                .as("account-code order, so the report reads the same on every run")
                .containsExactly(GCA, SUSPENSE, FEE);

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.id()).isEqualTo(InvariantId.SL_1);
            assertThat(result.satisfied()).as("detail: %s", result.detail()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.detail())
                .contains("3 control accounts over 3 contracts tie")
                .contains("no difference to explain");
            assertThat(reconciliation.breaks()).isEmpty();
        }

        @Test
        @DisplayName("an empty reconciliation passes, because nothing fails to tie")
        void emptyPasses() {
            // Correct rather than convenient: a book with no balances and no control accounts has
            // nothing out. It is not a loophole either — the population is the caller's assertion
            // about what it reconciled, and FR-901's close gate is what requires the population to
            // be the period's.
            GlReconciliation empty = GlReconciliation.of(PERIOD, BOOK, List.of(), List.of());
            assertThat(empty.tiesToGl().satisfied()).isTrue();
            assertThat(empty.currency()).isEqualTo(Money.INR);
            assertThat(empty.unexplainedTotal()).isEqualTo(Money.zero(Money.INR));
        }
    }

    @Nested
    @DisplayName("SL-1 fails on an unexplained difference, and the deviation is the money")
    class Breaks {

        @Test
        @DisplayName("*** THE FAILING INPUT: GL reports 53,000.00 against a sub-ledger 53,125.00")
        void unexplainedDifferenceFails() {
            // This is the answer to "what input makes SL-1 fail?". 53,125.00 − 53,000.00 = 125.00,
            // derived by hand and cross-checked in python3. Both figures are inputs, so nothing in
            // the type system prevents them differing.
            List<GlControlAccountBalance> reported = List.of(
                gl(GCA, "4250000.00"),
                gl(FEE, "53000.00"),
                gl(SUSPENSE, "4820.55"));
            GlReconciliation reconciliation =
                GlReconciliation.of(PERIOD, BOOK, subLedger(), reported);

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.id()).isEqualTo(InvariantId.SL_1);
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("125.00"));
            assertThat(result.detail())
                .contains("1 of 3 control accounts")
                .contains(FEE)
                .contains("nothing explained");
            assertThat(reconciliation.breaks()).extracting(AccountReconciliation::accountCode)
                .containsExactly(FEE);
        }

        @Test
        @DisplayName("two accounts out in OPPOSITE directions do not net to a pass")
        void oppositeBreaksDoNotNet() {
            // GL fee 53,250.00 → difference −125.00; GL GCA 4,249,875.00 → difference +125.00.
            // Signed total 0.00; absolute total 250.00. This is the likely shape of a break, not an
            // exotic one: it is exactly what one posting mapped to the wrong control account looks
            // like, and a signed deviation would report the period as tied.
            List<GlControlAccountBalance> reported = List.of(
                gl(GCA, "4249875.00"),
                gl(FEE, "53250.00"),
                gl(SUSPENSE, "4820.55"));
            GlReconciliation reconciliation =
                GlReconciliation.of(PERIOD, BOOK, subLedger(), reported);

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("absolute, so +125.00 and −125.00 sum to 250.00 rather than to nil")
                .isEqualByComparingTo(new BigDecimal("250.00"));
            assertThat(result.detail()).contains("signed total 0.00, absolute 250.00");
            assertThat(reconciliation.unexplainedTotal()).isEqualTo(Money.inr("250.00"));
        }

        @Test
        @DisplayName("an account only one side knows about breaks by its whole balance")
        void unionNotIntersection() {
            // The GL carries 9,999.99 on a sundry control account the engine never posts to. An
            // intersection of the two account sets would drop this account and report a clean tie,
            // which is the worst possible outcome: a missing chart-of-accounts mapping reading as
            // green. 0.00 − 9,999.99 = −9,999.99.
            List<GlControlAccountBalance> reported = new ArrayList<>(glTying());
            reported.add(gl("1499-SUNDRY", "9999.99"));
            GlReconciliation reconciliation =
                GlReconciliation.of(PERIOD, BOOK, subLedger(), reported);

            assertThat(reconciliation.accountCodes()).hasSize(4);
            AccountReconciliation sundry = reconciliation.lineFor("1499-SUNDRY").orElseThrow();
            assertThat(sundry.contractCount()).isZero();
            assertThat(sundry.difference()).isEqualTo(Money.inr("-9999.99"));

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("9999.99"));
        }

        @Test
        @DisplayName("a sub-ledger account with no GL balance is distinguished from a nil one")
        void missingGlBalanceIsNamed() {
            // Same arithmetic as a GL balance of nil, different finding: one is a control account
            // that stands at nothing, the other is a control account nobody extracted. The
            // reconciliation says which.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                List.of(gl(GCA, "4250000.00"), gl(FEE, "53125.00")));

            AccountReconciliation suspense = reconciliation.lineFor(SUSPENSE).orElseThrow();
            assertThat(suspense.glBalanceSupplied()).isFalse();
            assertThat(suspense.difference()).isEqualTo(Money.inr("4820.55"));

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("4820.55"));
            assertThat(result.detail()).contains("no balance supplied");
        }
    }

    @Nested
    @DisplayName("an explained difference does not breach — FR-803 says UNEXPLAINED")
    class Explained {

        @Test
        @DisplayName("a timing difference and a manual GL journal both tie the period")
        void twoCausesExplainTwoAccounts() {
            // The GL has not yet taken C3's 4,820.55 suspense posting (timing, +4,820.55), and
            // somebody put a 1,000.00 debit through the GL by hand, so the GL's GCA reads
            // 4,251,000.00 against a sub-ledger 4,250,000.00 (manual journal, −1,000.00).
            // Both differences were derived by hand: 4,820.55 − 0.00 = +4,820.55 and
            // 4,250,000.00 − 4,251,000.00 = −1,000.00.
            List<GlControlAccountBalance> reported = List.of(
                gl(GCA, "4251000.00"),
                gl(FEE, "53125.00"),
                gl(SUSPENSE, "0.00"));
            List<ExplainedDifference> explanations = List.of(
                ExplainedDifference.timing(SUSPENSE, Money.inr("4820.55"),
                    "C3 suspense charge posted 30 Apr, GL cut-off was 29 Apr"),
                ExplainedDifference.manualGlJournal(GCA, Money.inr("-1000.00"),
                    "GL journal 88213 posted by Finance outside the engine"));

            GlReconciliation reconciliation =
                GlReconciliation.of(PERIOD, BOOK, subLedger(), reported, explanations);
            InvariantResult result = reconciliation.tiesToGl();

            assertThat(result.satisfied()).as("detail: %s", result.detail()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            // Signed total of the explanations: +4,820.55 − 1,000.00 = +3,820.55, of which only the
            // timing difference self-reverses.
            assertThat(reconciliation.explainedTotal()).isEqualTo(Money.inr("3820.55"));
            assertThat(reconciliation.selfReversingTotal()).isEqualTo(Money.inr("4820.55"));
            assertThat(reconciliation.explanationCount()).isEqualTo(2);
            assertThat(result.detail())
                .contains("2 explained differences totalling INR 3820.55")
                .contains("INR 4820.55 self-reversing");
            assertThat(reconciliation.unmatchedExplanations())
                .as("both explanations sit on accounts that genuinely differed")
                .isEmpty();
        }

        @Test
        @DisplayName("two explanations on one account are summed")
        void explanationsAccumulate() {
            // GL suspense reads 0.00 against 4,820.55: explained as 3,000.00 of timing plus
            // 1,820.55 in transit. 4,820.55 − 3,000.00 − 1,820.55 = 0.00, by hand.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                List.of(gl(GCA, "4250000.00"), gl(FEE, "53125.00"), gl(SUSPENSE, "0.00")),
                List.of(
                    ExplainedDifference.timing(SUSPENSE, Money.inr("3000.00"), "late batch"),
                    new ExplainedDifference(SUSPENSE, Money.inr("1820.55"),
                        DifferenceCause.IN_TRANSIT_SETTLEMENT, "recovery in transit at 30 Apr")));

            assertThat(reconciliation.tiesToGl().satisfied()).isTrue();
            assertThat(reconciliation.lineFor(SUSPENSE).orElseThrow().explained())
                .isEqualTo(Money.inr("4820.55"));
        }

        @Test
        @DisplayName("a partial explanation leaves the remainder, and the remainder breaches")
        void partialExplanation() {
            // 4,820.55 difference, 4,000.00 explained → 820.55 unexplained. Derived by hand:
            // 4,820.55 − 4,000.00 = 820.55.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                List.of(gl(GCA, "4250000.00"), gl(FEE, "53125.00"), gl(SUSPENSE, "0.00")),
                List.of(ExplainedDifference.timing(
                    SUSPENSE, Money.inr("4000.00"), "part of the late batch")));

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("820.55"));
            assertThat(result.detail()).contains("1 explanations totalling INR 4000.00");
        }
    }

    @Nested
    @DisplayName("an explanation that does not match its difference is itself a finding")
    class MismatchedExplanations {

        @Test
        @DisplayName("over-explaining a 125.00 difference by 500.00 breaches by 375.00")
        void overExplanationBreaches() {
            // The design decision this test exists for. `unexplained = difference − explained`, a
            // subtraction, NOT max(0, |difference| − |explained|). Under the capped form a 500.00
            // explanation would silence a real 125.00 break and over-explanation would be
            // unrepresentable — so the finding could not surface at all. Under subtraction:
            // 125.00 − 500.00 = −375.00, and SL-1 reports 375.00.
            //
            // That is the right answer whichever figure is wrong: either the GL balance or the
            // explanation has to be corrected before the account ties, and the accountant needs to
            // know the pair disagrees. The sign says which way.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                List.of(gl(GCA, "4250000.00"), gl(FEE, "53000.00"), gl(SUSPENSE, "4820.55")),
                List.of(ExplainedDifference.timing(FEE, Money.inr("500.00"),
                    "claimed timing difference, four times the size of the difference")));

            AccountReconciliation fee = reconciliation.lineFor(FEE).orElseThrow();
            assertThat(fee.difference()).isEqualTo(Money.inr("125.00"));
            assertThat(fee.unexplained()).isEqualTo(Money.inr("-375.00"));

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("375.00"));
        }

        @Test
        @DisplayName("an explanation pointing the wrong way doubles the residual rather than curing it")
        void wrongDirectionBreaches() {
            // Difference +125.00, explanation −125.00: 125.00 − (−125.00) = 250.00. A sign
            // convention error is a real and easy mistake, and it must not read as a cure. This is
            // why ExplainedDifference carries a signed amount in the same direction as the
            // difference rather than a magnitude plus its own direction enum — two conventions is
            // how this error becomes invisible.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                List.of(gl(GCA, "4250000.00"), gl(FEE, "53000.00"), gl(SUSPENSE, "4820.55")),
                List.of(ExplainedDifference.timing(FEE, Money.inr("-125.00"), "sign the wrong way")));

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("250.00"));
        }

        @Test
        @DisplayName("a single explanation on an account that already ties breaches")
        void spuriousExplanationBreaches() {
            // 0.00 − 250.00 = −250.00. An explanation filed against an account whose books agree
            // makes them disagree, which is the correct reading: the explanation asserts a
            // difference that is not there, so one of the two is wrong.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                glTying(),
                List.of(ExplainedDifference.timing(FEE, Money.inr("250.00"), "explains nothing")));

            InvariantResult result = reconciliation.tiesToGl();
            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(new BigDecimal("250.00"));
        }

        @Test
        @DisplayName("two cancelling explanations on a tying account are reported, not folded into SL-1")
        void cancellingExplanationsAreReportedSeparately() {
            // The one case the subtraction cannot see: +250.00 and −250.00 on an account whose
            // books agree leave nothing unexplained, so SL-1 passes — correctly, because no money
            // is unaccounted for and SL-1's deviation has to keep meaning "the amount the two books
            // disagree by". It is still a defect in the explanation register, and it is reported.
            GlReconciliation reconciliation = GlReconciliation.of(PERIOD, BOOK, subLedger(),
                glTying(),
                List.of(
                    ExplainedDifference.timing(FEE, Money.inr("250.00"), "asserted, not observed"),
                    ExplainedDifference.timing(FEE, Money.inr("-250.00"), "and its mirror")));

            assertThat(reconciliation.tiesToGl().satisfied())
                .as("nothing is unaccounted for, so SL-1 is green")
                .isTrue();
            assertThat(reconciliation.unmatchedExplanations())
                .as("and the register defect is still on the report")
                .hasSize(2);
            assertThat(reconciliation.lineFor(FEE).orElseThrow().carriesUnmatchedExplanation())
                .isTrue();
        }
    }

    @Nested
    @DisplayName("an explanation has to say something, and a nil one is not an explanation")
    class ExplanationShape {

        @Test
        @DisplayName("a cause with no narrative is refused")
        void narrativeMandatory() {
            // The cause is a category; the narrative is the fact. C-13 is a control somebody signs,
            // and a difference is suppressed on the fact.
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    ExplainedDifference.timing(FEE, Money.inr("125.00"), "   "))
                .withMessageContaining("with no narrative");
        }

        @Test
        @DisplayName("a nil explanation is refused, and that is not what SL-1 asserts")
        void nilExplanationRefused() {
            // Safe under the rule against guarding what an invariant asserts: SL-1 asserts the
            // unexplained residual is nil, and "no explanation" is expressed by filing none.
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    ExplainedDifference.timing(FEE, Money.zero(Money.INR), "explains nothing"))
                .withMessageContaining("an explanation of nothing explains nothing");
        }

        @Test
        @DisplayName("OTHER still has to carry a narrative")
        void otherNeedsWords() {
            ExplainedDifference other = new ExplainedDifference(FEE, Money.inr("1.00"),
                DifferenceCause.OTHER, "under investigation with the GL team");
            assertThat(other.selfReversing()).isFalse();
            assertThat(other.cause().statement()).contains("not covered by this taxonomy");
        }
    }

    @Nested
    @DisplayName("inputs with no single residual are refused rather than reported as breaks")
    class Refusals {

        @Test
        @DisplayName("two sub-ledger balances for one contract, book and account")
        void duplicateSubLedgerBalance() {
            List<SubLedgerBalance> doubled = new ArrayList<>(subLedger());
            doubled.add(SubLedgerBalance.of("C1", GCA, Money.inr("1000000.00")));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> GlReconciliation.of(PERIOD, BOOK, doubled, glTying()))
                .withMessageContaining("two sub-ledger balances for C1/MAIN/" + GCA);
        }

        @Test
        @DisplayName("the GL reporting one control account twice")
        void duplicateGlBalance() {
            List<GlControlAccountBalance> twice = new ArrayList<>(glTying());
            twice.add(new GlControlAccountBalance(GCA, BOOK, Money.inr("4250000.00"), "TB-DRAFT"));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> GlReconciliation.of(PERIOD, BOOK, subLedger(), twice))
                .withMessageContaining("reported account " + GCA + " twice");
        }

        @Test
        @DisplayName("a balance stamped with another book")
        void wrongBook() {
            List<SubLedgerBalance> otherBook = new ArrayList<>(subLedger());
            otherBook.add(new SubLedgerBalance("C4", "IFRS", GCA, Money.inr("500.00")));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> GlReconciliation.of(PERIOD, BOOK, otherBook, glTying()))
                .withMessageContaining("stamped book IFRS");
        }

        @Test
        @DisplayName("a cross-currency reconciliation, because a residual across currencies is not a number")
        void mixedCurrency() {
            Currency usd = Currency.getInstance("USD");
            List<GlControlAccountBalance> reported = new ArrayList<>(glTying());
            reported.add(GlControlAccountBalance.of("1502-FX-LOANS", Money.of("100.00", usd), TB));

            assertThatIllegalArgumentException()
                .isThrownBy(() -> GlReconciliation.of(PERIOD, BOOK, subLedger(), reported))
                .withMessageContaining("not a number");
        }

        @Test
        @DisplayName("a GL balance with no stated source is not evidence")
        void sourceRefMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() ->
                    GlControlAccountBalance.of(GCA, Money.inr("1.00"), "  "))
                .withMessageContaining("not evidence of anything");
        }
    }
}
