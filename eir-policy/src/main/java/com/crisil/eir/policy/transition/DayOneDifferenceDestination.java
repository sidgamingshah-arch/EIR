package com.crisil.eir.policy.transition;

/**
 * Where a day-1 fair value difference is taken (FR-909, reference § 4 Silence 6).
 *
 * <p><b>This enum lists the choices available, not a default.</b> ACPIR 19 and 20 require fair
 * value at initial recognition and give no guidance at all on the resulting day-1 difference — the
 * reference register carries it as Silence 6 at {@code [MED-HIGH]}. So which constant applies is a
 * Board-approved policy position, and invariant BM-1 is the assertion that one was taken. There is
 * deliberately no constant meaning "unresolved": an absent decision is modelled as an absent
 * destination, because a constant for it would sit in the enum looking like an answer.
 */
public enum DayOneDifferenceDestination {

    /**
     * Employee benefit cost — the reference's position for staff and concessional employee loans
     * (§ 5 item 11): "the day-1 shortfall is employee compensation, not a lending loss".
     *
     * <p>The distinction is the point. Booked as a lending loss it lands in impairment or interest
     * income and reads as credit performance; booked as compensation it lands in staff cost, which
     * is what it economically is. Same figure, and the two readings say different things about the
     * bank.
     */
    EMPLOYEE_BENEFIT_COST,

    /**
     * Opening retained earnings — the ACPIR 19 transition treatment.
     *
     * <p>Correct for the day-1 valuation of an <em>existing</em> book at 1 April 2027, and wrong
     * for an origination after it: a loan written below market in June 2027 is a current-period
     * event, and routing its shortfall to opening retained earnings would put a current cost
     * outside the current result entirely.
     */
    OPENING_RETAINED_EARNINGS,

    /**
     * An operating expense line other than staff cost.
     *
     * <p>The residual, for directed concessional lending where the concession is not employee
     * compensation and no more specific home has been established. Silence 6 covers this case and
     * the reference offers no position on it, so a version citing this constant is asserting a
     * choice rather than following guidance — which is exactly what BM-1 requires to be approved.
     */
    OTHER_OPERATING_EXPENSE;

    /** Whether this destination is inside the current period's result. */
    public boolean affectsCurrentPeriodResult() {
        return this != OPENING_RETAINED_EARNINGS;
    }
}
