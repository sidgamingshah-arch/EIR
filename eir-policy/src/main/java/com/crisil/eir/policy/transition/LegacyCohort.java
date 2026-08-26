package com.crisil.eir.policy.transition;

import com.crisil.eir.domain.FourEyes;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A slice of the existing book and how it is brought onto the EIR (04 § 6, FR-908).
 *
 * <p><b>The deadline is a literal, on purpose.</b> ACPIR 21 and ACPIR 50 both require the legacy
 * book on the EIR by 31 March 2030. That date is statutory, not configurable, and
 * {@link #ACPIR_50_DEADLINE} is a constant for the same reason the schema puts it in a generated
 * column rather than a settings table: a deadline that can be changed by configuration is a
 * deadline somebody will change.
 *
 * <p><b>Survival, not size.</b> {@link #survivesAcpir50Deadline()} is the question the whole
 * prioritisation turns on and it is answered here, once, so that two reports cannot answer it two
 * ways — the same reason the schema generates the column instead of storing it. Reconstruction
 * capacity spent on a cohort that has run off by the deadline buys nothing, because that exposure
 * never needs a reconstructed rate. Invariant LC-1, on {@link LegacyMigrationPlan}, is the
 * assertion that the queue respects it.
 *
 * @param cohortName          natural key; the schema makes it unique
 * @param definition          what the cohort selects — product, vintage, whatever the plan used
 * @param definedOn           when the cohort was struck
 * @param expectedRunoffDate  when the cohort is expected to have run off
 * @param migrationPriority   1 is first; the schema requires at least 1
 * @param method              reconstruct, or deem
 * @param contractCount       how many exposures; the tempting alternative ordering
 * @param approvedBy          who approved the cohort definition, or null while unapproved
 */
public record LegacyCohort(
    String cohortName,
    String definition,
    LocalDate definedOn,
    LocalDate expectedRunoffDate,
    int migrationPriority,
    MigrationMethod method,
    long contractCount,
    String approvedBy) {

    /**
     * 31 March 2030 — the ACPIR 21 and ACPIR 50 deadline.
     *
     * <p>A literal rather than a parameter. The schema's own comment on the generated column makes
     * the argument: "the deadline is statutory, not configurable".
     */
    public static final LocalDate ACPIR_50_DEADLINE = LocalDate.of(2030, 3, 31);

    public LegacyCohort {
        cohortName = FourEyes.requireIdentity(cohortName, "cohortName",
            "a cohort is cited by name in a migration plan");
        definition = FourEyes.requireIdentity(definition, "definition",
            "a cohort nobody can reproduce the membership of is not a cohort");
        Objects.requireNonNull(definedOn, "definedOn");
        Objects.requireNonNull(expectedRunoffDate, "expectedRunoffDate");
        Objects.requireNonNull(method, "method");
        approvedBy = approvedBy == null || approvedBy.isBlank() ? null : approvedBy.strip();
        if (migrationPriority < 1) {
            throw new IllegalArgumentException(
                "cohort " + cohortName + " has migration priority " + migrationPriority
                    + "; 1 is first and there is nothing before it");
        }
        if (expectedRunoffDate.isBefore(definedOn)) {
            throw new IllegalArgumentException(
                "cohort " + cohortName + " is expected to run off on " + expectedRunoffDate
                    + ", before it was defined on " + definedOn);
        }
        if (contractCount < 0) {
            throw new IllegalArgumentException(
                "cohort " + cohortName + " has " + contractCount + " contracts");
        }
    }

    /**
     * Whether this cohort is still on the books after the ACPIR 21 and 50 deadline.
     *
     * <p>Strictly after: a cohort running off <em>on</em> 31 March 2030 has met the deadline, since
     * the obligation is to be on the EIR by that date and an exposure that ends on it was on
     * whatever basis it was on for its whole life. The schema draws the boundary the same way
     * ({@code expected_runoff_date > DATE '2030-03-31'}), and having the two disagree by a day
     * would put a cohort in the priority queue in one place and out of it in the other.
     */
    public boolean survivesAcpir50Deadline() {
        return expectedRunoffDate.isAfter(ACPIR_50_DEADLINE);
    }

    /**
     * Whether reconstruction effort on this cohort buys anything.
     *
     * <p>False for a cohort that has run off before the deadline and is nonetheless queued for
     * {@code FULL_RECONSTRUCTION}. Reported as plain data rather than an invariant: spending
     * effort badly is not an accounting breach, and an invariant id on it would put a programme
     * management question in the same list as a figure that does not tie. It is published because
     * it is the roadmap's own sentence — "reconstructing an EIR for a loan maturing in 2029 is
     * wasted effort" — and a plan nobody checks it against will contain some.
     */
    public boolean reconstructionEffortIsWasted() {
        return method == MigrationMethod.FULL_RECONSTRUCTION && !survivesAcpir50Deadline();
    }

    /** Whether a definition approval is on file. */
    public boolean isApproved() {
        return approvedBy != null;
    }

    /** A one-line description naming the cohort, its fate and its place in the queue. */
    public String describe() {
        return "cohort " + cohortName + " (" + contractCount + " contracts, " + definition
            + ") runs off " + expectedRunoffDate
            + (survivesAcpir50Deadline() ? " — SURVIVES the 2030 deadline" : " — before the deadline")
            + ", priority " + migrationPriority + " by " + method
            + (isApproved() ? ", approved by " + approvedBy : ", unapproved");
    }
}
