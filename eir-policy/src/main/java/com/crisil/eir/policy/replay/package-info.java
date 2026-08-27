/**
 * Deterministic replay of a closed period, and the nightly sampled control that proves it
 * (FR-903, invariant DT-1, control C-12).
 *
 * <h2>The two halves of FR-903, both load-bearing</h2>
 *
 * <p>FR-903 reads: "Replay any prior period <b>bit-identically</b> <b>under the policy then in
 * force</b>". Neither half is decoration.
 *
 * <p><b>Bit-identically</b> is stricter than numerically equal, and the difference is the whole
 * reason {@link com.crisil.eir.policy.replay.ReplayComparison} exists rather than a two-line
 * {@code equals} over two maps. {@link com.crisil.eir.domain.Money#equals} compares by numeric
 * value and ignores scale — deliberately, and correctly, for a type whose job is accounting
 * arithmetic, so that {@code Money.of("1.0")} equals {@code Money.of("1.00")}. A replay that
 * produces {@code 1.0} where the close published {@code 1.00} has produced a different published
 * artefact: the statement, the disclosure and the extract all differ, and a comparison built on
 * {@code Money.equals} reports a match. So this package never calls it. See
 * {@link com.crisil.eir.policy.replay.ReplayFigure#bitIdentical} for which comparison is used
 * where and why.
 *
 * <p><b>Under the policy then in force</b> is the half that is easiest to drop and the one that
 * makes the control mean something. A replay that reproduces the figures by resolving today's
 * policy versions has proved nothing: it reproduced the right number from the wrong rule, which
 * is luck, and the next period's figures will not hold. So the comparison carries the policy
 * version ids each run cited and treats a divergence as a DT-1 failure even when every figure
 * matches, resolving the expected snapshot through
 * {@link com.crisil.eir.policy.registry.PolicyVersionRegistry} at the <em>period end</em> rather
 * than at the replay date.
 *
 * <h2>Where this sits relative to PV-1</h2>
 *
 * <p>{@link com.crisil.eir.domain.InvariantId#PV_1} — asserted by the registry — is the
 * precondition: does a policy version resolve at all for every date in the period. DT-1, here, is
 * the consequence: given that it resolves, does the replay reproduce the figures under it.
 * {@code PolicyVersionRegistry.policyResolvableOn} says explicitly that its results must not be
 * conjoined with a bit-identical-replay result, because
 * {@link com.crisil.eir.domain.InvariantResult#conjunction} keeps only the first breach's
 * deviation among results sharing an id. This package publishes exactly one DT-1 result and
 * nothing under PV-1, so the two never meet in one list by accident.
 *
 * <h2>Alignment with the schema</h2>
 *
 * <p>{@code V2__ledger_and_transition.sql} is authoritative. {@code accounting_period} supplies
 * {@link com.crisil.eir.policy.replay.ClosedPeriod} — the {@code YYYYMM} {@code period_id}, the
 * start and end dates and the {@code ck_accounting_period_id_matches_dates} check.
 * {@code amortisation_run} supplies {@link com.crisil.eir.policy.replay.ReplayRun} — the run id,
 * the period, and the three version columns ({@code policy_version_id},
 * {@code rule_set_version_id}, {@code routing_table_version_id}), which this package generalises
 * to one id per {@link com.crisil.eir.policy.PolicyKind} for the reason
 * {@code PolicyKind} itself gives: the kinds move on different clocks, and there are seven of
 * them rather than three.
 */
package com.crisil.eir.policy.replay;
