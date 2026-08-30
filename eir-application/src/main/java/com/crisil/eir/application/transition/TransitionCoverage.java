package com.crisil.eir.application.transition;

import com.crisil.eir.policy.transition.ContractMigrationState;
import com.crisil.eir.policy.transition.LegacyCohort;
import com.crisil.eir.policy.transition.LegacyMigrationPlan;
import com.crisil.eir.policy.transition.MigrationTracker;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Migration coverage against the ACPIR 21 and the ACPIR 50 deadlines — <b>two obligations, two
 * figures, two dates, and deliberately no third figure combining them</b> (04 § 6, FR-908).
 *
 * <h2>Why the absence of a combined figure is the control</h2>
 *
 * <p>04 § 6: "ACPIR 21 and ACPIR 50 are two obligations with a common deadline, not one: the loan
 * must come under the EIR regime, and its ECL discounting must migrate to the EIR. Tracking them in
 * one field would hide a gap." The gap is not hypothetical, it is the expected sequence — ACPIR 50
 * explicitly concedes the interim contractual rate, so the ordinary state of a migrating book is
 * ACPIR 21 satisfied and ACPIR 50 outstanding. A single "78% migrated" figure over a book in that
 * state is arithmetically defensible and reports one obligation's completion as though it were
 * both: ACPIR 21 reaching 100% pulls the blended number up while ACPIR 50 has not started, and the
 * deadline that is actually at risk is the one the number conceals.
 *
 * <p>So there is no {@code overallCoverage()}, no {@code percentageMigrated()} and no
 * {@code totalOutstanding()} on this type. The two {@link ObligationCoverage} values are the
 * report. Anyone who needs a headline figure has to choose which obligation they are reporting,
 * which is the choice the merged figure removes.
 *
 * <h2>Two deadlines, held as two dates</h2>
 *
 * <p>They coincide today: both are 31 March 2030. They are carried as two fields anyway, and
 * {@link #ACPIR_21_DEADLINE} is its own literal rather than an alias of
 * {@link LegacyCohort#ACPIR_50_DEADLINE}, because the reference register records the interaction as
 * unresolved — "Paragraph 21 requires all loans outstanding at 31 March 2027 to be under the EIR
 * regime by 31 March 2030; paragraph 50 requires ECL computation for those loans to migrate to EIR
 * by the same date. Whether these are one obligation or two with a common deadline is not explicit;
 * treat as two." Deriving one date from the other would make a future divergence a one-line edit
 * that silently moved both, and this type does not require the two to be equal for exactly that
 * reason.
 *
 * <h2>Measured and supplied figures do not mix</h2>
 *
 * <p>The two obligation figures are <em>measured</em>: one recorded position per contract, read
 * through the ports, counted by asking {@code ContractMigrationState} its own two questions.
 * {@link #plannedContractsRequiringMigration()} is <em>supplied</em>: it is the sum of
 * {@code LegacyCohort.contractCount} over the cohorts that survive the deadline, and nothing in this
 * engine evaluates a cohort's definition — see {@link AssertedCohortMembership#BASIS}, which
 * {@link #describe()} prints beside it. The two are separate fields, never added, and their
 * difference is published as an unreconciled difference rather than as a gap, because attributing it
 * to either side would be a claim neither figure supports.
 *
 * @param asOf                                the date coverage is stated at; an argument, never a
 *                                            clock (03 § 1.1)
 * @param contractsInPopulation               how many contracts the exercise had to account for
 * @param contractsTracked                    how many carry a recorded ECL discount basis
 * @param untrackedContracts                  population less tracked — the figure that makes the
 *                                            rest of this report meaningful or meaningless
 * @param acpir21                             coverage of the recognition obligation
 * @param acpir50                             coverage of the ECL discounting obligation
 * @param underAcpir50Concession              tracked contracts on the EIR for interest and still
 *                                            discounting ECL at the contractual rate — the shape
 *                                            ACPIR 50's concession permits until the deadline
 * @param eclAheadOfInterest                  the reverse gap: ECL on the EIR while interest is not.
 *                                            Legitimate during a phased cutover and odd enough to
 *                                            surface
 * @param plannedContractsRequiringMigration  supplied, not measured: cohort contract counts summed
 *                                            over the cohorts surviving 31 March 2030
 * @param survivingCohortCount                how many cohorts run off after the deadline
 */
public record TransitionCoverage(
    LocalDate asOf,
    long contractsInPopulation,
    long contractsTracked,
    long untrackedContracts,
    ObligationCoverage acpir21,
    ObligationCoverage acpir50,
    long underAcpir50Concession,
    long eclAheadOfInterest,
    long plannedContractsRequiringMigration,
    int survivingCohortCount) {

    /**
     * 31 March 2030 — ACPIR 21's deadline for the legacy book to be under the EIR regime.
     *
     * <p>A literal, for {@code LegacyCohort.ACPIR_50_DEADLINE}'s reason: "a deadline that can be
     * changed by configuration is a deadline somebody will change." Its own literal rather than a
     * reference to that constant, for the reason in this class's javadoc: the two obligations'
     * dates coincide today and the source text does not say they are the same date.
     */
    public static final LocalDate ACPIR_21_DEADLINE = LocalDate.of(2030, 3, 31);

    public TransitionCoverage {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(acpir21, "acpir21");
        Objects.requireNonNull(acpir50, "acpir50");
        if (contractsInPopulation < 0 || contractsTracked < 0
            || plannedContractsRequiringMigration < 0 || survivingCohortCount < 0) {
            throw new IllegalArgumentException(
                "coverage figures must be non-negative: population " + contractsInPopulation
                    + ", tracked " + contractsTracked + ", planned "
                    + plannedContractsRequiringMigration + ", surviving cohorts "
                    + survivingCohortCount);
        }
        if (untrackedContracts != contractsInPopulation - contractsTracked) {
            // The one piece of arithmetic this record enforces rather than reports, because it is
            // the figure that decides whether everything else here means anything. A coverage
            // report showing nil untracked over a population half of which was never presented is
            // this codebase's most-found defect, and an untracked count that does not follow from
            // the other two is how it would present.
            throw new IllegalArgumentException(
                "coverage reports " + untrackedContracts + " untracked contracts against a"
                    + " population of " + contractsInPopulation + " and " + contractsTracked
                    + " tracked; the untracked figure has to be the difference, or the report is"
                    + " claiming a coverage it did not measure");
        }
        if (acpir21.contractsTracked() != contractsTracked
            || acpir50.contractsTracked() != contractsTracked) {
            throw new IllegalArgumentException(
                "the two obligations report " + acpir21.contractsTracked() + " and "
                    + acpir50.contractsTracked() + " tracked contracts against " + contractsTracked
                    + " overall; both obligations are measured over the same recorded positions, so"
                    + " a difference means one of them was counted over a different population");
        }
        if (underAcpir50Concession < 0 || underAcpir50Concession > contractsTracked) {
            throw new IllegalArgumentException(
                "the ACPIR 50 concession population is " + underAcpir50Concession + " of "
                    + contractsTracked + " tracked contracts");
        }
        if (eclAheadOfInterest < 0 || eclAheadOfInterest > contractsTracked) {
            throw new IllegalArgumentException(
                "the ECL-ahead-of-interest population is " + eclAheadOfInterest + " of "
                    + contractsTracked + " tracked contracts");
        }
    }

    /**
     * Coverage over the positions the exercise measured, and the plan it is measured against.
     *
     * <p><b>Where each figure comes from, and what is not recomputed.</b> The outstanding counts are
     * {@link MigrationTracker}'s published answers — {@code outstandingAcpir21Migrations()} and, for
     * the concession population, {@code outstandingAcpir50Migrations()}. The satisfied counts are
     * obtained by asking each {@link ContractMigrationState} its own two questions,
     * {@code satisfiesAcpir21()} and {@code satisfiesAcpir50()}, because the tracker does not publish
     * them. No predicate is restated: "satisfies ACPIR 50" has exactly one definition and this counts
     * the answers rather than re-deciding them.
     *
     * <p>ACPIR 50's outstanding count is {@code tracked - satisfying}, which decomposes as the
     * concession population plus the contracts satisfying neither obligation. Both parts are
     * published — the concession population as its own field, the rest visible as the difference —
     * because a book that has migrated no contract at all and a book mid-concession have the same
     * ACPIR 50 outstanding count and are not in the same position.
     *
     * @param asOf     the date coverage is stated at
     * @param states   the recorded positions, one per tracked contract
     * @param tracker  the tracker built over those positions and the full population
     * @param plan     the migration plan, for the supplied side of the report
     */
    public static TransitionCoverage of(
        LocalDate asOf,
        Collection<ContractMigrationState> states,
        MigrationTracker tracker,
        LegacyMigrationPlan plan) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(plan, "plan");

        long tracked = tracker.trackedContracts();
        if (states.size() != tracked) {
            throw new IllegalArgumentException(
                states.size() + " recorded positions were supplied and the tracker holds " + tracked
                    + "; the satisfied counts would then be measured over a different population"
                    + " than the outstanding counts, and the two halves of one obligation's"
                    + " coverage would not add up");
        }
        long satisfying21 = states.stream().filter(ContractMigrationState::satisfiesAcpir21).count();
        long satisfying50 = states.stream().filter(ContractMigrationState::satisfiesAcpir50).count();
        long outstanding21 = tracker.outstandingAcpir21Migrations();
        if (satisfying21 + outstanding21 != tracked) {
            // A genuine cross-check rather than a tautology: the satisfied count comes from the
            // states and the outstanding count from the tracker, so this fires if the tracker was
            // built over a different set of positions than the one being counted here.
            throw new IllegalArgumentException(
                satisfying21 + " contracts satisfy ACPIR 21 and the tracker reports "
                    + outstanding21 + " outstanding, against " + tracked + " tracked; the two"
                    + " figures come from different readings of the same population");
        }

        return new TransitionCoverage(
            asOf,
            tracked + tracker.untrackedContracts(),
            tracked,
            tracker.untrackedContracts(),
            new ObligationCoverage("ACPIR 21",
                "the loan is under the EIR regime: interest is recognised on the EIR",
                ACPIR_21_DEADLINE, tracked, satisfying21, outstanding21),
            new ObligationCoverage("ACPIR 50",
                "the loan's ECL is discounted at the EIR rather than at the interim contractual"
                    + " rate",
                LegacyCohort.ACPIR_50_DEADLINE, tracked, satisfying50, tracked - satisfying50),
            tracker.outstandingAcpir50Migrations(),
            tracker.eclAheadOfInterest().size(),
            plan.contractsRequiringMigration(),
            plan.survivingCohortsInQueueOrder().size());
    }

    /** Both obligations, in the order 04 § 6 states them. Never merged. */
    public List<ObligationCoverage> obligations() {
        return List.of(acpir21, acpir50);
    }

    /**
     * The unreconciled difference between the plan's supplied count and the measured population.
     *
     * <p>Plain data, and explicitly not a defect claim about either side. The planned figure is a
     * cohort contract count somebody supplied ({@link AssertedCohortMembership#BASIS}); the measured
     * figure is a population the ports named. A difference can mean the cohort counts are stale, or
     * that the exercise ran over one book of a bank with several, or that contracts have run off
     * since the cohorts were struck. It is published because a difference nobody looks at is how a
     * cohort count comes to be believed, and it is not published under an invariant id for the
     * reason {@code TransitionValuationRun.coverageAgainst} gives: an id here would attribute a
     * data-feed question to the valuation.
     *
     * <p>Signed, deliberately: positive means the plan expects more contracts to need migrating than
     * the exercise saw, negative means it saw more than the plan accounts for, and the two send a
     * reader to different places.
     */
    public long plannedLessMeasured() {
        return plannedContractsRequiringMigration - contractsInPopulation;
    }

    /** Whether every contract in the population carries a recorded ECL discount basis. */
    public boolean fullyTracked() {
        return untrackedContracts == 0 && contractsInPopulation > 0;
    }

    /**
     * The report, as the two obligations plus the caveat on the supplied figures.
     *
     * <p>The untracked count is printed first and unconditionally. A coverage report opening with a
     * completion figure invites the reader to stop there, and the completion figure is only as good
     * as the population it was measured over.
     */
    public String describe() {
        StringBuilder text = new StringBuilder("migration coverage as at ").append(asOf)
            .append(": ").append(contractsTracked).append(" of ").append(contractsInPopulation)
            .append(" contracts carry a recorded ECL discount basis");
        if (untrackedContracts > 0) {
            text.append(" — ").append(untrackedContracts)
                .append(" UNTRACKED, whose position is unknown rather than outstanding");
        }
        for (ObligationCoverage obligation : obligations()) {
            text.append("\n  ").append(obligation.describe(asOf));
        }
        text.append("\n  ").append(underAcpir50Concession)
            .append(" contract(s) are on the EIR for interest and still discounting ECL at the")
            .append(" contractual rate, which ACPIR 50's interim concession permits until ")
            .append(LegacyCohort.ACPIR_50_DEADLINE);
        if (eclAheadOfInterest > 0) {
            text.append("\n  ").append(eclAheadOfInterest)
                .append(" contract(s) discount ECL at the EIR while interest is not yet recognised")
                .append(" on it — a phased-cutover sequencing signal, not a breach");
        }
        text.append("\n  plan: ").append(plannedContractsRequiringMigration)
            .append(" contract(s) across ").append(survivingCohortCount)
            .append(" cohort(s) surviving ").append(LegacyCohort.ACPIR_50_DEADLINE)
            .append(" — SUPPLIED, not measured (").append(AssertedCohortMembership.BASIS)
            .append("); unreconciled against the measured population by ")
            .append(plannedLessMeasured());
        return text.toString();
    }

    /**
     * One obligation's coverage: how many contracts satisfy it, how many do not, and by when.
     *
     * <p><b>Why the requirement travels with the numbers.</b> Two coverage figures side by side are
     * indistinguishable without them, and the whole failure this type exists to prevent is a reader
     * taking one obligation's progress for the other's. So each value carries the obligation's name
     * and the sentence of what satisfying it means, and {@link #describe(LocalDate)} prints both.
     *
     * @param obligation        "ACPIR 21" or "ACPIR 50"
     * @param requirement       what satisfying it means, in one sentence
     * @param deadline          the date it must be satisfied by
     * @param contractsTracked  the measured population — contracts with a recorded position
     * @param contractsSatisfying how many satisfy it
     * @param contractsOutstanding how many do not; with the satisfied count this must exhaust the
     *                             tracked population, because a contract either satisfies an
     *                             obligation or does not
     */
    public record ObligationCoverage(
        String obligation,
        String requirement,
        LocalDate deadline,
        long contractsTracked,
        long contractsSatisfying,
        long contractsOutstanding) {

        public ObligationCoverage {
            Objects.requireNonNull(obligation, "obligation");
            Objects.requireNonNull(requirement, "requirement");
            Objects.requireNonNull(deadline, "deadline");
            if (contractsSatisfying < 0 || contractsOutstanding < 0 || contractsTracked < 0) {
                throw new IllegalArgumentException(obligation + " coverage has a negative count:"
                    + " tracked " + contractsTracked + ", satisfying " + contractsSatisfying
                    + ", outstanding " + contractsOutstanding);
            }
            if (contractsSatisfying + contractsOutstanding != contractsTracked) {
                // Enforced because the alternative is a partial partition that reads as coverage:
                // a contract counted in neither column has a recorded position that this report
                // does not classify, and it would vanish from both the progress figure and the
                // remaining-work figure.
                throw new IllegalArgumentException(obligation + " coverage classifies "
                    + contractsSatisfying + " satisfying and " + contractsOutstanding
                    + " outstanding out of " + contractsTracked + " tracked contracts; every"
                    + " recorded position either satisfies the obligation or does not, so a"
                    + " contract in neither column is one this report silently dropped");
            }
        }

        /** Whether the deadline has passed as at {@code asOf}. Strictly after, as the policy does. */
        public boolean isPastDeadline(LocalDate asOf) {
            Objects.requireNonNull(asOf, "asOf");
            return asOf.isAfter(deadline);
        }

        /**
         * Whether this obligation is in breach as at {@code asOf} — outstanding, past the deadline.
         *
         * <p>Deliberately not "outstanding" on its own. An outstanding obligation before the
         * deadline is the migration in progress, which is what ACPIR 21's three-year window and
         * ACPIR 50's interim concession exist to permit; calling it a breach would make the report
         * red from 2027 and red reports get suppressed. The same reasoning TM-1 is formulated on.
         */
        public boolean isInBreach(LocalDate asOf) {
            return contractsOutstanding > 0 && isPastDeadline(asOf);
        }

        /** This obligation's line of the report. */
        public String describe(LocalDate asOf) {
            return obligation + " (" + requirement + ") by " + deadline + ": "
                + contractsSatisfying + " of " + contractsTracked + " tracked contract(s) satisfy"
                + " it, " + contractsOutstanding + " outstanding"
                + (isInBreach(asOf) ? " — IN BREACH, the deadline has passed"
                    : isPastDeadline(asOf) ? " — deadline passed, satisfied"
                        : " — before the deadline");
        }
    }
}
