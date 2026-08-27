package com.crisil.eir.policy.close;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.exception.ExceptionCategory;
import com.crisil.eir.policy.exception.ExceptionRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;

/**
 * CL-1 and the close gate as properties, each recomputed from the requirement's own definition.
 *
 * <p><b>Why these are legitimate alongside the hand-worked fixtures.</b> Nothing here asks the code
 * what the answer is. Each property states the rule in the words the specification uses and derives
 * the expected answer from the generated inputs by a route that shares no code with the
 * implementation:
 *
 * <ul>
 *   <li>CL-1's definition, from its own javadoc: the deviation is "the count of mutated rows or
 *       figures found in a closed period". The recomputation below counts, with plain
 *       {@link BigDecimal} arithmetic, the keys whose amounts differ at presentation scale plus the
 *       keys present on only one side. It never calls {@code ClosedPeriodImmutability.mutations}.
 *   <li>FR-901's definition, verbatim: "all invariants green, all exceptions cleared or accepted
 *       with approval, all reconciliations tied". The recomputation is that conjunction of three
 *       booleans over the generated inputs, and it never inspects a refusal to decide what the
 *       answer should be.
 * </ul>
 *
 * <p>Both seeds are fixed. A property whose seed moves is a test whose failure cannot be reproduced,
 * and 03 § 1.4's determinism requirement applies to the tests as much as to the engine.
 */
class PeriodClosePropertiesTest {

    private static final LocalDate APRIL_START = LocalDate.of(2027, 4, 1);
    private static final LocalDate APRIL_END = LocalDate.of(2027, 4, 30);
    private static final Instant CLOSING_BEGAN = Instant.parse("2027-05-01T02:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2027-05-05T10:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2027-05-05T09:30:00Z");
    private static final Instant LATER = Instant.parse("2027-08-27T06:00:00Z");

