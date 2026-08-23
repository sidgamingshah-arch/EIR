package com.crisil.eir.domain;

/**
 * The invariants, asserted in production rather than only in tests. A breach
 * raises a control exception and blocks the period close.
 *
 * <p>Identifiers match the calculation specification section 9 and the control
 * set, so a production breach, a failing test and an audit workpaper all name the
 * same thing.
 */
public enum InvariantId {

    /** Initial gross carrying amount equals the net cash flow at inception. */
    IC_1("GCA0 = net cash flow at inception"),

    /** Terminal EIR-leg carrying amount is zero on a full-term, event-free contract. */
    TR_1("terminal EIR-leg GCA = 0"),

    /** Total EIR interest = total contractual interest + net integral fee +/- catch-ups. */
    INV_1("sum EIR interest = sum contractual interest + net fee"),

    /** EIR exceeds contractual iff the net integral fee is income. */
    INV_2("EIR vs contractual ordered by fee sign"),

    /** Total cash received = principal + total contractual interest on billed flows. */
    INV_3("cash received = principal + contractual interest"),

    /** Unamortised fee = contractual carrying amount - EIR carrying amount. */
    INV_4("unamortised fee = leg difference"),

    /** The EIR is unchanged across a catch-up restatement. */
    CU_1("EIR unchanged across catch-up"),

    /** Catch-up = PV(revised flows at original EIR) - carrying amount before. */
    CU_2("catch-up = PV(revised, original EIR) - GCA before"),

    /** Stage 3: net-basis interest + ECL discount unwind = gross-basis interest. */
    ST_2("net interest + ECL unwind = gross interest"),

    /** Stage 3: gross roll-forward, shadow unwind, suspense and recognised income reconcile. */
    S3_1("Stage 3 four-way reconciliation"),

    /** Stage 3: recognised interest income is nil. */
    S3_2("Stage 3 recognised income = 0"),

    /** No posting excluded by Direction entered any EIR stream or the carrying amount. */
    PC_1("penal charge exclusion asserted"),

    /** Pre-floor ECL retained and reported alongside post-floor. */
    PF_1("pre-floor ECL retained"),

    /** The credit-adjusted EIR is unchanged across a cure. */
    POCI_1("credit-adjusted EIR retained on cure"),

    /** No discontinued hedge without an active basis-adjustment amortisation schedule. */
    HB_1("discontinued hedge has amortisation schedule"),

    /** No hedging or swap cost present in any EIR cash flow stream. */
    HB_2("no hedging cost in EIR stream"),

    /** Sub-ledger contract balances sum to the GL control account. */
    SL_1("sub-ledger ties to GL"),

    /** Journal debits equal credits, per run and per contract. */
    SL_2("journals balance"),

    /** A re-run of a closed period reproduces published figures bit-identically. */
    DT_1("deterministic replay"),

    /** Every Tier 3 population has a current equivalence test on file. */
    TG_1("Tier 3 equivalence test in date");

    private final String statement;

    InvariantId(String statement) {
        this.statement = statement;
    }

    public String statement() {
        return statement;
    }
}
