package com.crisil.eir.policy.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariant CL-1 and FR-902's restatement mechanism.
 *
 * <p><b>Where the expected values come from.</b> The mutation counts are counted by hand off the
 * fixture and stated at each assertion. The two figures that are not round come from elsewhere in
 * the specification and are quoted rather than computed here: 19.50 is reference case 1's published
 * unamortised fee at period 23, and the pair 242,103,892.5032 / 242,103,892.5063 is the measured
 * near-boundary case written up in {@code InvariantResult.ofMoney}'s javadoc — used here because it
 * is the exact shape that would produce a false CL-1 breach if the comparison rounded both operands
 * instead of reducing the difference once.
 *
 * <p><b>What input makes CL-1 fail:</b> two statements of one closed period that disagree — a
 * changed amount, a figure that has gone, or a figure that has appeared. Each is exercised below,
 * and each is a state no Java type in this package prevents, which is the whole reason the control
 * is built from two statements rather than from one immutable period record.
 */
class ClosedPeriodImmutabilityTest {

    private static final LocalDate APRIL_START = LocalDate.of(2027, 4, 1);
    private static final LocalDate APRIL_END = LocalDate.of(2027, 4, 30);
    private static final Instant CLOSING_BEGAN = Instant.parse("2027-05-01T02:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2027-05-05T10:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2027-05-05T09:30:00Z");
    private static final Instant LATER = Instant.parse("2027-08-27T06:00:00Z");
    private static final Currency USD = Currency.getInstance("USD");

    private static final String GCA = "PERIOD_BALANCE:total:gross_carrying_amount";
    private static final String INTEREST = "PERIOD_BALANCE:total:eir_interest_income";
    private static final String ECL = "PERIOD_BALANCE:total:ecl_allowance";
    private static final String FEE = "PERIOD_BALANCE:LN-1:unamortised_fee";

