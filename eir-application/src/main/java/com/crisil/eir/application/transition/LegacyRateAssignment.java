package com.crisil.eir.application.transition;

import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.transition.DeemedEirDerivation;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationMethod;
import java.util.Objects;
import java.util.Optional;

/**
 * One legacy contract's rate, and where it came from: the plan's method applied to the contract
 * (FR-908, FR-909).
 *
 * <p><b>This derives no rate.</b> A reconstructed rate is the output of a solve that
 * {@code InitialRecognition} performs and the contract's state carries; a deemed rate is the figure
 * a {@code DeemedEirDerivation} was prepared to state. Both already exist by the time this exercise
 * reads them, and solving or deriving again here would produce a second EIR for one contract —
 * differing by whatever the two workings disagreed about, with nothing to say which is the one the
 * ledger used. So this type <em>selects</em> and records the selection.
 *
 * <p><b>The cohort is asserted, never computed.</b> {@link #assertedCohortName()} is the name
 * {@link AssertedCohortMembership} was given, and {@link AssertedCohortMembership#BASIS} says what
 * that is worth: {@code LegacyCohort.definition} is free text nothing evaluates. A reader of this
 * record must not conclude that the engine placed the contract in the cohort.
 *
 * @param contractId          the contract
 * @param assertedCohortName  the cohort somebody placed it in, or null where none did
 * @param method              the cohort's method, or null where no cohort claims the contract
 * @param basis               where the rate actually comes from, given the method and the state
 * @param rate                the rate in force, or null where none exists yet
 * @param derivation          the derivation behind a deemed rate, or null
 */
