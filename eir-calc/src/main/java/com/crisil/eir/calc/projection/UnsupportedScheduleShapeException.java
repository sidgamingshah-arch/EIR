package com.crisil.eir.calc.projection;

import java.util.List;

/**
 * Raised where no registered projector supports a contract's terms.
 *
 * <p>Named rather than generic, and it names the shape and the registered
 * projectors, because the alternative failure mode is far worse: a registry that
 * falls back to a projector that "nearly" fits produces a schedule the lender
 * never billed and a rate solved over it. That is a plausible number with no
 * trace, which is the failure class this engine is built to refuse. A missing
 * projector is a configuration gap, and the contract routes to the exception
 * queue.
 */
public class UnsupportedScheduleShapeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ScheduleShape shape;

    public UnsupportedScheduleShapeException(ContractTerms terms, List<String> registered) {
        super("no projector supports schedule shape " + terms.shape()
            + " (rate type " + terms.rateType()
            + ", " + terms.termPeriods() + " periods"
            + ", moratorium " + terms.moratoriumPeriods() + " periods"
            + "); registered projectors: " + registered);
        this.shape = terms.shape();
    }

    public ScheduleShape shape() {
        return shape;
    }
}
