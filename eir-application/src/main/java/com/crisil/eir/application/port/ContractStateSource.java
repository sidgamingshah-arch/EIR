package com.crisil.eir.application.port;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.Stage;
import java.util.Optional;

/**
 * One contract's state at the start of a period (05 § 3.2: "load contract, prior balance, events,
 * staging").
 *
 * <p><b>The ECL engine's version is part of the state, not metadata.</b> 04 § 2.9 makes
 * {@code ecl_engine_version} NOT NULL and says why: "re-running a period with today's ECL output
 * produces a different answer and invariant DT-1 fails; the version is what makes a Stage 3 replay
 * deterministic". A port that returned a stage and an allowance without the version it came from
 * would make every Stage 3 contract irreproducible, and the failure would look like an arithmetic
 * drift rather than a missing input.
 */
public interface ContractStateSource {

    /**
     * The contract's opening state, or empty where the boundary does not see it.
     *
     * <p>Empty rather than an exception, because a contract in the population that has no state as
     * at the boundary is a data condition the run reports per contract — FR-905 — and not a reason
     * to abandon a ten-million-contract close.
     */
    Optional<OpeningState> openingState(String contractId, AsAtBoundary boundary);

    /**
     * A contract as the run finds it at period start.
     *
     * @param terms                     the contract's terms, for projection and rolling forward
     * @param eir                       the rate in force, from the last solve; null where the
     *                                  contract has never been solved and this run must onboard it
     * @param openingGca                gross carrying amount brought forward
     * @param openingContractual        contractual-leg balance brought forward
     * @param stage                     the ECL engine's verdict for the period
     * @param allowance                 the allowance the stage carries
     * @param eclEngineVersion          which ECL model produced the pair — required for DT-1
     * @param contractualInterestBilled what the borrower was billed, from the CBS
     */
    record OpeningState(
        ContractTerms terms,
        Rate eir,
        Money openingGca,
        Money openingContractual,
        Stage stage,
        Money allowance,
        String eclEngineVersion,
        Money contractualInterestBilled) {

        public OpeningState {
            java.util.Objects.requireNonNull(terms, "terms");
            java.util.Objects.requireNonNull(openingGca, "openingGca");
            java.util.Objects.requireNonNull(stage, "stage");
            java.util.Objects.requireNonNull(allowance, "allowance");
            if (eclEngineVersion == null || eclEngineVersion.isBlank()) {
                // Refused rather than defaulted. 04 § 2.9's argument is that the version is what
                // makes a Stage 3 replay deterministic, so a blank one is not a missing label — it
                // is a contract whose stage cannot be reproduced, and the run has to know that
                // before it publishes a figure derived from it.
                throw new IllegalArgumentException(
                    "opening state carries stage " + stage + " and allowance "
                        + allowance.atPresentationScale() + " with no ECL engine version;"
                        + " re-running the period would consume a different ECL output and DT-1"
                        + " would fail with nothing to point at (04 § 2.9)");
            }
        }

        /** Whether this contract has a rate, or needs onboarding before it can roll forward. */
        public boolean hasBeenSolved() {
            return eir != null;
        }
    }
}
