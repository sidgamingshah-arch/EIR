package com.crisil.eir.application.transition;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.ContractSource;
import com.crisil.eir.application.port.ContractStateSource;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One transition exercise: what it values, at what date, and everything it may read (04 § 6).
 *
 * <p><b>The ports are on the request, not injected into a service</b> — {@code RunRequest}'s own
 * argument, and it applies with more force here. The FY27 exercise is re-run through the year as the
 * paragraph 19 evidence file is built, and each re-run must be able to read the world as an earlier
 * one saw it. A long-lived service holding its sources would need a second, differently configured
 * object to do that, and the two paths would drift in exactly the way DT-1 exists to detect.
 *
 * <p><b>Three ports, not eight.</b> {@code RunRequest} makes the core banking feed, the general
 * ledger and the policy registry mandatory because a month-end run cannot close without them. This
 * exercise closes nothing: it posts no journal, ties to no control account and bills no borrower, so
 * requiring those three would force every caller to supply sources this code never reads, and a
 * source nobody reads is a source nobody notices is wrong. The ACPIR 19 difference does reach the
 * ledger — as an adjustment to <em>opening</em> retained earnings, which is a transition posting
 * made once and not a run output ({@code TransitionFairValue} names the destination and offers no
 * accessor that would let it be read as a period result).
 *
 * @param runId          the exercise's identity; what every exception raised is stamped with
 *                       (04 § 2.13)
 * @param periodId       the accounting period the ECL discount basis is recorded for; the schema
 *                       keys {@code ECL_DISCOUNT_BASIS} on contract and period
 * @param transitionDate the ACPIR 19 valuation date — 1 April 2027 for the transition itself. An
 *                       argument rather than a constant, because 04 § 6 deliberately does not
 *                       constrain {@code transition_fair_value.transition_date} to that day: the
 *                       same structure serves a below-market origination on its own day 1, and a
 *                       dry run of the exercise during FY27 values at the date it is preparing for
 * @param boundary       the as-at boundary; also the date TM-1 is asserted on, via
 *                       {@code businessAsOf}
 * @param contracts      the population — ids, so that the count FR-905's accounting depends on
 *                       comes from the source and not from whatever the loader returned
 * @param contractState  the book's side of the valuation: the carrying amount brought forward, and
 *                       whether a rate is in force at all (ACPIR 21)
 * @param source         the transition facts no other port carries
 */
public record TransitionRequest(
    String runId,
    int periodId,
    LocalDate transitionDate,
    AsAtBoundary boundary,
    ContractSource contracts,
    ContractStateSource contractState,
    TransitionSource source) {

    public TransitionRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transitionDate, "transitionDate");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(contracts, "contracts");
        Objects.requireNonNull(contractState, "contractState");
        Objects.requireNonNull(source, "source");
        runId = runId.strip();
        if (runId.isEmpty()) {
            throw new IllegalArgumentException(
                "a transition exercise needs a run id; 04 § 2.13 stamps every exception with the"
                    + " run that raised it, and a three-year migration programme re-runs this"
                    + " exercise often enough that 'the last entry for this contract' is not an"
                    + " identification");
        }
        if (periodId < 190001 || periodId > 999912 || periodId % 100 < 1 || periodId % 100 > 12) {
            // The same shape RunRequest checks, and for the same reason: the period id is what
            // every ECL discount basis row is keyed on, and a malformed one files the migration
            // position in a period nobody will look in.
            throw new IllegalArgumentException(
                "period " + periodId + " is not a YYYYMM accounting period (04 § 2.13)");
        }
    }

    /** Whether this exercise is reproducing an earlier one. */
    public boolean isReplay() {
        return boundary.isReplay();
    }
}
