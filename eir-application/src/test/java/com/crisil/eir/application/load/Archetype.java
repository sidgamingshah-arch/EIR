package com.crisil.eir.application.load;

/**
 * The five behaviours a synthetic close is built out of, and the share of the book each carries.
 *
 * <h2>Why the mix is the measurement and N is not</h2>
 *
 * <p>05 § 3.2 is explicit that the cost of a close is decided by the event mix rather than by the
 * population: "<b>inside the event branch only</b>. A fixed-rate contract with no events never
 * re-solves. The steady-state run is overwhelmingly roll-forward arithmetic, which is what makes
 * the 10M-contract target reachable." A synthetic book of all-events would therefore measure a
 * cost the engine was never sized for, and a book of no-events would flatter the result by never
 * reaching the solver at all. Both would produce a number, and neither number would be about the
 * 4-hour gate.
 *
 * <p>So the shares below are stated, sourced and held to. They are integers in <b>permille</b> and
 * must sum to 1000, which {@link SyntheticBook} checks at class initialisation — a mix that
 * silently does not add up would make every extrapolation in {@code tools/load-harness/RESULTS.md}
 * wrong in a direction nobody could recover.
 *
 * <h2>Where each share comes from</h2>
 *
 * <ul>
 *   <li><b>{@link #PERFORMING_PERIODIC}, 600‰</b> and <b>{@link #PERFORMING_ACTUAL}, 320‰</b> —
 *       together 92% of the book: performing contracts with no event, which is the roll-forward
 *       arithmetic the target is sized on. They are split because the two time conventions have
 *       materially different cost and 03 § 3.10 makes actual dating "the default and the
 *       fallback": under {@code TimeConvention.PeriodicIndex} the accrual exponent is exactly one
 *       and the accretion is a single {@code BigDecimal} multiply, while under
 *       {@code TimeConvention.ActualDate} it is a day-counted fraction (31/365 here) and the
 *       accretion is a fractional power evaluated to 28 significant digits. Measuring only the
 *       periodic case would understate the steady state by whatever that power costs, which is the
 *       single largest per-contract cost this harness found. The 65/35 split within the performing
 *       block is a judgement, not a citation, and the sensitivity sweep in {@code RESULTS.md}
 *       reports the cost of each convention separately so the reader can re-weight it.</li>
 *   <li><b>{@link #STAGE_3_SUPPRESSED}, 40‰</b> — 4%, the order of the gross NPA ratio of Indian
 *       scheduled commercial banks in the years around this engine's ACPIR 2026 go-live. These are
 *       the contracts that reach {@code Stage3Decomposition}, the {@code SuspenseLedger} and
 *       S3-1's four-way reconciliation, so they cost strictly more than a performing contract
 *       while performing no solve. Reference case 5's figures are used verbatim.</li>
 *   <li><b>{@link #EVENT_CATCH_UP}, 30‰</b> — 3% of the book carries a re-estimation event that
 *       03 § 6.1's baseline routes to a B5.4.6 catch-up: a pre-determined step-up, an ESG ratchet,
 *       a revised behavioural estimate. It consults the routing table and discounts the twelve
 *       remaining instalments through {@code CatchUpCalculator}, and it performs <b>no solve</b> —
 *       which is exactly why it is here as a separate archetype from the reset. A harness that
 *       lumped all events together could not tell the reader which half of the event branch cost
 *       the money.</li>
 *   <li><b>{@link #EVENT_RESET}, 10‰</b> — 1% of the book routes to a B5.4.5 reset and re-solves
 *       through {@code BracketedNewtonSolver}. This is the share the whole timing question turns
 *       on and it is the least defensible single number in this file, because it depends on the
 *       floating share of the book and on the reset frequency of the benchmark: a book that is 40%
 *       repo-linked with quarterly resets would put an order of 13% of contracts through a reset
 *       in some months and none in others. 1% is chosen as a plausible steady-state month, and
 *       because a single point estimate would be worthless the harness takes the reset share as an
 *       argument and {@code RESULTS.md} reports the measured marginal cost of one solve. The
 *       extrapolation to 10M is then a function of the reset share rather than a claim about
 *       it.</li>
 * </ul>
 *
 * <p><b>Amounts are reference case 1's and are deliberately not varied.</b> Every figure in
 * {@link SyntheticBook} is a published one — 528,407.32 opening, 47,073.47 EMI, 5,506.79 gross EIR
 * interest, 486,840.64 closing at month 13, and reference case 5's 211,362.93 allowance and
 * 5,298.16 billed — so the harness's output is recognisable and every per-contract invariant is
 * green. Varying the digits would look more realistic and would measure something else: SL-2, ST-2
 * and S3-1 are exact ties between hand-derived figures, so invented amounts breach them, and a run
 * whose contracts are red spends its time in breach-message construction rather than in
 * arithmetic. Cost per contract is in any case insensitive to the digits at these scales — a
 * {@code BigDecimal} of 528,407.32 and one of 3,904,112.55 are both a single {@code long}
 * intermediate. Variety here is variety of <em>path</em>, which is what the cost depends on.
 */
public enum Archetype {

    /** Performing, no event, uniform monthly periods: accrual exponent exactly one. */
    PERFORMING_PERIODIC(600),

    /** Performing, no event, ACT/365F dating: accrual exponent 31/365, a fractional power. */
    PERFORMING_ACTUAL(320),

    /** Stage 3 with recognition suppressed; decomposition, suspense ledger and S3-1. */
    STAGE_3_SUPPRESSED(40),

    /** A re-estimation event routed to a B5.4.6 catch-up. Restates the balance; never solves. */
    EVENT_CATCH_UP(30),

    /** A benchmark movement on a floating instrument, routed to a B5.4.5 reset. Solves once. */
    EVENT_RESET(10);

    private final int defaultPermille;

    Archetype(int defaultPermille) {
        this.defaultPermille = defaultPermille;
    }

    /** This archetype's share of the book, in parts per thousand, under the baseline mix. */
    public int defaultPermille() {
        return defaultPermille;
    }

    /** Whether a contract of this archetype reaches the solver — true of the reset alone. */
    public boolean solves() {
        return this == EVENT_RESET;
    }

    /** Whether a contract of this archetype consults the routing table at all. */
    public boolean hasEvent() {
        return this == EVENT_CATCH_UP || this == EVENT_RESET;
    }
}
