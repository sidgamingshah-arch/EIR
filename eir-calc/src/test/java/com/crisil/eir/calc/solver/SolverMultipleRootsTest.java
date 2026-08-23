package com.crisil.eir.calc.solver;

import static com.crisil.eir.calc.solver.SolverFixtures.MONTHLY;
import static com.crisil.eir.calc.solver.SolverFixtures.bd;
import static com.crisil.eir.calc.solver.SolverFixtures.deepDiscount;
import static com.crisil.eir.calc.solver.SolverFixtures.rootsAtTenAndFortyPercent;
import static com.crisil.eir.calc.solver.SolverFixtures.rootsAtTenPercentAndThreeHundred;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crisil.eir.calc.Discounting;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.TimeConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The multiple-root policy of calculation specification 4.4, in the order the
 * specification states it.
 *
 * <table border="1">
 *   <caption>4.4, and what this test asserts of each limb</caption>
 *   <tr><th>Condition</th><th>Required outcome</th></tr>
 *   <tr><td>exactly one root in the plausible band</td>
 *       <td>take it, record that disambiguation occurred</td></tr>
 *   <tr><td>several in the band</td>
 *       <td>take the one nearest contractual, mark {@code REQUIRES_REVIEW}</td></tr>
 *   <tr><td>none in the band</td><td>exception queue</td></tr>
 * </table>
 *
 * <p>Every path records <em>all</em> candidate roots, and that is asserted on every
 * path here. A disambiguation that does not show what it chose between cannot be
 * reviewed, and the whole justification for taking one of several mathematically
 * valid IRRs is that the choice is on the record.
 *
 * <p>The vectors are stylised three-flow interim-drawdown profiles whose roots have
 * closed forms — {@code (-1000, +5100, -4400)} has roots at exactly 0.1 and 3.0,
 * {@code (-1000, +2500, -1540)} at exactly 0.1 and 0.4. The economics are those of
 * tranched project finance with a large interim drawdown (3.8), which is where the
 * profile occurs in practice; the arithmetic is kept exact so that what is under
 * test is the policy rather than a rounding coincidence.
 *
 * <p><strong>The band is annualised and the roots are not.</strong> That is why the
 * same three amounts appear twice: read as annual periods the roots are 10% and 40%
 * and both are plausible; read as monthly periods they are 10% and 40% <em>a
 * month</em>, which annualise to 214% and 5,569% and neither is. A band applied to
 * an unannualised root would silently widen by the compounding frequency.
 */
class SolverMultipleRootsTest {

    private static final TimeConvention ANNUAL = new TimeConvention.PeriodicIndex(1);

    private final RateSolver solver = new BracketedNewtonSolver();

