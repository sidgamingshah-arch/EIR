package com.crisil.eir.calc.routing;

/**
 * Which side of the balance sheet the instrument sits on.
 *
 * <p>It matters to exactly one question, and it matters absolutely: whether the
 * 10% test <em>decides</em> substantiality or merely <em>evidences</em> it.
 *
 * <ul>
 *   <li>{@link #LIABILITY} — IFRS 9 B3.3.6 sets a bright line. Terms that differ
 *       by at least 10% on a present-value basis are substantially different, and
 *       the exchange or modification is accounted for as an extinguishment.
 *   <li>{@link #ASSET} — IFRS 9 sets <em>no</em> equivalent bright line. The
 *       IASB's February 2025 tentative direction is toward a principles-based
 *       qualitative assessment whose outcome cannot be determined by a
 *       quantitative test alone, and ACPIR 78–81 govern stage migration on
 *       restructuring while saying nothing about whether the original EIR
 *       survives. So the number is evidence and a person concludes.
 * </ul>
 *
 * <p>For a bank with a restructuring book, a DCCO-deferment book and FITL creation
 * this asymmetry is the most consequential single gap in the standard, which is
 * why it is a required parameter of {@link ModificationTest#evaluate} rather than
 * a default.
 */
public enum InstrumentSide {

    /** A financial asset. The 10% test is evidential only. */
    ASSET,

    /** A financial liability. The 10% test is authoritative under IFRS 9 B3.3.6. */
    LIABILITY;

    /** Whether the quantitative test decides the outcome on this side. */
    public boolean quantitativeTestIsAuthoritative() {
        return this == LIABILITY;
    }
}
