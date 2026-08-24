package com.crisil.eir.calc.projection.blueprint;

import java.util.Objects;

/**
 * A repayment holiday.
 *
 * <p>Says when <em>principal</em> repayment begins. What happens to interest during
 * the holiday is {@link InterestServicing}'s business, and the two are deliberately
 * separate — see that interface.
 *
 * @param periods    holiday length in schedule periods; zero for none
 * @param kind       what is suspended
 * @param termEffect whether the holiday extends the instrument or is absorbed
 */
public record Moratorium(int periods, MoratoriumKind kind, MoratoriumTermEffect termEffect) {

    public Moratorium {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(termEffect, "termEffect");
        if (periods < 0) {
            throw new IllegalArgumentException("periods must not be negative, got " + periods);
        }
        if (periods == 0 && kind != MoratoriumKind.NONE) {
            throw new IllegalArgumentException(
                "a moratorium of zero periods must be kind NONE, got " + kind);
        }
        if (periods > 0 && kind == MoratoriumKind.NONE) {
            throw new IllegalArgumentException(
                "kind NONE cannot span " + periods + " periods; state the kind the holiday actually is");
        }
    }

    /** No holiday. */
    public static Moratorium none() {
        return new Moratorium(0, MoratoriumKind.NONE, MoratoriumTermEffect.EXTEND_TERM);
    }

    /** Interest serviced, principal suspended — the "principal holiday". */
    public static Moratorium principalOnly(int periods) {
        return new Moratorium(periods, MoratoriumKind.PRINCIPAL_ONLY, MoratoriumTermEffect.EXTEND_TERM);
    }

    /** Nothing paid; interest compounds into the balance. Education loans, project IDC. */
    public static Moratorium fullyCapitalised(int periods) {
        return new Moratorium(
            periods, MoratoriumKind.FULL_INTEREST_CAPITALISED, MoratoriumTermEffect.EXTEND_TERM);
    }

    public boolean isPresent() {
        return periods > 0;
    }

    /** What the holiday suspends. */
    public enum MoratoriumKind {

        /** No holiday. */
        NONE,

        /** Principal suspended, interest serviced as it falls due. */
        PRINCIPAL_ONLY,

        /** Nothing paid; interest compounds into the gross carrying amount. */
        FULL_INTEREST_CAPITALISED,

        /** Nothing paid; interest accrues simple and settles as a lump. */
        FULL_INTEREST_DEFERRED_SIMPLE,

        /** A reduced instalment during the holiday. */
        PARTIAL_SERVICING
    }

    /**
     * What the holiday does to the instrument's life.
     *
     * <p>This is a contractual term with a real effect on the rate, not an
     * accounting convenience: extending the term and compressing the remaining
     * instalments produce different flow vectors and therefore different EIRs from
     * the same holiday.
     */
    public enum MoratoriumTermEffect {

        /** Maturity moves out by the holiday length. */
        EXTEND_TERM,

        /** Maturity held; the remaining instalments rise to absorb the holiday. */
        COMPRESS_REMAINING,

        /** Maturity held; the arrears go to a terminal lump. */
        BALLOON_ARREARS
    }
}
