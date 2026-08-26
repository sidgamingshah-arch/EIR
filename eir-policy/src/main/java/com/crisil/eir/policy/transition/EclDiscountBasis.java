package com.crisil.eir.policy.transition;

/**
 * Which rate an ECL was discounted at (04 § 6, ACPIR 50).
 *
 * <p>Two constants and a deadline between them. ACPIR 50 makes the EIR the ECL discount rate and
 * concedes the contractual rate as an <em>interim</em> factor until 31 March 2030. The concession
 * is real and worth using; what it is not is a general deferral, and the difference shows up only
 * if the basis is recorded per contract rather than assumed.
 */
public enum EclDiscountBasis {

    /**
     * The contractual rate, under ACPIR 50's interim concession.
     *
     * <p>Legitimate until 31 March 2030 and not after. A contract sitting here on 1 April 2030 is
     * in breach, and the only thing that makes that visible on the day is having recorded it all
     * along.
     */
    CONTRACTUAL_INTERIM,

    /** The EIR, as ACPIR 50 requires. Needs an EIR computation to point at. */
    EIR;

    /** Whether this basis satisfies ACPIR 50 rather than deferring it. */
    public boolean satisfiesAcpir50() {
        return this == EIR;
    }
}
