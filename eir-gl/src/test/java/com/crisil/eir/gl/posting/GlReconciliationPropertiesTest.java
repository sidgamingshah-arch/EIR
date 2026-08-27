package com.crisil.eir.gl.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;

/**
 * Invariant SL-1 over generated populations, recomputed from FR-803's definition.
 *
 * <p><b>Why a hand-built fixture is not enough.</b> {@code GlReconciliationTest} pins the accounting
 * readings — what counts as explained, what over-explanation means — on figures a reader can check
 * by eye, which is the right way to fix an interpretation and the wrong way to establish that an
 * <em>aggregate over a population</em> is correct. SL-1's deviation is a total over an arbitrary
 * account set, and a total is exactly the kind of figure that is right on a three-account fixture
 * and wrong on a book: a filter that drops an account present on only one side, an {@code abs} in
 * the wrong place, a residual rounded twice.
 *
 * <p><b>No expected value here comes from running the code under test.</b> Every property recomputes
 * the answer two independent ways and requires them to agree with each other before comparing
 * against {@link GlReconciliation}:
 *
 * <ol>
 *   <li>from FR-803's wording, by walking the raw input lists with nested loops — for each account
 *       in the union, sum the sub-ledger balances, take the GL balance, sum the explanations,
 *       subtract, take the absolute value, add up. Deliberately the slowest possible reading of the
 *       requirement, and it shares no code with the implementation's map-based grouping;
 *   <li>from the algebra of how the fixture was constructed. Each account's GL balance is set to the
 *       sub-ledger total <em>less a chosen skew</em>, so the difference is the skew by construction
 *       and the unexplained residual is {@code skew − explanation}, worked out on paper.
 * </ol>
 *
 * <p>Two derivations rather than one because they fail differently: (1) catches an implementation
 * that misreads the requirement, (2) catches a test that misbuilds its own fixture.
 */
class GlReconciliationPropertiesTest {

    private static final int PERIOD = 202804;
    private static final String BOOK = "MAIN";
    private static final String SOURCE = "TB-202804-GENERATED";

    private static final List<String> ACCOUNTS = List.of(
        "1301-LOANS-GCA",
        "1401-EIR-RECEIVABLE",
        "1409-INTEREST-SUSPENSE",
        "2301-UNAMORTISED-FEE",
        "4101-INTEREST-INCOME",
        "5201-BASIS-ADJUSTMENT");

    /** Rupees-and-paise from a whole number of paise, so the arithmetic stays exact. */
    private static Money paise(long amount) {
        return Money.of(BigDecimal.valueOf(amount).movePointLeft(2), Money.INR);
    }

