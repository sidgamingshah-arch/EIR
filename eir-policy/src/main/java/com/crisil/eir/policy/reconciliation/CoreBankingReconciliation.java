package com.crisil.eir.policy.reconciliation;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The contractual interest leg reconciled to the core banking system for one period, as one RC-1
 * result (FR-804, control C-14, 07 § 4.3 gate 4).
 *
 * <p><b>Why this control is worth more than it looks.</b> 07 § 4.1 makes the argument for C-15 —
 * the cheapest available external check on engine correctness, because it compares against a number
 * computed by a different team for a different purpose — and the argument is stronger here. The CBS
 * figure is not another opinion about the same measurement: ADR-0004 makes the CBS/LMS the book of
 * record for what the borrower was billed, and the engine's contractual leg is a projection of
 * exactly that. So a difference admits no interpretation. Either the engine mis-projected the
 * schedule or the feed is wrong, and both have to be found <em>before</em> the EIR leg built on top
 * of the contractual leg is believed — which is why this is a close gate and not a report. Every
 * figure the engine exists to publish sits on top of the leg this reconciles: the fee amortisation
 * is the difference between the interest legs (INV-1), the unamortised balance is the difference
 * between the carrying amounts (INV-4), and the Stage 3 suspense ledger carries contractual
 * interest billed and not recognised. A wrong contractual leg does not produce a wrong contractual
 * disclosure; it produces a plausible EIR result.
 *
 * <p><b>The deviation is money, and it is the total absolute residual.</b> Unlike DT-1 or TM-1,
 * where a count is the meaningful figure, a close needs to know how much the two systems disagree
 * by — the figure that lands in a control-account difference. That leaves a tension with the
 * repository's rule that a population-level deviation must never be a signed sum, because two
 * breaks in opposite directions must not net to a pass. Both are honoured: the deviation is
 * {@code Σ |unexplained|} (the same choice S3-1 makes, "the total absolute residual"), and the
 * signed figure that actually ties to a control account is published separately as
 * {@link #netUnexplainedDifference()}. A signed deviation would let an engine over-accruing on one
 * product and under-accruing on another report zero.
 *
 * <p><b>One result, per {@code InvariantResult.conjunction}'s rule.</b> Everything below produces a
 * single RC-1, because {@code conjunction} keeps only the first breach's deviation among results
 * sharing an id — so publishing one result per contract would report whichever break came first in
 * the list and silently drop the rest of the book's disagreement.
 *
 * <p><b>Presence, and where this differs from {@code MigrationTracker}.</b> That class faces the
 * same "population versus what was presented" problem and answers it by taking the population size
 * as an explicit input, because "a population that reports zero outstanding migrations because half
 * of it was never presented is exactly the failure 04 § 6 gives the discount basis its own table to
 * prevent". It has to: it has one presented set and no other evidence of coverage. A reconciliation
 * has <em>two</em> presented sets, and each is the other's evidence — so the population here is the
 * union of the two, and a contract can only be dropped from the reconciliation by being absent from
 * both, which is a condition neither source can create alone. Iterating the engine's contracts and
 * looking up the CBS would reintroduce exactly MigrationTracker's failure with the CBS side as the
 * silent casualty.
 *
 * <p>The residual gap is stated rather than papered over: a contract absent from <em>both</em>
 * sources is invisible to RC-1, and no amount of arithmetic here can see it. That is close gate 1
 * in 07 § 4.3 — every upstream feed received and version-recorded — and it is not folded in here
 * because RC-1's deviation is money and a contract nobody presented has no money to disagree by;
 * making it non-zero would mean fabricating an amount, and making the deviation a count would give
 * up the figure a close actually needs. {@link #contractsReconciled()} is published so a caller can
 * compare the union against the contract master itself.
 */
public final class CoreBankingReconciliation {

    /** How many contracts a breach detail names before it summarises. Matches MigrationTracker. */
    private static final int NAMED_IN_DETAIL = 10;

    private final int periodId;
    private final Currency currency;
    private final List<ContractReconciliation> lines;
    private final List<DifferenceExplanation> danglingExplanations;

    private CoreBankingReconciliation(
        int periodId,
        Currency currency,
        List<ContractReconciliation> lines,
        List<DifferenceExplanation> danglingExplanations) {
        this.periodId = periodId;
        this.currency = currency;
        this.lines = lines;
        this.danglingExplanations = danglingExplanations;
    }

    /**
     * Reconciles the two sides over the union of the contracts they present.
     *
     * <p>{@code currency} is an argument rather than inferred from the lines for two reasons: an
     * empty run still has to produce a well-formed result, and a line in the wrong currency should
     * be refused against a stated expectation rather than against whichever line happened to be
     * first. C-14 reconciles one control account, and a control account is denominated.
     *
     * @param periodId     the accounting period, {@code YYYYMM}
     * @param currency     the control account's currency; every line must be in it
     * @param engineLines  the contractual leg's figure per contract, from
     *                     {@link ContractualLegInterest#fromTwoLegs}
     * @param cbsLines     the CBS feed's figure per contract
     * @param explanations attributions offered, keyed to contracts by
     *                     {@link DifferenceExplanation#contractId()}; several per contract is
     *                     normal — a timing difference and a fee classification can both apply
     * @throws IllegalArgumentException where either source presents one contract twice, a line
     *                                  belongs to another period, or a line is in another currency.
     *                                  All three are caller defects that would corrupt the
     *                                  aggregate rather than facts about the book.
     */
    public static CoreBankingReconciliation over(
        int periodId,
        Currency currency,
        Collection<ContractualLegInterest> engineLines,
        Collection<CbsBilledInterest> cbsLines,
        Collection<DifferenceExplanation> explanations) {
        ContractualLegInterest.requirePeriodId(periodId);
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(engineLines, "engineLines");
        Objects.requireNonNull(cbsLines, "cbsLines");
        Objects.requireNonNull(explanations, "explanations");

        Map<String, ContractualLegInterest> engine = new LinkedHashMap<>();
        for (ContractualLegInterest line : engineLines) {
            requireSamePeriod("engine", line.contractId(), line.periodId(), periodId);
            requireSameCurrency("engine", line.contractId(), line.contractualInterest(), currency);
            ContractualLegInterest existing = engine.putIfAbsent(line.contractId(), line);
            if (existing != null) {
                // MigrationTracker's reasoning, unchanged: two answers for one contract is not a
                // position. Here it is worse than ambiguous — silently keeping one would drop the
                // other's interest from the total and understate the disagreement, and silently
                // summing them would invent a contract that accrued twice.
                throw new IllegalArgumentException(
                    "the engine presented contract " + line.contractId() + " twice for period "
                        + periodId + "; one contract has one contractual interest figure per"
                        + " period, and two is not a position");
            }
        }

        Map<String, CbsBilledInterest> cbs = new LinkedHashMap<>();
        for (CbsBilledInterest line : cbsLines) {
            requireSamePeriod("CBS", line.contractId(), line.periodId(), periodId);
            requireSameCurrency("CBS", line.contractId(), line.billedInterest(), currency);
            CbsBilledInterest existing = cbs.putIfAbsent(line.contractId(), line);
            if (existing != null) {
                throw new IllegalArgumentException(
                    "the CBS feed presented contract " + line.contractId() + " twice for period "
                        + periodId + " (" + existing.feedReference() + " and "
                        + line.feedReference() + "); a duplicate across two extracts is a feed"
                        + " defect, and picking one of them would double-count or drop interest");
            }
        }

        Map<String, List<DifferenceExplanation>> byContract = new LinkedHashMap<>();
        for (DifferenceExplanation explanation : explanations) {
            requireSameCurrency(
                "explanation", explanation.contractId(), explanation.amount(), currency);
            byContract.computeIfAbsent(explanation.contractId(), key -> new ArrayList<>())
                .add(explanation);
        }

        // The union, in contract id order so that a report and a re-run read the same way. DT-1
        // byte-compares a replayed period, which a HashMap iteration order would defeat.
        Collection<String> union = new TreeSet<>(engine.keySet());
        union.addAll(cbs.keySet());

        List<ContractReconciliation> lines = new ArrayList<>(union.size());
        for (String contractId : union) {
            lines.add(ContractReconciliation.of(
                contractId,
                engine.get(contractId),
                cbs.get(contractId),
                byContract.getOrDefault(contractId, List.of())));
        }

        // An explanation against a contract neither source presented. Reported rather than thrown:
        // it is a data condition, and the usual cause is a stale explanation carried forward from
        // last period on an account that has since run off. It cannot enter the money — there is no
        // difference for it to reduce — so it is published as its own list.
        List<DifferenceExplanation> dangling = new ArrayList<>();
        for (Map.Entry<String, List<DifferenceExplanation>> entry : byContract.entrySet()) {
            if (!union.contains(entry.getKey())) {
                dangling.addAll(entry.getValue());
            }
        }

        return new CoreBankingReconciliation(
            periodId, currency, List.copyOf(lines), List.copyOf(dangling));
    }

    private static void requireSamePeriod(
        String side, String contractId, int linePeriod, int periodId) {
        if (linePeriod != periodId) {
            // TwoLegResult.reconcile refuses to pair legs spanning different periods for the same
            // reason: a comparison across two periods produces a difference that looks plausible
            // and reconciles to nothing.
            throw new IllegalArgumentException(
                "the " + side + " line for contract " + contractId + " is for period " + linePeriod
                    + ", not " + periodId + "; C-14 compares one period against the same period");
        }
    }

    private static void requireSameCurrency(
        String side, String contractId, Money amount, Currency currency) {
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "the " + side + " line for contract " + contractId + " is "
                    + amount.currency().getCurrencyCode() + " against a "
                    + currency.getCurrencyCode() + " control account; a deviation summed across"
                    + " currencies is a figure nobody can reconcile");
        }
    }

    /**
     * Invariant RC-1: the contractual interest leg ties to the core banking system, with zero
     * unexplained difference.
     *
     * <p><b>What input makes this fail?</b> Four distinct kinds, all constructible:
     *
     * <ol>
     *   <li>A contract where the two figures differ and nothing is attributed — reference case 1
     *       period 2 accrues 9,629.27 on the contractual leg; a feed line of 9,629.30 fails with a
     *       deviation of 0.03.
     *   <li>An explanation whose amount is not the difference it claims to explain. A difference of
     *       12.88 with a signed-off timing explanation of 10.00 fails with 2.88; the same
     *       explanation offered as −12.88 fails with 25.76.
     *   <li>An explanation nobody countersigned, or one countersigned by its own preparer. The
     *       difference is unexplained in full, and the detail says which of the two.
     *   <li>A contract one source presents and the other does not. The whole of the present side's
     *       amount is unexplained.
     * </ol>
     *
     * <p>Nothing in this package's constructors prevents any of them, which is checked directly by
     * the tests: {@code ContractReconciliation} accepts unequal figures,
     * {@code DifferenceExplanation} accepts a self-approved one, and neither compares an
     * explanation's amount against the difference. The type is what makes the states
     * representable, not what forbids them — {@code JournalEntry} allows an unbalanced entry to be
     * constructed for the same reason, so that SL-2 has something to detect.
     */
    public InvariantResult tiesToCoreBanking() {
        Money totalAbsolute = totalAbsoluteUnexplainedDifference();
        List<ContractReconciliation> breaks = breaks();
        if (breaks.isEmpty()) {
            return InvariantResult.pass(InvariantId.RC_1, passDetail());
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(breaks.size() + " of " + lines.size() + " contracts do not tie for period "
            + periodId + ": total unexplained " + totalAbsolute
            + " absolute, " + netUnexplainedDifference() + " net");

        List<ContractReconciliation> presenceBreaks = breaks.stream()
            .filter(line -> line.presence().isOneSided())
            .toList();
        if (!presenceBreaks.isEmpty()) {
            long engineOnly = presenceBreaks.stream()
                .filter(line -> line.presence() == SourcePresence.ENGINE_ONLY).count();
            reasons.add(presenceBreaks.size() + " are present in one source only (" + engineOnly
                + " projected by the engine and absent from the feed, "
                + (presenceBreaks.size() - engineOnly) + " billed by the CBS and not projected);"
                + " a population or extract question, not a schedule one");
        }

        List<ContractReconciliation> misstated = misstatedExplanations();
        if (!misstated.isEmpty()) {
            reasons.add(misstated.size() + " carry a signed-off explanation whose amount is not the"
                + " difference it explains, which is a finding in its own right: "
                + names(misstated));
        }

        List<ContractReconciliation> ineffective = breaks.stream()
            .filter(line -> !line.ineffectiveExplanations().isEmpty())
            .toList();
        if (!ineffective.isEmpty()) {
            reasons.add(ineffective.size() + " carry an explanation that is not effective — "
                + ineffective.getFirst().ineffectiveExplanations().getFirst()
                    .ineffectiveBecause());
        }

        if (!danglingExplanations.isEmpty()) {
            reasons.add(danglingExplanations.size() + " explanations name a contract neither source"
                + " presented");
        }

        reasons.add("breaks: " + names(breaks));
        return InvariantResult.fail(
            InvariantId.RC_1, String.join("; ", reasons), totalAbsolute.amount());
    }

    private String passDetail() {
        StringBuilder detail = new StringBuilder();
        detail.append(lines.size()).append(" contracts tie for period ").append(periodId)
            .append(": engine contractual interest ")
            .append(totalEngineContractualInterest().atPresentationScale())
            .append(" against CBS billed interest ")
            .append(totalCbsBilledInterest().atPresentationScale());
        Money explained = totalExplainedDifference().atPresentationScale();
        if (!explained.isZero()) {
            detail.append(", of which ").append(explained)
                .append(" is explained across ").append(explainedContracts().size())
                .append(" contracts");
        }
        if (!danglingExplanations.isEmpty()) {
            // Surfaced on a pass as well. Nothing here breaches — an explanation with no
            // difference to reduce cannot move the money — but a growing list of them means
            // explanations are being carried forward rather than prepared, which is the
            // provenance of a self-approved explanation nobody re-reads.
            detail.append("; ").append(danglingExplanations.size())
                .append(" explanations name a contract neither source presented");
        }
        return detail.toString();
    }

    private static String names(List<ContractReconciliation> subject) {
        List<String> ids = subject.stream()
            .limit(NAMED_IN_DETAIL)
            .map(ContractReconciliation::contractId)
            .toList();
        return subject.size() > NAMED_IN_DETAIL
            ? "first " + NAMED_IN_DETAIL + " of " + subject.size() + " " + ids
            : ids.toString();
    }

    /**
     * {@code Σ |unexplained|} over the population — RC-1's deviation.
     *
     * <p>Absolute, so that an engine over-accruing 5,000 on one contract and under-accruing 5,000
     * on another reports 10,000 rather than nothing. The per-contract figures are already at
     * presentation scale, each reduced once, so this sum is exact in paise.
     */
    public Money totalAbsoluteUnexplainedDifference() {
        Money total = Money.zero(currency);
        for (ContractReconciliation line : lines) {
            total = total.plus(line.unexplainedDifference().abs());
        }
        return total;
    }

    /**
     * The signed net unexplained difference — the figure that ties to a control account.
     *
     * <p>Published because it is what a controller posts against and what a suspense account would
     * carry, and kept out of the deviation because it can be zero on a book with breaks in both
     * directions. Both figures belong in the close pack: the net says what the balance sheet is out
     * by, the absolute says how much work there is.
     */
    public Money netUnexplainedDifference() {
        Money total = Money.zero(currency);
        for (ContractReconciliation line : lines) {
            total = total.plus(line.unexplainedDifference());
        }
        return total;
    }

    /** The total that effective explanations account for across the population. */
    public Money totalExplainedDifference() {
        Money total = Money.zero(currency);
        for (ContractReconciliation line : lines) {
            total = total.plus(line.explainedAmount());
        }
        return total;
    }

    /** Contractual interest the engine projected across the population. */
    public Money totalEngineContractualInterest() {
        Money total = Money.zero(currency);
        for (ContractReconciliation line : lines) {
            if (line.engineContractualInterest() != null) {
                total = total.plus(line.engineContractualInterest());
            }
        }
        return total;
    }

    /** Interest the CBS billed across the population. */
    public Money totalCbsBilledInterest() {
        Money total = Money.zero(currency);
        for (ContractReconciliation line : lines) {
            if (line.cbsBilledInterest() != null) {
                total = total.plus(line.cbsBilledInterest());
            }
        }
        return total;
    }

    /** Every line, in contract id order. */
    public List<ContractReconciliation> lines() {
        return lines;
    }

    /** The line for a contract, or {@code null} where neither source presented it. */
    public ContractReconciliation lineFor(String contractId) {
        for (ContractReconciliation line : lines) {
            if (line.contractId().equals(contractId)) {
                return line;
            }
        }
        return null;
    }

    /** Lines carrying an unexplained difference. */
    public List<ContractReconciliation> breaks() {
        return lines.stream().filter(line -> !line.isTied()).toList();
    }

    /**
     * Lines present in one source only, tied or not.
     *
     * <p>Includes the ones an explanation closed. A one-sided contract whose absence has been
     * accounted for still belongs in the population report, because the diagnosis — an account
     * closed in the CBS, an extract filtered differently — is about the feed rather than the
     * contract, and a run whose one-sided count is climbing is worth looking at even while every
     * one of them is explained.
     */
    public List<ContractReconciliation> presenceBreaks() {
        return lines.stream().filter(line -> line.presence().isOneSided()).toList();
    }

    /**
     * Lines where a signed-off explanation does not add up to the difference it explains.
     *
     * <p>The check that stops an explanation being a note. See
     * {@link ContractReconciliation#hasMisstatedExplanation()}.
     */
    public List<ContractReconciliation> misstatedExplanations() {
        return lines.stream().filter(ContractReconciliation::hasMisstatedExplanation).toList();
    }

    /** Lines carrying at least one explanation that is not entitled to reduce anything. */
    public List<ContractReconciliation> linesWithIneffectiveExplanations() {
        return lines.stream()
            .filter(line -> !line.ineffectiveExplanations().isEmpty())
            .toList();
    }

    /** Lines where an effective explanation accounts for part or all of a difference. */
    public List<ContractReconciliation> explainedContracts() {
        return lines.stream()
            .filter(line -> !line.effectiveExplanations().isEmpty())
            .toList();
    }

    /**
     * Explanations naming a contract neither source presented.
     *
     * <p>Plain data, in the shape {@code MigrationTracker.outstandingAcpir50Migrations()} publishes
     * its programme figure: it cannot move the money, so making it breach would put a condition
     * with no amount into a deviation denominated in money. It is named in RC-1's detail on both a
     * pass and a fail, because the reading is what matters — explanations accumulating against
     * contracts that no longer exist is how a book of explanations stops being re-read.
     */
    public List<DifferenceExplanation> danglingExplanations() {
        return danglingExplanations;
    }

    /**
     * How many contracts the reconciliation covered — the union of the two sources.
     *
     * <p>Published so a caller can compare it against the contract master. A union smaller than the
     * master means both feeds are short of the same contracts, which RC-1 cannot see and close gate
     * 1 of 07 § 4.3 can.
     */
    public int contractsReconciled() {
        return lines.size();
    }

    public int periodId() {
        return periodId;
    }

    public Currency currency() {
        return currency;
    }

    /** A one-line reconciliation position. */
    public String describe() {
        return "period " + periodId + ": " + lines.size() + " contracts, engine "
            + totalEngineContractualInterest().atPresentationScale() + " against CBS "
            + totalCbsBilledInterest().atPresentationScale() + ", explained "
            + totalExplainedDifference().atPresentationScale() + ", unexplained "
            + totalAbsoluteUnexplainedDifference() + " absolute over " + breaks().size()
            + " contracts";
    }
}
