package com.crisil.eir.application.transition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which legacy cohort each contract belongs to — <b>asserted by whoever struck the cohorts, never
 * computed here</b>.
 *
 * <p><b>Why the type is named after the weakness.</b> {@code LegacyCohort.definition} carries what
 * the cohort selects as free text, matching the schema's JSONB column, and <em>nothing in this
 * engine evaluates it</em>. So {@code LegacyCohort.contractCount} is a figure the plan's author
 * supplied, and membership is a mapping the plan's author supplied, and neither is derivable from
 * anything the engine holds. A type called {@code CohortMembership} with a method called
 * {@code cohortOf} reads exactly like a computed answer, and a coverage report built on it reads
 * like measured coverage. It is not, and the whole point of naming it this way is that a caller
 * cannot pass it without noticing.
 *
 * <p><b>What follows from that, and is enforced elsewhere.</b>
 * {@link TransitionCoverage} keeps the supplied figures and the measured figures in separate fields
 * and prints {@link #BASIS} beside the supplied ones; {@link TransitionRun} blocks a clean exercise
 * where a contract in the measured population has no cohort asserted at all, because a contract
 * with no cohort has no migration method and therefore nothing says how it comes onto the EIR.
 *
 * @param cohortByContract contract id to cohort name, as asserted; the cohort name is the natural
 *                         key {@code LegacyCohort} makes unique
 */
public record AssertedCohortMembership(Map<String, String> cohortByContract) {

    /**
     * The sentence that has to travel with every figure derived from a cohort's own numbers.
     *
     * <p>A constant rather than prose repeated at each site, so that the caveat cannot be present
     * on one report and absent from the next — which is how a supplied figure becomes a measured
     * one over two release cycles.
     */
    public static final String BASIS =
        "cohort membership is asserted by the migration plan, not derived: LegacyCohort.definition"
            + " is free text that nothing in this engine evaluates, so contractCount is supplied"
            + " rather than computed and this figure is only as good as the plan's own selection";

    public AssertedCohortMembership {
        Objects.requireNonNull(cohortByContract, "cohortByContract");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : cohortByContract.entrySet()) {
            String contractId = entry.getKey();
            String cohortName = entry.getValue();
            if (contractId == null || contractId.isBlank()) {
                throw new IllegalArgumentException(
                    "an asserted cohort membership names a blank contract; a mapping nobody can"
                        + " tie to a contract cannot be checked against a population");
            }
            if (cohortName == null || cohortName.isBlank()) {
                // Refused rather than treated as "no cohort". An absent mapping and a mapping to
                // nothing are different claims: the first says nobody has placed this contract,
                // the second says somebody placed it nowhere, and the second is a defect in the
                // plan that would otherwise read as the first.
                throw new IllegalArgumentException(
                    "contract " + contractId + " is mapped to a blank cohort name; leave a contract"
                        + " out of the mapping to say no cohort has been asserted for it, which the"
                        + " exercise reports, rather than mapping it to nothing");
            }
            copy.put(contractId.strip(), cohortName.strip());
        }
        cohortByContract = Map.copyOf(copy);
    }

    /** The mapping as asserted. */
    public static AssertedCohortMembership asserted(Map<String, String> cohortByContract) {
        return new AssertedCohortMembership(cohortByContract);
    }

    /**
     * No membership asserted for any contract.
     *
     * <p>A legitimate state early in FY28 — the reference's sequencing puts cohort prioritisation in
     * that year — and one the exercise reports rather than accepts silently: every contract in the
     * population then has no migration method, which {@link TransitionRun} names as a blocking
     * reason.
     */
    public static AssertedCohortMembership none() {
        return new AssertedCohortMembership(Map.of());
    }

    /**
     * The cohort asserted for {@code contractId}, or empty where none was.
     *
     * <p><b>The lookup key is stripped, because the stored key was.</b> Found in review: the
     * constructor normalises on insert and this method used the raw argument, so a mapping supplied
     * as {@code " C-1 "} was stored under {@code "C-1"} and a lookup with {@code " C-1 "} missed
     * it. The consequence was not a missing cohort for one contract — {@code ContractSource} feeds
     * these ids straight from the population, so a feed with padded ids would have returned
     * {@code UNASSIGNED} for <em>every</em> contract and blocked the whole exercise with a reason
     * about the plan. Normalised on both sides so the two cannot disagree.
     */
    public Optional<String> cohortOf(String contractId) {
        if (contractId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cohortByContract.get(contractId.strip()));
    }

    /** How many contracts have a cohort asserted for them. */
    public int size() {
        return cohortByContract.size();
    }
}