    @Test
    @DisplayName("exactly one root in the band: taken, with the disambiguation recorded")
    void oneRootInBandIsTakenAndRecorded() {
        FlowVector vector = rootsAtTenPercentAndThreeHundred(12);
        assertThat(Discounting.signChanges(vector)).isEqualTo(2);

        SolveResult result = solver.solve(SolveRequest.atInception(vector, ANNUAL, null));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.candidateRootCount())
            .as("both mathematically valid IRRs must be on the record, not just the chosen one")
            .isEqualTo(2);
        assertThat(result.candidateRoots().get(0)).isEqualByComparingTo(bd("0.1"));
        assertThat(result.candidateRoots().get(1).setScale(9, RoundingMode.HALF_UP))
            .isEqualByComparingTo(bd("3.0"));
        assertThat(result.rate().periodic()).isEqualByComparingTo(bd("0.1"));
        // 300% a year is outside the default band and 10% is inside it, so the choice is
        // made by the band alone and needs no contractual rate.
        assertThat(result.diagnostic())
            .contains("disambiguated per 4.4(1)")
            .contains("[-0.5, 2.0] annual effective");
    }

    @Test
    @DisplayName("several roots in the band with a contractual rate: nearest taken, flagged REQUIRES_REVIEW")
    void severalRootsInBandTakeTheNearestToContractualAndAreFlagged() {
        FlowVector vector = rootsAtTenAndFortyPercent(12);

        SolveResult result = solver.solve(SolveRequest.atInception(vector, ANNUAL, bd("0.12")));

        // Computed and usable, flagged rather than blocked (4.4(2)): the figure goes to
        // the ledger and the computation goes for approval.
        assertThat(result.status()).isEqualTo(SolveStatus.REQUIRES_REVIEW);
        assertThat(result.status().requiresApproval()).isTrue();
        assertThat(result.status().carriesRate()).isTrue();
        assertThat(result.status().routesToExceptionQueue()).isFalse();
        assertThat(result.hasRate()).isTrue();
        assertThat(result.isSolved())
            .as("REQUIRES_REVIEW is usable but is not SOLVED; a caller filtering on isSolved must "
                + "not sweep it up as clean")
            .isFalse();

        assertThat(result.candidateRootCount()).isEqualTo(2);
        assertThat(result.rate().periodic())
            .as("10% is nearer the 12% contractual rate than 40% is")
            .isEqualByComparingTo(bd("0.1"));
        assertThat(result.diagnostic())
            .contains("2 lie in the plausible band")
            .contains("nearest the contractual rate");
    }

    @Test
    @DisplayName("several roots in the band and no contractual rate: no tie-break exists, so no rate")
    void severalRootsInBandWithoutASeedRoutesToTheExceptionQueue() {
        FlowVector vector = rootsAtTenAndFortyPercent(12);

        SolveResult result = solver.solve(SolveRequest.atInception(vector, ANNUAL, null));

        // 4.4(2) tie-breaks on the contractual rate. Without one the solver does not
        // invent a preference between two equally valid IRRs, and it does not quietly
        // take the lower or the first: that is a judgement, and judgements leave this
        // engine through the exception queue.
        assertThat(result.status()).isEqualTo(SolveStatus.MULTIPLE_ROOTS);
        assertThat(result.status().routesToExceptionQueue()).isTrue();
        assertThat(result.rate()).isNull();
        assertThat(result.candidateRootCount()).isEqualTo(2);
        assertThat(result.diagnostic()).contains("no contractual rate was supplied to choose between them");
        assertThatThrownBy(result::rateOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MULTIPLE_ROOTS");
    }

    @Test
    @DisplayName("no root in the band: exception queue, with every candidate recorded")
    void noRootInBandRoutesToTheExceptionQueue() {
        // The same three amounts, read as monthly periods. 10% and 40% a month
        // annualise to 214% and 5,569% effective, so the band excludes both.
        FlowVector vector = rootsAtTenAndFortyPercent(1);

        SolveResult result = solver.solve(SolveRequest.atInception(vector, MONTHLY, bd("0.01")));

        assertThat(result.status()).isEqualTo(SolveStatus.MULTIPLE_ROOTS);
        assertThat(result.rate()).isNull();
        assertThat(result.method()).isNull();
        assertThat(result.candidateRootCount()).isEqualTo(2);
        assertThat(result.diagnostic())
            .contains("none lies in the plausible band")
            .contains("4.4(3)");
        // Same roots, same amounts, different units: this is the assertion that the band
        // is applied to the annualised root and not to the raw one.
        assertThat(PlausibleBand.standard().contains(bd("0.1"), 1)).isTrue();
        assertThat(PlausibleBand.standard().contains(bd("0.1"), 12)).isFalse();
    }

    @Test
    @DisplayName("a per-product band changes the disambiguation, and says which band it used")
    void aPerProductBandIsHonoured() {
        FlowVector vector = rootsAtTenAndFortyPercent(12);
        // Configured per product (4.4): a band that admits only the lower root turns the
        // REQUIRES_REVIEW above into a clean single-root disambiguation. The policy is
        // the input, not a solver heuristic.
        PlausibleBand narrow = PlausibleBand.of("-0.5", "0.25");

        SolveResult result = solver.solve(
            SolveRequest.atInception(vector, ANNUAL, null).withBand(narrow));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.rate().periodic()).isEqualByComparingTo(bd("0.1"));
        assertThat(result.candidateRootCount()).isEqualTo(2);
        assertThat(result.diagnostic()).contains(narrow.label());
    }

    @Test
    @DisplayName("more than one sign change is necessary for multiple roots, not sufficient")
    void signChangesAloneDoNotMakeAVectorAmbiguous() {
        // A tranched facility with a drawdown after repayment has begun changes sign
        // three times and still has exactly one root on the ladder. The scan is what
        // decides, and it scans the whole ladder every time rather than inferring
        // ambiguity from the shape of the vector.
        FlowVector tranched = SolverFixtures.tranchedWithInterimDrawdown();
        assertThat(Discounting.signChanges(tranched)).isEqualTo(3);

        SolveResult result = solver.solve(SolveRequest.atInception(tranched, MONTHLY, bd("0.01")));

        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
        assertThat(result.candidateRootCount()).isEqualTo(1);
        assertThat(result.diagnostic()).contains("a single root on the ladder");
    }

    @Test
    @DisplayName("an ordinary annuity has one sign change and one root")
    void anOrdinaryAnnuityIsUnambiguous() {
        SolveResult result = solver.solve(
            SolveRequest.atInception(deepDiscount(), MONTHLY, BigDecimal.valueOf(1, 2)));

        assertThat(Discounting.signChanges(deepDiscount())).isEqualTo(1);
        assertThat(result.candidateRootCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(SolveStatus.SOLVED);
    }
}
