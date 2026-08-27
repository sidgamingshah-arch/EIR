package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Sub-ledger contract balances reconciled to the GL control accounts at close, with invariant SL-1
 * (FR-803, control C-13, 03 § 9).
 *
 * <h2>Why this is a control and not a tautology</h2>
 *
 * <p><b>Both sides are supplied by the caller and neither is derived from the other.</b> The
 * sub-ledger side is {@code period_balance} closing figures (04 § 2.8) mapped onto control accounts;
 * the GL side is what the general ledger says its control accounts stand at, extracted from the GL.
 * The engine posts to a general ledger and is not one (08's scope table), so those are two
 * independently sourced sets of numbers that can genuinely disagree — which is the entire point of a
 * control account, and the reason SL-1 is a real check where a comparison of a summary against the
 * batch it was summarised from would not be.
 *
 * <p>That distinction is worth being explicit about because the tempting implementation is right
 * there. {@link GlSummary} already aggregates the run's postings by account; feeding those totals in
 * as the "GL side" would compile, would read like a reconciliation on a close pack, and could not
 * fail on any input. It would be the fifth control this codebase has found wearing an invariant id
 * while being incapable of firing. So no factory here builds one side from the other, and
 * {@link GlControlAccountBalance} carries a mandatory {@code sourceRef} naming where its figure came
 * from.
 *
 * <p>They are not even the same quantity. A summary is the period's <em>movements</em>; this is
 * <em>balances</em> at close. A period whose movements summarise perfectly still breaks here if a
 * prior period's posting went to the wrong control account.
 *
 * <h2>"Zero unexplained difference", not zero</h2>
 *
 * <p>FR-803 and C-13 both say <em>unexplained</em>. A control account legitimately differs from its
 * sub-ledger across a cut-off, or because somebody posted a journal in the GL by hand, and a control
 * that fired on those would be red at every close and would be suppressed within a quarter — the
 * failure {@code InvariantId.TM_1} documents at length for a different requirement. So a difference
 * with a stated cause is an {@link ExplainedDifference}, and SL-1's deviation is the total
 * <b>absolute unexplained</b> amount over the account set.
 *
 * <p>Absolute rather than signed, because two accounts broken in opposite directions must not net to
 * a pass. Here that is the likely case, not the exotic one: the two halves of one mis-mapped posting
 * land on two accounts, so the signed total across the account set is nil precisely when the posting
 * went to the wrong place and nothing else is wrong.
 *
 * <h2>One result, over the whole account set</h2>
 *
 * <p>{@code InvariantResult.conjunction} keeps only the first breach's deviation among results
 * sharing an id, so publishing one result per account would report one account's residual and
 * silently drop the rest — and a reconciliation that reports one of its breaks is worse than one
 * that reports none, because it looks like it has been read. See
 * {@code InvariantId.S3_1}'s note, which generalises the same point over four legs.
 */
public final class GlReconciliation {

    private final int periodId;
    private final String bookId;
    private final Currency currency;
    private final List<AccountReconciliation> lines;
    private final int contractCount;

    private GlReconciliation(
        int periodId,
        String bookId,
        Currency currency,
        List<AccountReconciliation> lines,
        int contractCount) {
        this.periodId = periodId;
        this.bookId = bookId;
        this.currency = currency;
        this.lines = lines;
        this.contractCount = contractCount;
    }

    /**
     * Build the reconciliation for one period and one book from the three independent inputs.
     *
     * <p><b>The account set is the union, not the intersection.</b> An account the GL reports and the
     * sub-ledger has never heard of, or a sub-ledger control account with no GL counterpart, is a
     * difference equal to the whole balance — and it is the worst break there is, because it usually
     * means a chart-of-accounts mapping is missing entirely. An intersection would silently drop
     * exactly that case and report a clean tie on the accounts that happen to appear in both.
     * Explanations are part of the union too, so an explanation filed against an account nobody
     * supplied a balance for still surfaces.
     *
     * @param periodId       the accounting period, {@code YYYYMM} per 04 § 2.13
     * @param bookId         the book being reconciled. Stamped on and checked against the two
     *                       BALANCE sides. NOT on {@link ExplainedDifference}, which carries no
     *                       book at all — so an explanation raised against another book's control
     *                       account is accepted here and applied to this book's difference,
     *                       silencing a real break. That hole is narrower than it looks now that
     *                       {@link AccountReconciliation#carriesUnmatchedExplanation()} catches any
     *                       claim exceeding the difference in gross whatever book it came from;
     *                       what survives is a same-magnitude explanation belonging elsewhere.
     *                       Recorded rather than overclaimed: this line previously read "every
     *                       input must be stamped with it" and two of the three were
     * @param subLedger      contract-level closing balances, from the engine
     * @param glBalances     control account balances, from the general ledger
     * @param explanations   differences with a stated cause; may be empty
     * @throws IllegalArgumentException if an input is stamped with another book, if one contract has
     *     two balances on one account, if the GL reports one account twice, or if the inputs mix
     *     currencies. None of these is a reconciliation break; each is an input that has no single
     *     residual to report.
     */
    public static GlReconciliation of(
        int periodId,
        String bookId,
        Collection<SubLedgerBalance> subLedger,
        Collection<GlControlAccountBalance> glBalances,
        Collection<ExplainedDifference> explanations) {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(subLedger, "subLedger");
        Objects.requireNonNull(glBalances, "glBalances");
        Objects.requireNonNull(explanations, "explanations");
        String book = bookId.strip();
        if (book.isEmpty()) {
            throw new IllegalArgumentException("bookId must not be blank on a reconciliation");
        }
        Currency currency = currencyOf(subLedger, glBalances, explanations);

        // Sub-ledger balances, grouped by control account. Duplicate (contract, book, account) is
        // refused rather than summed: the same argument MigrationTracker makes for one migration
        // state per contract — two figures for one identity is not a position, and summing them
        // would inflate the sub-ledger total and report a break of exactly the duplicate's size,
        // which is a caller defect wearing an accounting finding's clothes.
        Map<String, List<SubLedgerBalance>> byAccount = new TreeMap<>();
        Set<String> seenIdentities = new LinkedHashSet<>();
        Set<String> contracts = new LinkedHashSet<>();
        for (SubLedgerBalance balance : subLedger) {
            requireBook(book, balance.bookId(), "sub-ledger balance " + balance.identity());
            requireCurrency(currency, balance.closingBalance(), balance.identity());
            if (!seenIdentities.add(balance.identity())) {
                throw new IllegalArgumentException(
                    "two sub-ledger balances for " + balance.identity()
                        + "; 04 § 2.8 gives one row per contract per period per book, so two"
                        + " figures for one identity means a duplicated feed rather than a break");
            }
            contracts.add(balance.contractId());
            byAccount.computeIfAbsent(balance.controlAccountCode(), key -> new ArrayList<>())
                .add(balance);
        }

        Map<String, GlControlAccountBalance> gl = new LinkedHashMap<>();
        for (GlControlAccountBalance reported : glBalances) {
            requireBook(book, reported.bookId(), "GL balance for " + reported.accountCode());
            requireCurrency(currency, reported.balance(), reported.accountCode());
            GlControlAccountBalance existing = gl.putIfAbsent(reported.accountCode(), reported);
            if (existing != null) {
                throw new IllegalArgumentException(
                    "the GL reported account " + reported.accountCode() + " twice (per "
                        + existing.sourceRef() + " and " + reported.sourceRef()
                        + "); a control account has one balance, and \"whichever we read first\" is"
                        + " not a reconciliation");
            }
        }

        Map<String, List<ExplainedDifference>> explained = new TreeMap<>();
        for (ExplainedDifference explanation : explanations) {
            requireCurrency(currency, explanation.amount(), explanation.accountCode());
            explained.computeIfAbsent(explanation.accountCode(), key -> new ArrayList<>())
                .add(explanation);
        }

        // The union. Sorted so a report reads in account-code order and two runs over the same
        // inputs produce the same document, which FR-903's bit-identical replay needs.
        SortedSet<String> accounts = new TreeSet<>();
        accounts.addAll(byAccount.keySet());
        accounts.addAll(gl.keySet());
        accounts.addAll(explained.keySet());

        List<AccountReconciliation> lines = new ArrayList<>(accounts.size());
        for (String account : accounts) {
            List<SubLedgerBalance> balances =
                byAccount.getOrDefault(account, List.of());
            Money subLedgerTotal = Money.zero(currency);
            for (SubLedgerBalance balance : balances) {
                subLedgerTotal = subLedgerTotal.plus(balance.closingBalance());
            }
            GlControlAccountBalance reported = gl.get(account);
            List<ExplainedDifference> forAccount = explained.getOrDefault(account, List.of());
            Money explainedTotal = Money.zero(currency);
            for (ExplainedDifference explanation : forAccount) {
                explainedTotal = explainedTotal.plus(explanation.amount());
            }
            lines.add(new AccountReconciliation(
                account,
                balances.size(),
                subLedgerTotal,
                reported == null ? Money.zero(currency) : reported.balance(),
                reported != null,
                explainedTotal,
                forAccount));
        }
        return new GlReconciliation(
            periodId, book, currency, List.copyOf(lines), contracts.size());
    }

    /** Convenience for a reconciliation with nothing to explain. */
    public static GlReconciliation of(
        int periodId,
        String bookId,
        Collection<SubLedgerBalance> subLedger,
        Collection<GlControlAccountBalance> glBalances) {
        return of(periodId, bookId, subLedger, glBalances, List.of());
    }

    /**
     * Invariant SL-1: the sub-ledger ties to the GL control accounts with zero unexplained
     * difference.
     *
     * <p><b>What input makes this fail?</b> Any account where the GL's reported balance differs from
     * the sub-ledger total by an amount the filed explanations do not cover, in the direction they
     * claim. Concretely, and this is the fixture in {@code GlReconciliationTest}: three contracts
     * carrying unamortised fee balances of 12,500.00, 31,250.00 and 9,375.00 sum to 53,125.00, the
     * GL reports 53,000.00 on {@code 2301-UNAMORTISED-FEE}, no explanation is filed, and SL-1 fails
     * with a deviation of 125.00. Both sides are inputs and neither is computed from the other, so
     * the disagreement is representable — there is no type-level reason the two figures should
     * agree, and no constructor here asserts that they do.
     *
     * <p>It also fails on: an account the GL reports that the sub-ledger has no balances for (the
     * union, not the intersection — the missing mapping is the break); an explanation larger than the
     * difference it claims to explain, or pointing the wrong way, which leaves a residual in the
     * opposite direction; and two accounts broken in opposite directions, which the absolute
     * deviation refuses to net to a pass.
     *
     * <p>It passes on an empty reconciliation, and that is correct rather than convenient: a book
     * with no balances and no control accounts has nothing that fails to tie. It is also not a
     * loophole, because the population is the caller's assertion about what it reconciled, and
     * FR-901's close gate is what requires the population to be the period's.
     *
     * <p>Deviation is the sum of the per-account absolute unexplained residuals, each reduced to
     * presentation scale once — {@code InvariantResult.ofMoney} sets out why the residual is reduced
     * rather than two rounded operands differenced, with the measured case that made the rule.
     */
    public InvariantResult tiesToGl() {
        List<String> breaks = new ArrayList<>();
        BigDecimal absolute = BigDecimal.ZERO;
        BigDecimal signed = BigDecimal.ZERO;
        for (AccountReconciliation line : lines) {
            Money unexplained = line.unexplained().atPresentationScale();
            signed = signed.add(unexplained.amount());
            if (!unexplained.isZero()) {
                absolute = absolute.add(unexplained.amount().abs());
                breaks.add(line.accountCode() + " unexplained "
                    + unexplained
                    + " (sub-ledger " + line.subLedgerTotal().atPresentationScale()
                    + " against GL "
                    + (line.glBalanceSupplied()
                        ? line.glBalance().atPresentationScale().toString()
                        : "no balance supplied")
                    + (line.explanations().isEmpty()
                        ? ", nothing explained"
                        : ", " + line.explanations().size() + " explanations totalling "
                            + line.explained().atPresentationScale())
                    + ")");
            }
        }

        if (breaks.isEmpty()) {
            return InvariantResult.pass(InvariantId.SL_1,
                lines.size() + " control accounts over " + contractCount + " contracts tie in book "
                    + bookId + " period " + periodId
                    + (explainedTotal().isZero()
                        ? " with no difference to explain"
                        : ", after " + explanationCount() + " explained differences totalling "
                            + explainedTotal().atPresentationScale() + " (of which "
                            + selfReversingTotal().atPresentationScale() + " self-reversing)"));
        }
        return InvariantResult.fail(InvariantId.SL_1,
            breaks.size() + " of " + lines.size() + " control accounts in book " + bookId
                + " period " + periodId + " carry an unexplained difference (signed total "
                + signed.toPlainString() + ", absolute " + absolute.toPlainString() + "): "
                + (breaks.size() > 20 ? breaks.subList(0, 20) + " …" : breaks),
            absolute);
    }

    /** One line per control account in the union, in code order. */
    public List<AccountReconciliation> lines() {
        return lines;
    }

    /** The line for one account, if it is in the reconciliation's account set. */
    public Optional<AccountReconciliation> lineFor(String accountCode) {
        return lines.stream()
            .filter(line -> line.accountCode().equals(accountCode))
            .findFirst();
    }

    /**
     * SL-1's deviation as a {@link Money}: the total absolute unexplained residual.
     *
     * <p>The same figure {@link #tiesToGl()} publishes, typed. Absolute for the reason given on the
     * class: opposite breaks must not net.
     */
    public Money unexplainedTotal() {
        Money total = Money.zero(currency);
        for (AccountReconciliation line : lines) {
            total = total.plus(line.unexplained().atPresentationScale().abs());
        }
        return total;
    }

    /** The signed total of every explanation filed, across all accounts. */
    public Money explainedTotal() {
        Money total = Money.zero(currency);
        for (AccountReconciliation line : lines) {
            total = total.plus(line.explained());
        }
        return total;
    }

    /**
     * The signed total of the explanations whose cause is expected to reverse next period.
     *
     * <p>Plain data, and the figure a close pack wants next to the pass: a reconciliation that ties
     * only because of 4,820.55 of timing differences ties differently from one that ties on its own,
     * and the next period is where the difference shows. Not asserted here, because asserting it
     * needs two periods to compare and that belongs to the close workflow (FR-901).
     */
    public Money selfReversingTotal() {
        Money total = Money.zero(currency);
        for (AccountReconciliation line : lines) {
            for (ExplainedDifference explanation : line.explanations()) {
                if (explanation.selfReversing()) {
                    total = total.plus(explanation.amount());
                }
            }
        }
        return total;
    }

    /**
     * Explanations filed against accounts whose two books already agreed.
     *
     * <p>Reported rather than folded into SL-1's deviation. Where such explanations do not cancel
     * they already breach SL-1 — a +250.00 explanation on a nil difference leaves −250.00
     * unexplained — so this exists for the one case the subtraction cannot see: two of them on one
     * account that exactly offset. Nothing about that account's balance is unaccounted for, so it is
     * a defect in the explanation register rather than a reconciliation break, and folding it in
     * would stop SL-1's deviation meaning "money the two books disagree by".
     */
    public List<ExplainedDifference> unmatchedExplanations() {
        List<ExplainedDifference> unmatched = new ArrayList<>();
        for (AccountReconciliation line : lines) {
            if (line.carriesUnmatchedExplanation()) {
                unmatched.addAll(line.explanations());
            }
        }
        return List.copyOf(unmatched);
    }

    /** How many explanations were filed in total. */
    public int explanationCount() {
        int count = 0;
        for (AccountReconciliation line : lines) {
            count += line.explanations().size();
        }
        return count;
    }

    /** The accounts that do not tie, in code order. */
    public List<AccountReconciliation> breaks() {
        return lines.stream().filter(line -> !line.ties()).toList();
    }

    /** Every control account in the union, in code order. */
    public SortedSet<String> accountCodes() {
        SortedSet<String> codes = new TreeSet<>();
        for (AccountReconciliation line : lines) {
            codes.add(line.accountCode());
        }
        return Collections.unmodifiableSortedSet(codes);
    }

    /** How many distinct contracts the sub-ledger side covers. */
    public int contractCount() {
        return contractCount;
    }

    /** The period, {@code YYYYMM}. */
    public int periodId() {
        return periodId;
    }

    /** The book being reconciled. */
    public String bookId() {
        return bookId;
    }

    /** The one currency both sides are in. */
    public Currency currency() {
        return currency;
    }

    /** A one-line reconciliation position. */
    public String describe() {
        return "book " + bookId + " period " + periodId + ": " + lines.size()
            + " control accounts over " + contractCount + " contracts, "
            + breaks().size() + " unexplained, total unexplained "
            + unexplainedTotal().atPresentationScale();
    }

    /**
     * The one currency across all three inputs.
     *
     * <p>Refused rather than converted, and refused rather than reported as a breach: a deviation
     * measured across two currencies is a figure nobody can reconcile, the argument
     * {@code InvariantResult.ofMoney} makes for letting a currency mismatch throw instead of
     * failing. INR for a wholly empty reconciliation, matching {@code JournalBatch.runResidual()}.
     */
    private static Currency currencyOf(
        Collection<SubLedgerBalance> subLedger,
        Collection<GlControlAccountBalance> glBalances,
        Collection<ExplainedDifference> explanations) {
        for (SubLedgerBalance balance : subLedger) {
            return balance.closingBalance().currency();
        }
        for (GlControlAccountBalance reported : glBalances) {
            return reported.balance().currency();
        }
        for (ExplainedDifference explanation : explanations) {
            return explanation.amount().currency();
        }
        return Money.INR;
    }

    private static void requireCurrency(Currency expected, Money amount, String what) {
        if (!amount.currency().equals(expected)) {
            throw new IllegalArgumentException(
                "reconciliation is in " + expected.getCurrencyCode() + " but " + what + " is in "
                    + amount.currency().getCurrencyCode() + "; a residual across two currencies is"
                    + " not a number, so this is refused rather than reported as a break");
        }
    }

    private static void requireBook(String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                what + " is stamped book " + actual + " in a reconciliation of book " + expected
                    + "; 04 § 2.8's grain includes the book, and mixing two books sums balances"
                    + " into a control account that carries only one of them");
        }
    }
}
