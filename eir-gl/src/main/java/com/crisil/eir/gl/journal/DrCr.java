package com.crisil.eir.gl.journal;

/**
 * Which side of a journal a line sits on (04 § 2.7, {@code journal_entry.dr_cr}).
 *
 * <p>An enum rather than a sign on the amount, and the schema agrees — it stores {@code dr_cr TEXT}
 * with a {@code CHECK (dr_cr IN ('DR','CR'))} and constrains {@code amount >= 0}. The reason is the
 * one the suspense ledger states for its movements: a credit posted as a negative debit and a debit
 * posted as a negative credit produce the same net, and only one of them is the journal that was
 * intended. Keeping the direction in a named field means an unbalanced journal is a detectable
 * condition rather than an arithmetic accident.
 */
public enum DrCr {

    /** Debit. */
    DR,

    /** Credit. */
    CR;

    /** The other side. */
    public DrCr opposite() {
        return this == DR ? CR : DR;
    }

    /** {@code +1} for a debit, {@code -1} for a credit — for summing a residual. */
    public int signum() {
        return this == DR ? 1 : -1;
    }
}
