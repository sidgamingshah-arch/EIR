package com.crisil.eir.calc.projection.blueprint;

import com.crisil.eir.domain.InvariantId;

/**
 * Raised where an instrument's optionality means it has no EIR at all (ST-12).
 *
 * <p>A conversion feature on an asset almost always fails SPPI, and an SPPI
 * failure sends the <em>entire</em> instrument to fair value through profit or
 * loss, where no effective interest rate arises. That is a cliff and not a
 * gradient: there is no reduced, approximate or provisional EIR on the far side of
 * it, so the only correct output is a refusal.
 *
 * <p>Which is why this is an exception and not a life. Returning a life — any life
 * — would put a number into a field whose existence asserts amortised cost, and
 * the number would then be indistinguishable from one belonging to an instrument
 * that had passed the gate. Refusing costs a caller one branch; returning costs a
 * misclassification that reconciles perfectly all the way to the general ledger.
 *
 * <p>The classification gate is meant to run <em>before</em> any EIR work is
 * commissioned ([03 § 11]), so reaching this exception means the gate was skipped.
 * The message says so, because the fix is upstream and not here.
 */
public class OptionalitySppiFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient OptionSchedule.OptionType offendingType;

    public OptionalitySppiFailureException(OptionSchedule.OptionType offendingType) {
        super("invariant " + InvariantId.ST_12 + " (" + InvariantId.ST_12.statement() + "): a "
            + offendingType + " option on an asset fails SPPI, which classifies the whole"
            + " instrument at fair value through profit or loss where no effective interest rate"
            + " exists. No expected life is returned, because any life returned here would be"
            + " read as evidence of amortised cost. Run the classification gate before"
            + " commissioning EIR work rather than after it.");
        this.offendingType = offendingType;
    }

    /** The option type that failed the gate. */
    public OptionSchedule.OptionType offendingType() {
        return offendingType;
    }
}
