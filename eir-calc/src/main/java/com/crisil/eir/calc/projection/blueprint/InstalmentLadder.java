package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * A resolved contractual schedule: what is due, when, split into principal and
 * interest, with the balance left after each rung.
 *
 * <p>The intermediate between a {@link ScheduleBlueprint} and a flow vector. It
 * exists as its own value type because four stages consume it — the optionality
 * resolver truncates it, the behavioural adjuster reshapes it, the assembler turns it
 * into flows, and the reconciliation compares against it — and threading a raw list
 * of flows through all four would lose the principal/interest split that the
 * contractual leg needs.
 *
 * @param currency        currency of every amount
 * @param rungs           the schedule, ascending by period
 * @param terminalBalance what remains after the last rung; non-zero for a balloon or
 *     a residual value, and <em>retained rather than plugged</em>
 * @param invariants      ST-3 and ST-5 results
 */
public record InstalmentLadder(
    Currency currency,
    List<Rung> rungs,
    Money terminalBalance,
    List<InvariantResult> invariants) {

    public InstalmentLadder {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(rungs, "rungs");
        Objects.requireNonNull(terminalBalance, "terminalBalance");
        Objects.requireNonNull(invariants, "invariants");
        if (rungs.isEmpty()) {
            throw new IllegalArgumentException("a ladder needs at least one rung");
        }
        rungs = List.copyOf(rungs);
        invariants = List.copyOf(invariants);
        for (int i = 1; i < rungs.size(); i++) {
            if (rungs.get(i).periodIndex() <= rungs.get(i - 1).periodIndex()) {
                throw new IllegalArgumentException(
                    "rungs must ascend by period; " + rungs.get(i).periodIndex()
                        + " does not follow " + rungs.get(i - 1).periodIndex());
            }
        }
        for (Rung rung : rungs) {
            if (!rung.total().currency().equals(currency)) {
                throw new IllegalArgumentException(
                    "rung at period " + rung.periodIndex() + " is "
                        + rung.total().currency().getCurrencyCode() + " but the ladder is "
                        + currency.getCurrencyCode());
            }
        }
    }

    /**
     * Builds a ladder and asserts the two structural invariants it can check itself.
     *
     * @param principalAdvanced the notional, for ST-3
     * @param expectedTerminal  the terminal lump the profile intends — zero for a
     *     fully-amortising ladder, the balloon or residual amount otherwise (ST-5)
     */
    public static InstalmentLadder of(
        Currency currency,
        List<Rung> rungs,
        Money principalAdvanced,
        Money expectedTerminal) {

        Objects.requireNonNull(principalAdvanced, "principalAdvanced");
        Objects.requireNonNull(expectedTerminal, "expectedTerminal");
        Money scheduledPrincipal = Money.zero(currency);
        for (Rung rung : rungs) {
            scheduledPrincipal = scheduledPrincipal.plus(rung.principal());
        }
        Money terminal = rungs.isEmpty()
            ? principalAdvanced
            : rungs.get(rungs.size() - 1).balanceAfter();

        List<InvariantResult> checks = new ArrayList<>();
        checks.add(InvariantResult.ofMoney(
            InvariantId.ST_3,
            "scheduled principal over " + rungs.size() + " rungs plus the terminal balance"
                + " equals the principal advanced",
            principalAdvanced,
            scheduledPrincipal.plus(terminal)));
        checks.add(InvariantResult.ofMoney(
            InvariantId.ST_5,
            "ladder amortises to its intended terminal amount rather than to zero",
            expectedTerminal,
            terminal));
        return new InstalmentLadder(currency, rungs, terminal, checks);
    }

    public int length() {
        return rungs.size();
    }

    public Rung rung(int periodIndex) {
        for (Rung rung : rungs) {
            if (rung.periodIndex() == periodIndex) {
                return rung;
            }
        }
        throw new IllegalArgumentException("no rung at period " + periodIndex);
    }

    /** Total interest the schedule bills. */
    public Money totalInterest() {
        Money sum = Money.zero(currency);
        for (Rung rung : rungs) {
            sum = sum.plus(rung.interest());
        }
        return sum;
    }

    /** Total cash the schedule collects, terminal lump excluded. */
    public Money totalCash() {
        Money sum = Money.zero(currency);
        for (Rung rung : rungs) {
            sum = sum.plus(rung.total());
        }
        return sum;
    }

    /** The ladder truncated at and including {@code lastPeriod}. */
    public List<Rung> through(int lastPeriod) {
        List<Rung> kept = new ArrayList<>();
        for (Rung rung : rungs) {
            if (rung.periodIndex() <= lastPeriod) {
                kept.add(rung);
            }
        }
        return List.copyOf(kept);
    }

    public boolean allSatisfied() {
        return invariants.stream().allMatch(InvariantResult::satisfied);
    }

    /**
     * One rung of the schedule.
     *
     * <p>{@code total} is carried rather than derived from principal plus interest,
     * because a billed instalment is what the borrower is actually charged and the
     * two can differ by the rounding residue. Where they differ, the residue policy
     * decides — and the difference belongs in the reconciliation, not silently
     * absorbed into a derived total.
     *
     * @param periodIndex   1-based period ordinal
     * @param dueOn         the due date, already business-day adjusted
     * @param principal     principal repaid in this period
     * @param interest      interest billed in this period
     * @param total         the instalment actually billed
     * @param balanceAfter  contractual balance outstanding after this rung
     */
    public record Rung(
        int periodIndex,
        LocalDate dueOn,
        Money principal,
        Money interest,
        Money total,
        Money balanceAfter) {

        public Rung {
            Objects.requireNonNull(dueOn, "dueOn");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(interest, "interest");
            Objects.requireNonNull(total, "total");
            Objects.requireNonNull(balanceAfter, "balanceAfter");
            if (periodIndex < 1) {
                throw new IllegalArgumentException("periodIndex is 1-based, got " + periodIndex);
            }
            if (balanceAfter.isNegative()) {
                throw new IllegalArgumentException(
                    "balance after period " + periodIndex + " is negative (" + balanceAfter
                        + "); a schedule that over-amortises has repaid principal the borrower"
                        + " never owed");
            }
        }
    }
}
