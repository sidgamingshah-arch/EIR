package com.crisil.eir.domain;

/**
 * ECL staging, consumed as a versioned input. This engine never computes it.
 *
 * <p>Staging changes neither the EIR nor the gross carrying amount. Under ACPIR
 * it changes only whether interest income is <em>recognised</em>: Stage 1 and 2
 * accrete on the gross basis, Stage 3 recognises nothing at all while the ECL
 * discount unwind must still be computed for the impairment roll-forward.
 */
public enum Stage {
    STAGE_1,
    STAGE_2,
    STAGE_3;

    /** True where ACPIR suppresses interest income recognition entirely. */
    public boolean suppressesIncomeRecognition() {
        return this == STAGE_3;
    }
}