    private static AccountingPeriod closedApril() {
        return AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END)
            .startClosing(CLOSING_BEGAN)
            .attestedClose("financial.controller", CLOSED_AT, CUTOFF);
    }

    private static PeriodStatement statement(
        List<BigDecimal> amounts, Instant asAt) {
        Map<String, Money> figures = new LinkedHashMap<>();
        for (int index = 0; index < amounts.size(); index++) {
            figures.put("F" + index, Money.of(amounts.get(index), Money.INR));
        }
        return new PeriodStatement(202704, asAt, figures);
    }

    /**
     * CL-1's deviation is the count of mutated figures — recomputed from that definition.
     *
     * <p>The two generated lists need not be the same length, and that is deliberate: the overlap
     * exercises a changed amount, a longer published list exercises a deleted figure, and a longer
     * current list exercises one inserted into a closed period. All three are mutations by CL-1's
     * definition and the recomputation below counts all three without consulting the code under
     * test.
     *
     * <p>The comparison is the difference reduced once — {@code (current - published)} rounded to
     * two decimals — because that is the rule of section 1.3, not because that is what the
     * implementation does. Rounding the two operands separately would be a different rule and would
     * disagree on the near-boundary pairs this generator produces.
     */
    @Property(tries = 600, seed = "20270401")
    void clOneCountsEveryFigureThatDoesNotSayWhatItPublished(
        @ForAll @Size(min = 1, max = 6)
        List<@BigRange(min = "-1000000", max = "1000000") @Scale(4) BigDecimal> published,
        @ForAll @Size(min = 1, max = 6)
        List<@BigRange(min = "-1000000", max = "1000000") @Scale(4) BigDecimal> current) {

        // The independent count, straight from CL-1's definition.
        int overlap = Math.min(published.size(), current.size());
        int expected = 0;
        for (int index = 0; index < overlap; index++) {
            BigDecimal difference = current.get(index).subtract(published.get(index))
                .setScale(2, RoundingMode.HALF_UP);
            if (difference.signum() != 0) {
                expected++;
            }
        }
        // Figures published and now absent, and figures present now that were never published.
        expected += Math.abs(published.size() - current.size());

        InvariantResult result = ClosedPeriodImmutability.check(new ClosedPeriodComparison(
            closedApril(), statement(published, CUTOFF), statement(current, LATER)));

        assertThat(result.id()).isEqualTo(InvariantId.CL_1);
        assertThat(result.deviation())
            .as("CL-1's deviation is a count of mutated figures; expected %d", expected)
            .isEqualByComparingTo(BigDecimal.valueOf(expected));
        assertThat(result.satisfied())
            .as("satisfied exactly when nothing moved")
            .isEqualTo(expected == 0);
    }

    /**
     * No difference smaller than half a paise is ever a mutation.
     *
     * <p>The false-breach property. A CL-1 breach starts an investigation into an {@code UPDATE}
     * against a closed period, so a control that fired on a rounding artefact would send somebody
     * looking for something that never happened — and, worse, would train its readers to dismiss
     * CL-1 breaches. The generated shift never reaches 0.005, so by the rounding rule
     * ({@code Precision.MODE} is HALF_UP at the currency's two minor digits) the reduced difference
     * is always 0.00.
     */
    @Property(tries = 400, seed = "20270401")
    void subHalfPaiseDifferencesAreNeverMutations(
        @ForAll @BigRange(min = "-50000000", max = "50000000") @Scale(4) BigDecimal amount,
        @ForAll @BigRange(min = "-0.0049", max = "0.0049") @Scale(4) BigDecimal shift) {

        InvariantResult result = ClosedPeriodImmutability.check(new ClosedPeriodComparison(
            closedApril(),
            statement(List.of(amount), CUTOFF),
            statement(List.of(amount.add(shift)), LATER)));

        assertThat(result.satisfied())
            .as("a shift of %s reduces to 0.00 at INR presentation scale, so nothing moved", shift)
            .isTrue();
    }

    /**
     * FR-901, recomputed from its own sentence: the close is permitted exactly when all invariants
     * are green, all exceptions are cleared, and all reconciliations are presented and tied.
     *
     * <p>The expected answer is that three-way conjunction over the generated inputs. Nothing here
     * looks at a refusal to decide what the answer should be, so the property is a statement about
     * the requirement rather than a restatement of the gate.
     *
     * <p>Note the fourth term in the conjunction, which FR-901 does not state and 02 § 3.1 step 4
     * does: an empty dashboard is not a green one. It is written into the expected answer as
     * {@code !greens.isEmpty()} because a vacuous "all green" over nothing is the one case where the
     * requirement's plain reading is wrong.
     */
    @Property(tries = 500, seed = "20270401")
    void aCloseIsPermittedExactlyWhenFrNineZeroOnesThreeLimbsHold(
        @ForAll @Size(max = 4) List<Boolean> greens,
        @ForAll @IntRange(min = 0, max = 3) int openExceptions,
        @ForAll @IntRange(min = 0, max = 4) int scopesPresented,
        @ForAll @BigRange(min = "-100", max = "100") @Scale(2) BigDecimal residual) {

        List<InvariantResult> dashboard = new ArrayList<>();
        for (int index = 0; index < greens.size(); index++) {
            // Distinct ids, so that nothing is collapsed by conjunction: this property is about
            // the gate's reading of the dashboard, not about how two results under one id merge.
            InvariantId id = InvariantId.values()[index];
            dashboard.add(greens.get(index)
                ? InvariantResult.pass(id, "green")
                : InvariantResult.fail(id, "red", BigDecimal.ONE));
        }

        List<ExceptionRecord> exceptions = new ArrayList<>();
        for (int index = 0; index < openExceptions; index++) {
            exceptions.add(ExceptionRecord.raise("LN-" + index, "RUN-2027-04",
                ExceptionCategory.NO_SOLUTION, "solver found no root",
                "s3://payloads/LN-" + index + ".json"));
        }

        List<ReconciliationTie> ties = new ArrayList<>();
        for (int index = 0; index < scopesPresented; index++) {
            // The residual lands on the first presented scope; the rest tie exactly.
            BigDecimal appliedResidual = index == 0 ? residual : BigDecimal.ZERO;
            ties.add(new ReconciliationTie(ReconciliationScope.values()[index],
                Money.inr("1200000.00"),
                Money.of(new BigDecimal("1200000.00").add(appliedResidual), Money.INR),
                "generated"));
        }

        boolean allGreen = !greens.isEmpty() && greens.stream().allMatch(green -> green);
        boolean allExceptionsCleared = openExceptions == 0;
        boolean allReconciliationsTied =
            scopesPresented == ReconciliationScope.values().length && residual.signum() == 0;
        boolean expected = allGreen && allExceptionsCleared && allReconciliationsTied;

        CloseDecision decision = PeriodCloseGate.evaluate(new CloseRequest(
            AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END)
                .startClosing(CLOSING_BEGAN),
            "financial.controller", CLOSED_AT, CUTOFF, dashboard, exceptions, List.of(), ties));

        assertThat(decision.permitted())
            .as("FR-901: all invariants green (%s, over %d presented), all exceptions cleared"
                + " (%s), all reconciliations tied (%s)",
                allGreen, greens.size(), allExceptionsCleared, allReconciliationsTied)
            .isEqualTo(expected);
    }

    /**
     * A reconciliation nobody presented is refused once, and once per absent scope.
     *
     * <p>The count is derived from step 6's list — four scopes — minus what was presented, and it
     * matters that it is a count rather than a boolean: an operator with two absent
     * reconciliations has two pieces of work, and a gate reporting "reconciliations incomplete"
     * once sends them back to the gate to discover the second.
     */
    @Property(tries = 100, seed = "20270401")
    void everyAbsentReconciliationIsRefusedOnItsOwn(
        @ForAll @IntRange(min = 0, max = 4) int scopesPresented) {

        List<ReconciliationTie> ties = new ArrayList<>();
        for (int index = 0; index < scopesPresented; index++) {
            ties.add(new ReconciliationTie(ReconciliationScope.values()[index],
                Money.inr("0.00"), Money.inr("0.00"), "generated"));
        }

        CloseDecision decision = PeriodCloseGate.evaluate(new CloseRequest(
            AccountingPeriod.open(202704, "FY2027-28", APRIL_START, APRIL_END)
                .startClosing(CLOSING_BEGAN),
            "financial.controller", CLOSED_AT, CUTOFF,
            List.of(InvariantResult.pass(InvariantId.IC_1, "green")),
            List.of(), List.of(), ties));

        long absent = decision.refusals().stream()
            .filter(refusal -> refusal.reason() == CloseGateRefusal.RECONCILIATION_NOT_PRESENTED)
            .count();

        assertThat(absent)
            .as("four scopes required by 02 § 3.1 step 6, %d presented", scopesPresented)
            .isEqualTo(ReconciliationScope.values().length - scopesPresented);
    }
}