    /**
     * SL-1's deviation is the total absolute unexplained residual, and its verdict is that every
     * account's residual is nil.
     *
     * <p>Skews and explanations both span negative, zero and positive, so the generated set contains
     * accounts that tie, accounts explained exactly, accounts partly explained, accounts
     * over-explained, and accounts with an explanation pointing the wrong way — in arbitrary
     * proportion, including all of one kind.
     */
    @Property(tries = 600)
    void slOneIsTheTotalAbsoluteUnexplainedResidual(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = -50000, max = 50000) Integer> skews,
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = -50000, max = 50000) Integer> excuses,
        @ForAll @BigRange(min = "0.00", max = "9000000") @Scale(2) BigDecimal base) {
        int accounts = Math.min(skews.size(), excuses.size());
        List<SubLedgerBalance> subLedger = new ArrayList<>();
        List<GlControlAccountBalance> reported = new ArrayList<>();
        List<ExplainedDifference> explanations = new ArrayList<>();

        // Derivation (2), built in: each account's GL balance is the sub-ledger total less the skew,
        // so the difference IS the skew and the unexplained residual is skew − explanation.
        BigDecimal fromAlgebra = BigDecimal.ZERO;
        for (int index = 0; index < accounts; index++) {
            String account = ACCOUNTS.get(index);
            // Two contracts per account, so the sub-ledger side is genuinely a sum rather than a
            // single figure being passed through.
            Money first = Money.of(base, Money.INR);
            Money second = paise(index * 137L);
            subLedger.add(SubLedgerBalance.of("C" + index + "-A", account, first));
            subLedger.add(SubLedgerBalance.of("C" + index + "-B", account, second));

            Money skew = paise(skews.get(index));
            reported.add(GlControlAccountBalance.of(
                account, first.plus(second).minus(skew), SOURCE));

            long excuse = excuses.get(index);
            if (excuse != 0) {
                explanations.add(ExplainedDifference.timing(
                    account, paise(excuse), "generated explanation " + index));
            }
            fromAlgebra = fromAlgebra.add(
                BigDecimal.valueOf(skews.get(index) - excuse).movePointLeft(2).abs());
        }

        // Derivation (1): FR-803's wording, walked over the raw inputs.
        SortedSet<String> union = new TreeSet<>();
        for (SubLedgerBalance balance : subLedger) {
            union.add(balance.controlAccountCode());
        }
        for (GlControlAccountBalance balance : reported) {
            union.add(balance.accountCode());
        }
        for (ExplainedDifference explanation : explanations) {
            union.add(explanation.accountCode());
        }
        BigDecimal fromDefinition = BigDecimal.ZERO;
        boolean everyAccountTies = true;
        for (String account : union) {
            BigDecimal subTotal = BigDecimal.ZERO;
            for (SubLedgerBalance balance : subLedger) {
                if (balance.controlAccountCode().equals(account)) {
                    subTotal = subTotal.add(balance.closingBalance().amount());
                }
            }
            BigDecimal glBalance = BigDecimal.ZERO;
            for (GlControlAccountBalance balance : reported) {
                if (balance.accountCode().equals(account)) {
                    glBalance = balance.balance().amount();
                }
            }
            BigDecimal explained = BigDecimal.ZERO;
            for (ExplainedDifference explanation : explanations) {
                if (explanation.accountCode().equals(account)) {
                    explained = explained.add(explanation.amount().amount());
                }
            }
            // Reduced once, on the residual — the rule InvariantResult.ofMoney sets out.
            BigDecimal unexplained = subTotal.subtract(glBalance).subtract(explained)
                .setScale(2, RoundingMode.HALF_UP);
            if (unexplained.signum() != 0) {
                everyAccountTies = false;
                fromDefinition = fromDefinition.add(unexplained.abs());
            }
        }

        assertThat(fromDefinition)
            .as("the two independent derivations must agree before either is trusted")
            .isEqualByComparingTo(fromAlgebra);

        InvariantResult result =
            GlReconciliation.of(PERIOD, BOOK, subLedger, reported, explanations).tiesToGl();
        assertThat(result.id()).isEqualTo(InvariantId.SL_1);
        assertThat(result.satisfied())
            .as("skews %s, explanations %s, base %s; detail: %s",
                skews.subList(0, accounts), excuses.subList(0, accounts), base, result.detail())
            .isEqualTo(everyAccountTies);
        if (!everyAccountTies) {
            assertThat(result.deviation()).isEqualByComparingTo(fromDefinition);
        } else {
            assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * An explanation equal to the difference always ties the account, whatever the difference is.
     *
     * <p>The definition of "explained", asserted directly. It is the property that would break if
     * the residual were ever computed as {@code |difference| − |explained|}: that form ties on a
     * matching explanation too, so this property alone does not distinguish them — which is why
     * {@link #overExplainingNeverTies} is here as well.
     */
    @Property(tries = 500)
    void anExactExplanationAlwaysTies(
        @ForAll @Size(min = 1, max = 6) List<@IntRange(min = -80000, max = 80000) Integer> skews,
        @ForAll @BigRange(min = "0.00", max = "9000000") @Scale(2) BigDecimal base) {
        List<SubLedgerBalance> subLedger = new ArrayList<>();
        List<GlControlAccountBalance> reported = new ArrayList<>();
        List<ExplainedDifference> explanations = new ArrayList<>();
        for (int index = 0; index < skews.size(); index++) {
            String account = ACCOUNTS.get(index);
            Money total = Money.of(base, Money.INR);
            subLedger.add(SubLedgerBalance.of("C" + index, account, total));
            Money skew = paise(skews.get(index));
            reported.add(GlControlAccountBalance.of(account, total.minus(skew), SOURCE));
            if (skews.get(index) != 0) {
                // Exactly the difference, in the same direction. A zero skew needs no explanation,
                // and a nil ExplainedDifference is refused at construction.
                explanations.add(
                    ExplainedDifference.timing(account, skew, "explains the whole difference"));
            }
        }

        InvariantResult result =
            GlReconciliation.of(PERIOD, BOOK, subLedger, reported, explanations).tiesToGl();
        assertThat(result.satisfied())
            .as("skews %s fully explained; detail: %s", skews, result.detail())
            .isTrue();
        assertThat(result.deviation()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Over-explaining never ties, and the residual is the excess.
     *
     * <p>The property that rules out the capped form {@code max(0, |difference| − |explained|)}. Under
     * the cap an explanation of any size at least the difference ties the account, so a wrong
     * explanation silences a real break and "the explanation does not match the difference" cannot
     * surface at all. Under the subtraction the excess is what is left, in the opposite direction.
     *
     * <p>The excess is generated strictly positive so that every case really is an over-explanation;
     * the difference itself spans both signs, because a sign error in the residual would otherwise
     * hide behind a one-directional fixture.
     */
    @Property(tries = 500)
    void overExplainingNeverTies(
        @ForAll @IntRange(min = -60000, max = 60000) int skewInPaise,
        @ForAll @IntRange(min = 1, max = 60000) int excessInPaise,
        @ForAll @BigRange(min = "0.00", max = "9000000") @Scale(2) BigDecimal base) {
        String account = ACCOUNTS.get(0);
        Money total = Money.of(base, Money.INR);
        Money skew = paise(skewInPaise);
        // The explanation overshoots by `excess` in whichever direction the difference points; for a
        // nil difference it points positive, which is the "explains a difference that is not there"
        // case and is equally a finding.
        long direction = skewInPaise < 0 ? -1L : 1L;
        Money explanation = paise(skewInPaise + direction * excessInPaise);

        InvariantResult result = GlReconciliation.of(PERIOD, BOOK,
            List.of(SubLedgerBalance.of("C1", account, total)),
            List.of(GlControlAccountBalance.of(account, total.minus(skew), SOURCE)),
            List.of(ExplainedDifference.timing(account, explanation, "over-claimed"))).tiesToGl();

        // Worked out on paper: unexplained = difference − explained = skew − (skew ± excess)
        //                                  = ∓ excess, whose absolute value is the excess.
        BigDecimal expected = BigDecimal.valueOf(excessInPaise).movePointLeft(2);
        assertThat(result.satisfied())
            .as("difference %s over-explained by %s; detail: %s",
                skew.atPresentationScale(), expected, result.detail())
            .isFalse();
        assertThat(result.deviation()).isEqualByComparingTo(expected);
    }

    /**
     * Two accounts broken by equal and opposite amounts give a deviation of twice the break, never
     * nil.
     *
     * <p>The shape of one posting mapped to the wrong control account, which is the common break and
     * not an exotic one. A signed deviation would report it as a tie, and the period would close on
     * a book where two control accounts are both wrong.
     */
    @Property(tries = 500)
    void oppositeBreaksNeverNet(
        @ForAll @IntRange(min = 1, max = 90000) int breakInPaise,
        @ForAll @BigRange(min = "0.00", max = "9000000") @Scale(2) BigDecimal base) {
        Money total = Money.of(base, Money.INR);
        Money amount = paise(breakInPaise);
        String left = ACCOUNTS.get(0);
        String right = ACCOUNTS.get(1);

        InvariantResult result = GlReconciliation.of(PERIOD, BOOK,
            List.of(
                SubLedgerBalance.of("C1", left, total),
                SubLedgerBalance.of("C1", right, total)),
            List.of(
                GlControlAccountBalance.of(left, total.minus(amount), SOURCE),
                GlControlAccountBalance.of(right, total.plus(amount), SOURCE))).tiesToGl();

        assertThat(result.satisfied()).isFalse();
        assertThat(result.deviation())
            .as("2 × %s, because the deviation is absolute", amount.atPresentationScale())
            .isEqualByComparingTo(amount.amount().add(amount.amount()));
        assertThat(result.detail()).contains("signed total 0.00");
    }

    /**
     * SL-1 depends on each account's sub-ledger total, not on how that total is spread over
     * contracts.
     *
     * <p>The claim that makes SL-1 a control-account reconciliation rather than a per-contract one:
     * FR-803 compares the <em>sum</em> of contract balances against the control account, so the same
     * total split over one contract or twenty-five must give the same answer. The failure it rules
     * out is a per-contract rounding or a per-contract residual leaking into the aggregate, which a
     * two-contract fixture would never show.
     */
    @Property(tries = 500)
    void slOneIsIndependentOfHowTheTotalIsPartitioned(
        @ForAll @IntRange(min = 1, max = 25) int parts,
        @ForAll @IntRange(min = -70000, max = 70000) int skewInPaise,
        @ForAll @BigRange(min = "0.01", max = "9000000") @Scale(2) BigDecimal total) {
        String account = ACCOUNTS.get(0);
        Money whole = Money.of(total, Money.INR);
        Money skew = paise(skewInPaise);
        GlControlAccountBalance reported =
            GlControlAccountBalance.of(account, whole.minus(skew), SOURCE);

        // Equal pieces plus the remainder on the last, so the pieces sum exactly to the total with
        // no slack — the same split JournalBatchPropertiesTest uses for the same reason.
        BigDecimal each = total.divide(BigDecimal.valueOf(parts), 2, RoundingMode.DOWN);
        BigDecimal remainder = total.subtract(each.multiply(BigDecimal.valueOf(parts)));
        List<SubLedgerBalance> split = new ArrayList<>();
        for (int part = 0; part < parts; part++) {
            BigDecimal piece = part == parts - 1 ? each.add(remainder) : each;
            split.add(SubLedgerBalance.of("C" + part, account, Money.of(piece, Money.INR)));
        }

        InvariantResult many =
            GlReconciliation.of(PERIOD, BOOK, split, List.of(reported)).tiesToGl();
        InvariantResult one = GlReconciliation.of(PERIOD, BOOK,
            List.of(SubLedgerBalance.of("C0", account, whole)), List.of(reported)).tiesToGl();

        assertThat(many.satisfied())
            .as("%s split over %d contracts against a skew of %s", total, parts,
                skew.atPresentationScale())
            .isEqualTo(one.satisfied());
        assertThat(many.deviation()).isEqualByComparingTo(one.deviation());
        // And it is the skew, by hand: the difference is the total less (total − skew) = skew.
        assertThat(many.deviation())
            .isEqualByComparingTo(BigDecimal.valueOf(skewInPaise).movePointLeft(2).abs());
    }
}