    private static AccountingPeriod closedApril() {
        return AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END)
            .startClosing(CLOSING_BEGAN)
            .attestedClose("financial.controller", CLOSED_AT, CUTOFF);
    }

    private static AccountingPeriod closedMay() {
        return AccountingPeriod.open(202705, "FY2027-28",
                LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 31))
            .startClosing(Instant.parse("2027-06-01T02:00:00Z"))
            .attestedClose("financial.controller", Instant.parse("2027-06-05T10:00:00Z"),
                Instant.parse("2027-06-05T09:30:00Z"));
    }

    /** The four figures April published. */
    private static PeriodStatement publishedApril() {
        Map<String, Money> figures = new LinkedHashMap<>();
        figures.put(GCA, Money.inr("240362965.70"));
        figures.put(INTEREST, Money.inr("2525.04"));
        figures.put(ECL, Money.inr("-1200000.00"));
        // Reference case 1, period 23: the working difference 19.4966 publishes as 19.50.
        figures.put(FEE, Money.inr("19.50"));
        return new PeriodStatement(202704, CUTOFF, figures);
    }

    private static InvariantResult checkApril(PeriodStatement current) {
        return ClosedPeriodImmutability.check(
            new ClosedPeriodComparison(closedApril(), publishedApril(), current));
    }

    @Nested
    @DisplayName("CL-1: a closed period is never mutated")
    class ClOne {

        @Test
        @DisplayName("a period saying what it published passes, under CL-1 and no other id")
        void unmutatedPasses() {
            InvariantResult result = checkApril(
                new PeriodStatement(202704, LATER, publishedApril().figures()));

            assertThat(result.id())
                .as("its own identifier; CL-1 is not a replay claim and must not borrow DT-1")
                .isEqualTo(InvariantId.CL_1);
            assertThat(result.satisfied()).isTrue();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.detail())
                .as("the passing sentence names the attestation an auditor will ask for")
                .contains("4 figure(s) unchanged", "financial.controller");
        }

        @Test
        @DisplayName("a figure edited in place fails, deviation 1")
        void changedFigureFails() {
            // The failure CL-1's javadoc calls the one that looks like diligence: somebody found
            // an error and fixed it where fixing normally happens. 2,525.04 becomes 2,775.04.
            InvariantResult result = checkApril(
                publishedApril().withFigure(INTEREST, Money.inr("2775.04"), LATER));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation())
                .as("one mutated figure; CL-1's deviation is a count of them")
                .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail()).contains("CHANGED from INR 2525.04 to INR 2775.04");
        }

        @Test
        @DisplayName("a published figure that has gone fails: absent is not equal")
        void removedFigureFails() {
            // A DELETE, or an archive step that dropped rows it was meant to copy. A reader of the
            // current statement alone has no indication anything is missing.
            InvariantResult result = checkApril(publishedApril().withoutFigure(FEE, LATER));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail()).contains("REMOVED (published INR 19.50)");
        }

        @Test
        @DisplayName("a figure the period never published fails: a write into a closed partition")
        void addedFigureFails() {
            InvariantResult result = checkApril(publishedApril()
                .withFigure("PERIOD_BALANCE:LN-9:gross_carrying_amount",
                    Money.inr("500000.00"), LATER));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail()).contains("ADDED (INR 500000.00, never published)");
        }

        @Test
        @DisplayName("three mutations count three, and the report names all three")
        void countsEveryMutation() {
            // Counted by hand: GCA changed, FEE removed, LN-9 added. Three.
            PeriodStatement current = publishedApril()
                .withFigure(GCA, Money.inr("240362965.71"), LATER)
                .withoutFigure(FEE, LATER)
                .withFigure("PERIOD_BALANCE:LN-9:gross_carrying_amount",
                    Money.inr("1.00"), LATER);

            InvariantResult result = checkApril(current);

            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(result.detail())
                .as("one result, whose detail carries the whole population")
                .contains("CHANGED", "REMOVED", "ADDED", "3 mutated figure(s)");
        }

        @Test
        @DisplayName("two mutations in opposite directions do not net to a pass")
        void oppositeMutationsDoNotNet() {
            // The reason CL-1's deviation is a count and not a signed money total: +250.00 on
            // interest income and -250.00 on the allowance sum to zero. Two wrong figures, and a
            // signed aggregate would report the period unmutated.
            PeriodStatement current = publishedApril()
                .withFigure(INTEREST, Money.inr("2775.04"), LATER)
                .withFigure(ECL, Money.inr("-1200250.00"), LATER);

            InvariantResult result = checkApril(current);
            ClosedPeriodComparison comparison =
                new ClosedPeriodComparison(closedApril(), publishedApril(), current);

            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(ClosedPeriodImmutability.mutatedAmount(comparison, Money.INR))
                .as("|+250.00| + |-250.00| = 500.00, worked by hand")
                .isEqualTo(Money.inr("500.00"));
        }

        @Test
        @DisplayName("a sub-paise difference is not a mutation, because the difference is reduced once")
        void subPaiseDifferenceIsNotAMutation() {
            // The measured case from InvariantResult.ofMoney: 242,103,892.5063 -
            // 242,103,892.5032 = 0.0031, which is 0.00 at presentation scale. Rounding the two
            // operands first would give ...892.51 - ...892.50 = 0.01 and report a mutation of a
            // figure nobody touched — a false CL-1 breach on a closed period, which is an
            // investigation into an UPDATE that never happened.
            Map<String, Money> published = Map.of(GCA, Money.inr("242103892.5032"));
            Map<String, Money> current = Map.of(GCA, Money.inr("242103892.5063"));

            InvariantResult result = ClosedPeriodImmutability.check(new ClosedPeriodComparison(
                closedApril(),
                new PeriodStatement(202704, CUTOFF, published),
                new PeriodStatement(202704, LATER, current)));

            assertThat(result.satisfied())
                .as("0.0031 rounds to 0.00 at INR presentation scale")
                .isTrue();
        }

        @Test
        @DisplayName("a difference of exactly half a paise is a mutation")
        void halfAPaiseIsAMutation() {
            // The other side of the same boundary: 100.005 - 100.000 = 0.005, which rounds
            // HALF_UP at two decimals to 0.01. Precision.MODE is HALF_UP, so this is a mutation.
            InvariantResult result = ClosedPeriodImmutability.check(new ClosedPeriodComparison(
                closedApril(),
                new PeriodStatement(202704, CUTOFF, Map.of(GCA, Money.inr("100.000"))),
                new PeriodStatement(202704, LATER, Map.of(GCA, Money.inr("100.005")))));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a redenominated figure is reported as a mutation, not thrown")
        void currencyChangeIsAMutation() {
            // Money.minus would throw across currencies. Throwing here would turn a serious
            // mutation — a figure in a closed period now denominated in another currency — into a
            // crash that reports nothing.
            InvariantResult result = ClosedPeriodImmutability.check(new ClosedPeriodComparison(
                closedApril(),
                new PeriodStatement(202704, CUTOFF, Map.of(GCA, Money.inr("1000.00"))),
                new PeriodStatement(202704, LATER, Map.of(GCA, Money.of("1000.00", USD)))));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.detail()).contains("USD 1000.00");
        }

        @Test
        @DisplayName("figures inserted into a period that published none are detected")
        void insertsIntoAnEmptyPublishedStatementAreDetected() {
            // Why an empty published statement is allowed: a period with no exposures publishes
            // nothing, and two figures appearing in it afterwards is exactly the kind of write
            // into a closed partition CL-1 exists to catch. Counted by hand: two.
            InvariantResult result = ClosedPeriodImmutability.check(new ClosedPeriodComparison(
                closedApril(),
                PeriodStatement.empty(202704, CUTOFF),
                new PeriodStatement(202704, LATER, Map.of(
                    GCA, Money.inr("1.00"), INTEREST, Money.inr("2.00")))));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.valueOf(2));
        }
    }

    @Nested
    @DisplayName("CL-1 refuses to be a control that cannot fail")
    class NotATautology {

        @Test
        @DisplayName("two empty statements are refused, not passed")
        void twoEmptyStatementsRefused() {
            // The CL-1 form of "an empty dashboard is not a green one". Two empty statements
            // compare equal, so the control would report satisfied on a period nobody read.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ClosedPeriodComparison(
                    closedApril(),
                    PeriodStatement.empty(202704, CUTOFF),
                    PeriodStatement.empty(202704, LATER)))
                .withMessageContaining("two empty statements");
        }

        @Test
        @DisplayName("CL-1 over an open period is a wiring defect, and throws")
        void openPeriodRefused() {
            // An open period is SUPPOSED to change. A control claiming its figures have not is a
            // false pass or a false breach depending on the hour.
            AccountingPeriod open = AccountingPeriod.open(
                202704, "FY2027-28", APRIL_START, APRIL_END);

            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ClosedPeriodComparison(
                    open, publishedApril(), publishedApril()))
                .withMessageContaining("OPEN or CLOSING period is");
        }

        @Test
        @DisplayName("two statements for different periods are refused")
        void mismatchedPeriodsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ClosedPeriodComparison(
                    closedApril(), publishedApril(),
                    new PeriodStatement(202705, LATER, publishedApril().figures())))
                .withMessageContaining("comparing two periods reports every figure as mutated");
        }

        @Test
        @DisplayName("the two statements the wrong way round are refused")
        void reversedStatementsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ClosedPeriodComparison(
                    closedApril(),
                    new PeriodStatement(202704, LATER, publishedApril().figures()),
                    new PeriodStatement(202704, CUTOFF, publishedApril().figures())))
                .withMessageContaining("wrong way round");
        }

        @Test
        @DisplayName("CL-1 over no periods at all is refused")
        void sweepOverNothingRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ClosedPeriodImmutability.checkAll(
                    List.of(), RestatementRegister.empty()))
                .withMessageContaining("asserted over no periods");
        }
    }

    @Nested
    @DisplayName("one result per invariant, with the deviation aggregated deliberately")
    class Aggregation {

        @Test
        @DisplayName("a sweep over two periods counts every mutation in both")
        void sweepAggregatesCounts() {
            // Hand-counted: April has two mutations (interest changed, fee removed), May has one
            // (interest changed). Total three.
            ClosedPeriodComparison april = new ClosedPeriodComparison(
                closedApril(), publishedApril(),
                publishedApril()
                    .withFigure(INTEREST, Money.inr("2775.04"), LATER)
                    .withoutFigure(FEE, LATER));
            Map<String, Money> mayPublished = Map.of(INTEREST, Money.inr("3000.00"));
            ClosedPeriodComparison may = new ClosedPeriodComparison(
                closedMay(),
                new PeriodStatement(202705, Instant.parse("2027-06-05T09:30:00Z"), mayPublished),
                new PeriodStatement(202705, LATER, Map.of(INTEREST, Money.inr("3100.00"))));

            InvariantResult swept = ClosedPeriodImmutability.checkAll(
                List.of(april, may), RestatementRegister.empty());

            assertThat(swept.id()).isEqualTo(InvariantId.CL_1);
            assertThat(swept.deviation())
                .as("2 in April + 1 in May = 3, aggregated before publication")
                .isEqualByComparingTo(BigDecimal.valueOf(3));
            assertThat(swept.detail()).contains("period 202704", "period 202705");
        }

        @Test
        @DisplayName("conjoining two per-period results would have lost the second count")
        void conjunctionWouldLoseACount() {
            // The reason checkAll exists. InvariantResult.conjunction keeps only the FIRST
            // breach's deviation among results sharing an id, so a caller that conjoined April
            // and May would publish 2 and silently discard May's 1.
            InvariantResult aprilOnly = InvariantResult.fail(
                InvariantId.CL_1, "April: 2 mutated", BigDecimal.valueOf(2));
            InvariantResult mayOnly = InvariantResult.fail(
                InvariantId.CL_1, "May: 1 mutated", BigDecimal.ONE);

            InvariantResult conjoined =
                InvariantResult.conjunction(List.of(aprilOnly, mayOnly));

            assertThat(conjoined.deviation())
                .as("April's count survives; May's is gone — which is why aggregation happens"
                    + " before the result is built, not after")
                .isEqualByComparingTo(BigDecimal.valueOf(2));
        }
    }

    @Nested
    @DisplayName("FR-902: a correction creates a restatement artefact")
    class Restatements {

        private static final LocalDate MID_APRIL = LocalDate.of(2027, 4, 15);
        private static final Instant RECORDED = Instant.parse("2027-05-20T09:00:00Z");

        private static RestatementArtefact restatement(Money original, Money corrected) {
            return RestatementArtefact.correcting(
                closedApril(), 202705, "RST-2027-001", INTEREST, original, corrected,
                MID_APRIL, RECORDED, "internal.audit", "financial.controller",
                "a DSA commission posted 2027-04-15 was classified AS_INCURRED and is INTEGRAL");
        }

        @Test
        @DisplayName("both totals in this package treat another currency the same way, and disclose it")
        void multiCurrencyIsAnsweredOnceForThePackage() {
            // Two totals were written in one package for one kind of question and answered it in
            // opposite ways: ClosedPeriodImmutability.mutatedAmount skipped a contribution in
            // another currency, and RestatementRegister.netRestated let Money.plus THROW on the
            // same condition. A caller could not tell which behaviour to expect from which, and
            // one of the two made a multi-currency register unreportable in every currency rather
            // than reportable in each.
            //
            // Both filter now. But a restatement dropped from a MATERIALITY figure understates
            // materiality, so silence is not acceptable either — restatementsOutside names what
            // was left out, which is what lets a caller state its own population.
            java.util.Currency usd = java.util.Currency.getInstance("USD");
            RestatementArtefact inr = restatement(Money.inr("2525.04"), Money.inr("2775.04"));
            RestatementArtefact foreign = RestatementArtefact.correcting(
                closedApril(), 202705, "RST-2027-002", INTEREST,
                Money.of("1000.00", usd), Money.of("1400.00", usd),
                MID_APRIL, RECORDED, "internal.audit", "financial.controller",
                "a USD-book posting on the same misclassification");
            RestatementRegister register = RestatementRegister.empty().with(inr).with(foreign);

            assertThat(register.netRestated(202704, Money.INR))
                .as("250.00, and it does not throw on the USD artefact — which it used to")
                .isEqualTo(Money.inr("250.00"));
            assertThat(register.absoluteRestated(202704, Money.INR))
                .isEqualTo(Money.inr("250.00"));
            assertThat(register.restatementsOutside(202704, Money.INR))
                .as("and the figure it could not include is named, not silently dropped")
                .containsExactly(foreign);
            assertThat(register.inCurrency(202704, Money.INR)).containsExactly(inr);
            assertThat(register.netRestated(202704, usd))
                .as("reportable in each currency, which is the point of filtering over throwing")
                .isEqualTo(Money.of("400.00", usd));
        }

        @Test
        @DisplayName("the correct flow: April keeps its figures, May carries the movement")
        void historyIsNeverRewritten() {
            // 04 § 5 in one test. Business time stays inside April (valid_from 2027-04-15), system
            // time is after the close (recorded 2027-05-20), and accounting time moves — the
            // movement is recognised in period 202705. April's current statement therefore still
            // equals what it published, so CL-1 passes and DT-1 still replays.
            RestatementArtefact artefact =
                restatement(Money.inr("2525.04"), Money.inr("2775.04"));
            RestatementRegister register = RestatementRegister.empty().with(artefact);

            InvariantResult result = ClosedPeriodImmutability.check(
                new ClosedPeriodComparison(closedApril(), publishedApril(),
                    new PeriodStatement(202704, LATER, publishedApril().figures())),
                register);

            assertThat(result.satisfied())
                .as("the closed period keeps its published figures; the correction lives in May")
                .isTrue();
            assertThat(artefact.delta())
                .as("hand arithmetic: 2775.04 - 2525.04 = 250.00")
                .isEqualTo(Money.inr("250.00"));
            assertThat(register.recognisedIn(202705)).containsExactly(artefact);
            assertThat(register.restatedFigures(202704)).containsExactly(INTEREST);
        }

        @Test
        @DisplayName("a restatement on file does NOT excuse a figure edited in place")
        void restatementDoesNotExcuseAMutation() {
            // The worst case, not the excused one: the movement is now recognised twice — once
            // inside April where it should never have appeared, and once in May where the artefact
            // points. Both periods are wrong.
            RestatementRegister register = RestatementRegister.empty()
                .with(restatement(Money.inr("2525.04"), Money.inr("2775.04")));

            InvariantResult result = ClosedPeriodImmutability.check(
                new ClosedPeriodComparison(closedApril(), publishedApril(),
                    publishedApril().withFigure(INTEREST, Money.inr("2775.04"), LATER)),
                register);

            assertThat(result.satisfied()).isFalse();
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.detail())
                .as("the detail says which failure this is, because it is the expensive one")
                .contains("recognised twice");
        }

        @Test
        @DisplayName("a correction recognised in the period it corrects is refused")
        void correctionInThePeriodItCorrects() {
            // The guard that is the whole requirement: this is the in-place fix with an artefact
            // filed next to it.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestatementArtefact.correcting(
                    closedApril(), 202704, "RST-2027-002", INTEREST, Money.inr("2525.04"),
                    Money.inr("2775.04"), MID_APRIL, RECORDED, "internal.audit",
                    "financial.controller", "misclassified fee"))
                .withMessageContaining("mutation of a closed period (FR-902)");
        }

        @Test
        @DisplayName("a correction carried backwards into an earlier period is refused")
        void correctionCarriedBackwards() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestatementArtefact.correcting(
                    closedApril(), 202703, "RST-2027-003", INTEREST, Money.inr("2525.04"),
                    Money.inr("2775.04"), MID_APRIL, RECORDED, "internal.audit",
                    "financial.controller", "misclassified fee"))
                .withMessageContaining("earlier period 202703");
        }

        @Test
        @DisplayName("business time outside the corrected period is refused")
        void businessTimeMustFallInTheCorrectedPeriod() {
            // 2027-05-15 encodes as 202705, not 202704. The arithmetic is the DDL's own
            // (AccountingPeriod.periodIdOf), so the two spellings cannot drift.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestatementArtefact.correcting(
                    closedApril(), 202705, "RST-2027-004", INTEREST, Money.inr("2525.04"),
                    Money.inr("2775.04"), LocalDate.of(2027, 5, 15), RECORDED, "internal.audit",
                    "financial.controller", "misclassified fee"))
                .withMessageContaining("falls in period 202705");
        }

        @Test
        @DisplayName("a restatement recorded before the close is refused: the replay would move")
        void recordedBeforeTheClose() {
            // A version recorded before version_cutoff_at is inside the set a replay reads, so
            // the period would stop replaying to what it published (DT-1).
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestatementArtefact.correcting(
                    closedApril(), 202705, "RST-2027-005", INTEREST, Money.inr("2525.04"),
                    Money.inr("2775.04"), MID_APRIL, Instant.parse("2027-05-05T09:00:00Z"),
                    "internal.audit", "financial.controller", "misclassified fee"))
                .withMessageContaining("DT-1");
        }

        @Test
        @DisplayName("a zero-delta restatement is refused: it restates nothing")
        void zeroDeltaRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> restatement(Money.inr("2525.04"), Money.inr("2525.0401")))
                .withMessageContaining("restates nothing");
        }

        @Test
        @DisplayName("a self-approved restatement is refused, on identity and not on the raw string")
        void selfApprovedRestatementRefused() {
            // FourEyes strips and case-folds, so 'internal.audit' and ' Internal.Audit ' are one
            // person. A plain equals here would let a restatement of published accounts be a
            // one-person decision under a different capitalisation.
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestatementArtefact.correcting(
                    closedApril(), 202705, "RST-2027-006", INTEREST, Money.inr("2525.04"),
                    Money.inr("2775.04"), MID_APRIL, RECORDED, "internal.audit",
                    " Internal.Audit ", "misclassified fee"))
                .withMessageContaining("not a one-person");
        }

        @Test
        @DisplayName("a register nets for disclosure and grosses for materiality")
        void netAndAbsoluteRestated() {
            // Two corrections in opposite directions: +250.00 on interest income and -250.00 on
            // the allowance. Net 0.00; gross 500.00. A register reporting only the net would say a
            // period whose two lines were both wrong needed no disclosure.
            RestatementRegister register = RestatementRegister.of(List.of(
                restatement(Money.inr("2525.04"), Money.inr("2775.04")),
                RestatementArtefact.correcting(
                    closedApril(), 202705, "RST-2027-007", ECL, Money.inr("-1200000.00"),
                    Money.inr("-1200250.00"), MID_APRIL, RECORDED, "internal.audit",
                    "financial.controller", "allowance discounted at the contractual rate")));

            assertThat(register.netRestated(202704, Money.INR))
                .as("+250.00 and -250.00 net to zero — the disclosure figure, and useless alone")
                .isEqualTo(Money.inr("0.00"));
            assertThat(register.absoluteRestated(202704, Money.INR))
                .as("|+250.00| + |-250.00| = 500.00, worked by hand")
                .isEqualTo(Money.inr("500.00"));
            assertThat(register.holdsRestatementOf(202704, INTEREST)).isTrue();
            assertThat(register.holdsRestatementOf(202704, GCA)).isFalse();
        }

        @Test
        @DisplayName("a restatement of an open period is refused: it is an ordinary posting")
        void openPeriodNeedsNoRestatement() {
            AccountingPeriod open = AccountingPeriod.open(
                202704, "FY2027-28", APRIL_START, APRIL_END);

            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> RestatementArtefact.correcting(
                    open, 202705, "RST-2027-008", INTEREST, Money.inr("2525.04"),
                    Money.inr("2775.04"), MID_APRIL, RECORDED, "internal.audit",
                    "financial.controller", "misclassified fee"))
                .withMessageContaining("needs no restatement artefact");
        }
    }
}
