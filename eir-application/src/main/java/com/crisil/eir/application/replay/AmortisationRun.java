package com.crisil.eir.application.replay;

import com.crisil.eir.application.RunRequest;

/**
 * The batch job — the one 05 § 3.2 describes and the one 05 § 3.3 replays.
 *
 * <p><b>Why this interface is declared here and not by the run itself.</b> 05 § 3.3 is one
 * sentence long and it is a design constraint rather than a description: "A replay is the
 * <em>same batch job</em> with {@code is_replay = true} and an as-at boundary." A replay
 * implemented as a second pipeline proves nothing about the first — the two drift, and the drift
 * is invisible precisely where DT-1 exists to see it, because a replay pipeline that reproduces
 * its own arithmetic reproduces nothing about the run that published the figures. So this package
 * does not contain a projector, a solver, a roll-forward or a journal builder. It contains the
 * assembly of one {@link RunRequest} that differs from a live one in exactly one field, and a
 * call to whatever executes it.
 *
 * <p>The per-contract run is being built as a sibling unit and its exact type names are not yet
 * settled, so this is the narrowest surface the replay can be written against: it takes the
 * committed spine's {@link RunRequest} and returns the committed spine's
 * {@link com.crisil.eir.application.ContractResult} list, plus the one thing the spine does not
 * carry and DT-1 cannot do without — see {@link RunOutput#policyVersionsConsulted()}. When the
 * sibling lands, adapting it is one lambda; nothing in this package needs to know its shape.
 *
 * <p><b>The implementation must not read a clock.</b> Every input reaches it through
 * {@link com.crisil.eir.application.port.AsAtBoundary} on the request (03 § 1.1, ADR-0003). A run
 * that reads {@code Instant.now()} cannot be re-run, which is the failure DT-1 detects and the
 * reason this interface hands the boundary in rather than letting the job decide what "now" is.
 */
@FunctionalInterface
public interface AmortisationRun {

    /**
     * Runs the job.
     *
     * <p>The same method a live run calls. The replay calls it with a request whose boundary is
     * {@link com.crisil.eir.application.port.AsAtBoundary#replaying}; nothing else differs, and
     * {@link ReplayUseCase} exists mostly to make that true and provable.
     *
     * @param request the run — its boundary says whether this is a replay, and every port on it
     *                answers as at that boundary
     * @return one result per contract in the population, computed or quarantined, and the policy
     *         versions the run consulted
     */
    RunOutput execute(RunRequest request);
}
