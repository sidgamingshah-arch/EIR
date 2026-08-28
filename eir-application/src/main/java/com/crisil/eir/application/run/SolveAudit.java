package com.crisil.eir.application.run;

import com.crisil.eir.calc.solver.RateSolver;
import com.crisil.eir.calc.solver.SolveRequest;
import com.crisil.eir.calc.solver.SolveResult;
import com.crisil.eir.calc.solver.SolveStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The only route from this package to a {@link RateSolver}, and a count of every time it was taken.
 *
 * <p><b>Why the count exists.</b> 05 § 3.2's note on its own sequence diagram: the solve is "inside
 * the event branch only. A fixed-rate contract with no events never re-solves. The steady-state run
 * is overwhelmingly roll-forward arithmetic, which is what makes the 10M-contract target
 * reachable". A pipeline that re-solved every contract every period would agree with this one to
 * the last paise on any contract with no events — the figures are identical, because the rate the
 * solve would return is the rate already stored — and would miss the 10M target by orders of
 * magnitude. A defect whose only symptom is cost is a defect nothing in a figure-comparison test
 * can see, so the absence of a solve is made into a fact the run reports.
 *
 * <p><b>Why it wraps the solver rather than sitting beside it.</b> A counter the pipeline is
 * trusted to increment is a counter a future edit forgets to increment, and the count would then
 * read zero for a pipeline that solved ten million times — a control that says exactly what you
 * wanted to hear, which is the shape of failure this codebase has recorded repeatedly. Here the
 * pipeline holds a {@code SolveAudit} and no {@code RateSolver}, so there is no unaudited path to a
 * solve: the count is of solver invocations, not of intentions to record one.
 *
 * <p><b>Mutable, ordered, and not thread-safe.</b> The records are appended in call order, which
 * keeps a run's audit deterministic for FR-903's byte-identical replay. 05 § 3.2 runs partitions in
 * parallel, so each partition takes its own audit and the run sums them; sharing one across threads
 * would make the order — and therefore the replay — depend on scheduling.
 */
public final class SolveAudit {

    private final RateSolver solver;
    private final List<SolveRecord> records = new ArrayList<>();

    private SolveAudit(RateSolver solver) {
        this.solver = Objects.requireNonNull(solver, "solver");
    }

    /** An audit over one solver. */
    public static SolveAudit over(RateSolver solver) {
        return new SolveAudit(solver);
    }

    /**
     * Solves, recording the contract and the reason.
     *
     * <p>{@code reason} is required and must say <em>which branch</em> commissioned the solve. It
     * is the sentence an auditor reads when asking why a rate moved, and it is also the thing that
     * makes an unexpected count diagnosable: a run with ten million solves and ten million reasons
     * reading "reset on TIME_VALUE_OF_MONEY" is a repricing month, and one reading "period
     * roll-forward" is the defect this class exists to expose.
     */
    public SolveResult solve(String contractId, String reason, SolveRequest request) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(request, "request");
        if (contractId.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException(
                "a recorded solve names the contract and the branch that commissioned it; got"
                    + " contractId '" + contractId + "' and reason '" + reason + "'");
        }
        SolveResult result = solver.solve(request);
        records.add(new SolveRecord(contractId, reason, result.status()));
        return result;
    }

    /** How many solves this audit has seen, over every contract. */
    public int solveCount() {
        return records.size();
    }

    /** How many solves this audit has seen for one contract. */
    public int solveCountFor(String contractId) {
        Objects.requireNonNull(contractId, "contractId");
        int count = 0;
        for (SolveRecord record : records) {
            if (record.contractId().equals(contractId)) {
                count++;
            }
        }
        return count;
    }

    /** Every solve, in call order. */
    public List<SolveRecord> records() {
        return Collections.unmodifiableList(records);
    }

    /** A one-line summary for the run record. */
    public String describe() {
        return records.isEmpty()
            ? "no solve was performed: every contract rolled forward at its stored rate (05 § 3.2)"
            : records.size() + " solve(s) performed";
    }

    /**
     * One solve, attributed.
     *
     * @param contractId which contract
     * @param reason     which branch commissioned it
     * @param status     how it went; a {@link SolveStatus#routesToExceptionQueue()} status here is
     *                   what quarantines the contract one level up
     */
    public record SolveRecord(String contractId, String reason, SolveStatus status) {

        public SolveRecord {
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(status, "status");
        }
    }
}
