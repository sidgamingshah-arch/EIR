package com.crisil.eir.persistence.jdbc;

import java.util.Objects;

/**
 * {@code contract_version.compounding_basis} to the two numbers the projector needs from it.
 *
 * <p>V1 constrains the column to the eight values {@code WEEKLY | FORTNIGHTLY | MONTHLY | QUARTERLY
 * | HALF_YEARLY | ANNUAL | SEASONAL | CUSTOM}. {@code ContractTerms} needs
 * {@code periodsPerYear}, and placing a business date in the contract's own schedule needs calendar
 * months per period. Neither is derivable from the other for all eight values, which is why both
 * live here and why two of the eight answer only the first.
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
            // part is that monthsInPeriod refuses to pretend the spacing is uniform.
            case "SEASONAL", "CUSTOM" -> 12;
            default -> throw unknown(compoundingBasis);
        };
    }

    /**
     * Calendar months in one compounding period.
     *
     * @throws PersistenceFailure for {@code SEASONAL} and {@code CUSTOM}, which have none
     */
    public static int monthsInPeriod(String compoundingBasis) {
        return switch (normalise(compoundingBasis)) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "HALF_YEARLY" -> 6;
            case "ANNUAL" -> 12;
            // A week and a fortnight are not whole months either, and the same argument applies:
            // 52 weekly periods do not land on 12 month boundaries, so a months-per-period answer
            // would be a rounding decision disguised as a unit conversion.
            case "WEEKLY", "FORTNIGHTLY", "SEASONAL", "CUSTOM" -> throw new PersistenceFailure(
                "compounding basis " + normalise(compoundingBasis) + " has no whole number of"
                    + " calendar months per period, so a period ordinal cannot be derived from a"
                    + " calendar date. ContractTerms.monthsInPeriod refuses the same case for the"
                    + " same reason: 'period-anniversary date arithmetic then has no defined answer"
                    + " and the schedule must be supplied externally'");
            default -> throw unknown(compoundingBasis);
        };
    }

    private static String normalise(String compoundingBasis) {
        Objects.requireNonNull(compoundingBasis, "compoundingBasis");
        return compoundingBasis.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static PersistenceFailure unknown(String compoundingBasis) {
        return new PersistenceFailure(
            "compounding basis '" + compoundingBasis + "' is not one of the eight values V1's"
                + " contract_version_compounding_basis_ck admits. A value that reached the database"
                + " outside that list means the constraint was dropped, and an unrecognised"
                + " frequency must not fall back to monthly: doing so would rescale every rate on"
                + " the contract by the ratio of the two frequencies");
    }
}
