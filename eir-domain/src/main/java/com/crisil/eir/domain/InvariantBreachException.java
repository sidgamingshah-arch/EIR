package com.crisil.eir.domain;

/**
 * Raised when an invariant is violated.
 *
 * <p>A breach is a control exception, not a rounding nuisance: it means the
 * figures cannot be relied on. Nothing in the engine catches this to continue
 * with a substituted value.
 */
public class InvariantBreachException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient InvariantResult result;

    public InvariantBreachException(InvariantResult result) {
        super("invariant " + result.id() + " breached: " + result.detail());
        this.result = result;
    }

    public InvariantResult result() {
        return result;
    }
}
