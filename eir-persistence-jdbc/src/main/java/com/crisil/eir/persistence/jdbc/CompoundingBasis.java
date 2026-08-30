package com.crisil.eir.persistence.jdbc;

import java.time.Period;
import java.util.Objects;

/**
 * {@code contract_version.compounding_basis} to the two facts the projector needs from it.
 *
 * <p>V1 constrains the column to the eight values {@code WEEKLY | FORTNIGHTLY | MONTHLY | QUARTERLY
 * | HALF_YEARLY | ANNUAL | SEASONAL | CUSTOM}. {@code ContractTerms} needs
 * {@code periodsPerYear}, and placing a business date in the contract's own schedule needs the
 * calendar step from one due date to the next. Neither is derivable from the other for all eight
 * values, which is why both live here and why two of the eight answer only the first.
 *
 * <h2>The conversion that must never happen</h2>
 *
 * <p>{@code ContractTerms}' own constructor refuses a rate whose compounding does not match the
 * schedule, and its message is the rule: "interest must be computed from the rate for the period the
 * schedule is built on, <b>never by dividing an annual rate</b>". So this class does not convert a
 * rate. It reports how many periods a year the schedule runs, and
 * {@code contract_version.contractual_rate} is read as the rate <em>for that period</em> — see
 * {@link ContractTermsReader}, which states the storage convention it depends on.
 *
 * <h2>SEASONAL and CUSTOM have no calendar answer, and are not given one</h2>
 *
 * <p>A seasonal schedule — a Kisan Credit Card sized to a harvest — has periods of unequal calendar
 * length by construction. {@code ContractTerms.monthsInPeriod} throws for exactly this case, and its
 * reasoning is that "period-anniversary date arithmetic then has no defined answer and the schedule
 * must be supplied externally". Returning 12 or 6 here would silently place every seasonal contract
 * on a fabricated calendar, and the resulting period ordinal would be wrong by a variable amount
 * that no reconciliation attributes to a units bug.
 *
 * <p>{@link #periodsPerYear} still answers for both, because a rate has to be wrapped in
 * <em>something</em> and the count of instalments a year is a fact about the schedule even where
 * their spacing is not uniform.
 */
public final class CompoundingBasis {

    private CompoundingBasis() {
    }

    /** Instalments a year, for {@code ContractTerms.periodsPerYear} and {@code Rate}. */
    public static int periodsPerYear(String compoundingBasis) {
        return switch (normalise(compoundingBasis)) {
            case "WEEKLY" -> 52;
            case "FORTNIGHTLY" -> 26;
            case "MONTHLY" -> 12;
            case "QUARTERLY" -> 4;
            case "HALF_YEARLY" -> 2;
            case "ANNUAL" -> 1;
            // Both are irregular by construction. 12 is the count of periods a seasonal facility is
            // conventionally reported on and is the least misleading available answer; the honest
            // part is that stepOf refuses to pretend the spacing is uniform.
            case "SEASONAL", "CUSTOM" -> 12;
            default -> throw unknown(compoundingBasis);
        };
    }

    /**
     * The calendar step from one due date to the next.
     *
     * <p>A {@link Period} rather than a month count, because two of the eight frequencies are not
     * measured in months at all. Weekly and fortnightly retail collection is ordinary — a
     * daily-wage-earner microfinance loan or a KCC harvest-cycle facility — and V1's
     * {@code contract_version_compounding_basis_ck} admits both. An earlier version of this class
     * offered only a months-per-period answer and refused those two, which meant a weekly contract
     * resolved a perfectly good {@code openingState} and then aborted the whole run inside
     * {@code ContractPeriodSource}: a ten-million-contract close dying on the first weekly loan
     * rather than quarantining it under FR-905. Seven days is an exact calendar step and needs no
     * month arithmetic, so there is no reason to refuse it.
     *
     * <p>{@code SEASONAL} and {@code CUSTOM} are still refused, and that refusal is real rather than
     * a gap: a schedule sized to a harvest has periods of unequal length <em>by construction</em>, so
     * there is no step at all. {@code ContractTerms.monthsInPeriod} refuses the same case with the
     * same reasoning — "period-anniversary date arithmetic then has no defined answer and the
     * schedule must be supplied externally".
     *
     * @throws PersistenceFailure for {@code SEASONAL} and {@code CUSTOM}, which have no fixed step
     */
    public static Period stepOf(String compoundingBasis) {
        return switch (normalise(compoundingBasis)) {
            case "WEEKLY" -> Period.ofDays(7);
            case "FORTNIGHTLY" -> Period.ofDays(14);
            case "MONTHLY" -> Period.ofMonths(1);
            case "QUARTERLY" -> Period.ofMonths(3);
            case "HALF_YEARLY" -> Period.ofMonths(6);
            // Twelve months rather than one year, so that every month-based step is expressed in one
            // unit and PeriodId's arithmetic has a single case to handle.
            case "ANNUAL" -> Period.ofMonths(12);
            case "SEASONAL", "CUSTOM" -> throw new ContractDataCondition(
                "compounding basis " + normalise(compoundingBasis) + " has no fixed step from one due"
                    + " date to the next — its periods are of unequal length by construction — so a"
                    + " period ordinal cannot be derived from a calendar date. The schedule has to be"
                    + " supplied externally, which is what ContractTerms.monthsInPeriod says of the"
                    + " same case, and cashflow_schedule.source = 'LMS_AUTHORITATIVE' is how it"
                    + " arrives (FR-102)");
            default -> throw unknown(compoundingBasis);
        };
    }

    private static String normalise(String compoundingBasis) {
        Objects.requireNonNull(compoundingBasis, "compoundingBasis");
        return compoundingBasis.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static ContractDataCondition unknown(String compoundingBasis) {
        return new ContractDataCondition(
            "compounding basis '" + compoundingBasis + "' is not one of the eight values V1's"
                + " contract_version_compounding_basis_ck admits. A value that reached the database"
                + " outside that list means the constraint was dropped, and an unrecognised"
                + " frequency must not fall back to monthly: doing so would rescale every rate on"
                + " the contract by the ratio of the two frequencies");
    }
}