public record LegacyRateAssignment(
    String contractId,
    String assertedCohortName,
    MigrationMethod method,
    LegacyRateBasis basis,
    Rate rate,
    DeemedEirDerivation derivation) {

    public LegacyRateAssignment {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(basis, "basis");
        assertedCohortName = blankToNull(assertedCohortName);
        if ((basis == LegacyRateBasis.UNASSIGNED) != (method == null)) {
            // A method with no cohort, or a cohort's method with the unassigned basis, is a wiring
            // defect rather than a fact about the contract: the method comes from the cohort and
            // nowhere else, so the two cannot disagree about whether one was found.
            throw new IllegalArgumentException(
                "contract " + contractId + " has basis " + basis + " and method " + method
                    + "; the method comes from the asserted cohort, so UNASSIGNED and a method are"
                    + " the two halves of one answer and cannot be reported apart");
        }
        if (basis == LegacyRateBasis.DEEMED && (derivation == null || !derivation.isApproved())) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is measured on a deemed EIR with "
                    + (derivation == null ? "no derivation recorded" : "an unapproved derivation")
                    + "; DEEMED means an approved derivation was found, and a deemed rate nobody"
                    + " signed is DEEMED_UNBACKED — the distinction is exactly what DE-1 counts");
        }
        if (basis == LegacyRateBasis.RECONSTRUCTED && rate == null) {
            throw new IllegalArgumentException(
                "contract " + contractId + " is reported as reconstructed with no rate; a"
                    + " reconstruction that produced no rate has not been performed, and reporting"
                    + " it as done is how a migration comes to look complete");
        }
        if (rate != null && (basis == LegacyRateBasis.RECONSTRUCTION_OUTSTANDING
            || basis == LegacyRateBasis.DEEMED_UNBACKED)) {
            // Both bases mean the same thing about the rate: nothing authorises one. A contract
            // queued for reconstruction has not been solved, and an unsigned derivation is a
            // proposal — its figure stays visible on the derivation itself, where a reader sees it
            // is unapproved, rather than in a field named `rate` that reads as the rate in force.
            throw new IllegalArgumentException(
                "contract " + contractId + " has basis " + basis + " and carries a rate of "
                    + rate.periodic().toPlainString() + "; nothing has authorised a rate for it, so"
                    + " a rate in this field would report income being recognised on one");
        }
    }

    /**
     * The assignment for a contract <b>nobody has placed</b>: no cohort was asserted for it.
     *
     * <p>Constructed rather than skipped, and that is the point of it existing: a contract left out
     * of the assignment list would be absent from every count of what remains to migrate, and 04 § 6
     * gives the discount basis its own table precisely because a population reporting complete
     * coverage over the contracts it happened to see is the failure that matters here.
     */
    public static LegacyRateAssignment unplaced(String contractId, Rate rateInForce) {
        return new LegacyRateAssignment(contractId, null, null, LegacyRateBasis.UNASSIGNED,
            rateInForce, null);
    }

    /**
     * The assignment for a contract placed into a cohort <b>the plan does not define</b>.
     *
     * <p><b>Why this is not {@link #unplaced}.</b> Both end at {@link LegacyRateBasis#UNASSIGNED} —
     * neither contract has a migration method — and they are two different data conditions with two
     * different remedies: place the contract, or fix the plan. Collapsing them was a real defect
     * found in review: this branch discarded the asserted name, so a contract somebody <em>had</em>
     * placed, into a cohort the plan was missing, reported as "in no asserted cohort" and sent
     * whoever read it to the wrong system. {@link AssertedCohortMembership} states the principle it
     * violated — "an absent mapping and a mapping to nothing are different claims" — so the name is
     * kept and {@link #namesACohortThePlanDoesNotDefine()} distinguishes the two.
     */
    public static LegacyRateAssignment unknownCohort(
        String contractId, String assertedCohortName, Rate rateInForce) {
        return new LegacyRateAssignment(contractId,
            Objects.requireNonNull(assertedCohortName, "assertedCohortName"), null,
            LegacyRateBasis.UNASSIGNED, rateInForce, null);
    }

    /**
     * The assignment implied by a cohort's method, the plan's derivations and the rate in force.
     *
     * <p>The four outcomes and the input that produces each:
     *
     * <ul>
     *   <li>{@code FULL_RECONSTRUCTION} with a rate in force — {@link LegacyRateBasis#RECONSTRUCTED}
     *       — the work is done and the rate is the contract's own;
     *   <li>{@code FULL_RECONSTRUCTION} with no rate —
     *       {@link LegacyRateBasis#RECONSTRUCTION_OUTSTANDING} — the ordinary 2028 state, reported
     *       and not treated as a breach;
     *   <li>{@code DEEMED_EIR} with an approved derivation — {@link LegacyRateBasis#DEEMED} — the
     *       derivation's rate is used, <em>not</em> whatever rate the contract's state happens to
     *       carry, because the cohort's decision is to measure it on the deemed rate;
     *   <li>{@code DEEMED_EIR} with no derivation or an unsigned one —
     *       {@link LegacyRateBasis#DEEMED_UNBACKED} — the DE-1 population, counted once by the
     *       plan, and reported here with <em>no</em> rate: an unsigned derivation's figure stays on
     *       the derivation, where a reader sees it is unapproved.
     * </ul>
     *
     * <p>The approval question is asked of {@code DeemedEirDerivation.isApproved()} and the
     * derivation is found by {@code LegacyMigrationPlan.derivationFor(...)}. Neither is reimplemented
     * here: DE-1's count and this per-contract basis must not be able to disagree about which
     * cohorts are backed.
     *
     * @param rateInForce the rate the contract's state carries, or null where it has never been
     *                    solved
     */
    public static LegacyRateAssignment under(
        String contractId, LegacyCohort cohort, LegacyMigrationPlan plan, Rate rateInForce) {
        Objects.requireNonNull(cohort, "cohort");
        Objects.requireNonNull(plan, "plan");
        if (cohort.method() == MigrationMethod.FULL_RECONSTRUCTION) {
            return new LegacyRateAssignment(contractId, cohort.cohortName(), cohort.method(),
                rateInForce == null
                    ? LegacyRateBasis.RECONSTRUCTION_OUTSTANDING
                    : LegacyRateBasis.RECONSTRUCTED,
                rateInForce, null);
        }
        Optional<DeemedEirDerivation> derivation = plan.derivationFor(cohort.cohortName());
        boolean approved = derivation.isPresent() && derivation.get().isApproved();
        return new LegacyRateAssignment(contractId, cohort.cohortName(), cohort.method(),
            approved ? LegacyRateBasis.DEEMED : LegacyRateBasis.DEEMED_UNBACKED,
            approved ? derivation.get().deemedRate() : null,
            derivation.orElse(null));
    }

    /** Whether a rate exists for this contract at all. */
    public boolean hasRate() {
        return rate != null;
    }

    /** Whether migration work remains for this contract. */
    public boolean isOutstanding() {
        return basis.isOutstanding();
    }

    /**
     * Whether this contract was placed into a cohort the plan carries no definition of.
     *
     * <p>The remedy is to fix the plan, not to place the contract — which is why it is asked
     * separately from {@link #isUnplaced()}.
     */
    public boolean namesACohortThePlanDoesNotDefine() {
        return basis == LegacyRateBasis.UNASSIGNED && assertedCohortName != null;
    }

    /** Whether no cohort was asserted for this contract at all. The remedy is to place it. */
    public boolean isUnplaced() {
        return basis == LegacyRateBasis.UNASSIGNED && assertedCohortName == null;
    }

    /** A one-line audit sentence. Names the cohort as asserted, never as computed. */
    public String describe() {
        return "contract " + contractId
            + (assertedCohortName == null
                ? " is in no asserted cohort"
                : namesACohortThePlanDoesNotDefine()
                    ? " is asserted into cohort " + assertedCohortName
                        + ", which the plan does not define"
                    : " asserted into cohort " + assertedCohortName + " (" + method + ")")
            + ": " + basis
            + (rate == null ? ", no rate in force" : " at " + rate.periodic().toPlainString())
            + (derivation == null ? "" : " [" + derivation.describe() + "]");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
