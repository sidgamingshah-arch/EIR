/**
 * The replay use case: the same batch job, an as-at boundary, and DT-1 (05 § 3.3, FR-903, C-12).
 *
 * <h2>Why there is no pipeline in this package</h2>
 *
 * <p>05 § 3.3 is four sentences and the first is a design constraint rather than a description: "A
 * replay is the <b>same batch job</b> with {@code is_replay = true} and an as-at boundary." Read as
 * a constraint it forbids most of what a replay package would otherwise contain. There is no
 * projector here, no solver, no roll-forward, no journal builder and no second reading of any port.
 * {@link com.crisil.eir.application.replay.ReplayUseCase} assembles one
 * {@link com.crisil.eir.application.RunRequest} that differs from a live one in exactly two fields
 * — the run id, and the {@link com.crisil.eir.application.port.AsAtBoundary} — hands it to the same
 * {@link com.crisil.eir.application.replay.AmortisationRun} a live run drives, and compares what
 * comes back.
 *
 * <p>The alternative is worse than merely duplicative. A replay implemented as a parallel pipeline
 * proves nothing about the original: it reproduces its own arithmetic, the two implementations
 * drift, and they drift precisely where DT-1 exists to detect drift, because every figure the
 * replay produces is internally consistent. The control would then be green on a period nobody
 * could reproduce with the code that published it.
 *
 * <h2>What each piece is for</h2>
 *
 * <ul>
 *   <li>{@link com.crisil.eir.application.replay.PublishedRun} — the artefact to reproduce, plus
 *       the one input a replay cannot derive: the original run's {@code recorded_at}. Business time
 *       is on the period; system time is not derivable, and guessing it with a clock read is the
 *       failure ADR-0003 and DT-1 both name.</li>
 *   <li>{@link com.crisil.eir.application.replay.ReplayRequest} — the published run plus a live
 *       {@link com.crisil.eir.application.RunRequest} for the same period, from which the shadow
 *       request is derived field by field.</li>
 *   <li>{@link com.crisil.eir.application.replay.AmortisationRun} and
 *       {@link com.crisil.eir.application.replay.RunOutput} — the narrowest surface the replay can
 *       be written against while the per-contract run is built as a sibling unit. Spine types in,
 *       spine types out, plus the run's policy stamps, which
 *       {@link com.crisil.eir.application.ContractResult} does not carry and FR-903's second half
 *       cannot do without.</li>
 *   <li>{@link com.crisil.eir.application.replay.ShadowRun} — the shadow table: per-contract
 *       closing balances and journal lines, at the scale the run produced them and <b>never</b>
 *       rescaled, because a scale drift is a DT-1 breach worth zero rupees.</li>
 *   <li>{@link com.crisil.eir.application.replay.PopulationAccount} — FR-905's subtraction. Every
 *       contract computed or quarantined, and the count adds up.</li>
 *   <li>{@link com.crisil.eir.application.replay.ReplayCoverage} and
 *       {@link com.crisil.eir.application.replay.ReplayVerification} — one DT-1 result per period,
 *       and the guarantee that a comparison which compared nothing does not report a
 *       reproduction.</li>
 *   <li>{@link com.crisil.eir.application.replay.NightlyReplayReport} — one night of C-12, with no
 *       DT-1 result at all on a night that replayed nothing, because both available answers would
 *       be false.</li>
 * </ul>
 *
 * <h2>Where the comparison itself lives</h2>
 *
 * <p>In {@code eir-policy}'s {@code replay} package, and it stays there. That package holds the
 * two defects this one must not reintroduce, both recorded in
 * {@code ReplayComparison}'s javadoc: a policy leg that iterated only the kinds the two runs
 * happened to stamp and so could not fail when neither stamped anything, and a vacuous comparison
 * whose honest {@code isVacuous()} was not in the published verdict, so a caller asking only
 * {@code satisfied()} got green from a control that had looked at nothing. This package composes
 * that comparison rather than reimplementing it, hands it stamps the <em>job</em> reported rather
 * than ones it resolved itself, and adds the one coverage state the upstream vacuity test does not
 * reach — figures absent while policy stamps agree.
 */
package com.crisil.eir.application.replay;
