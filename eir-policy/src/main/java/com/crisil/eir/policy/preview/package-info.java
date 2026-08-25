/**
 * FR-210's impact-preview gate: a policy version cannot become {@code EFFECTIVE} without a
 * stored, current, internally coherent portfolio-level preview of what it does to the book.
 *
 * <p>FR-210 verbatim: "Support maker–checker on rule-set versions, with an effective date and a
 * stored portfolio-level impact preview generated before the version can go effective." Two
 * gates, not one. This package owns the second — {@link
 * com.crisil.eir.policy.preview.ActivationGate} — and deliberately knows nothing about the
 * first. The maker–checker transition lives in {@code com.crisil.eir.policy.approval}; the
 * composition is that the transition asks this gate for a decision and refuses its own
 * transition when the answer is a refusal. Nothing here defines, performs or validates a status
 * transition, so the two can be written and changed independently.
 *
 * <p><b>The gate is the deliverable, not the record.</b> {@link
 * com.crisil.eir.policy.preview.ImpactPreview} is a value with some derived figures on it; any
 * competent team writes that. What the requirement is actually buying is the refusal — 06 § 5's
 * {@code 409} — and a refusal is only worth anything if it fires on the cases that look fine.
 *
 * <h2>Why this gate carries the weight it does</h2>
 *
 * <p>Compressing an assumed life from 240 months to 96 multiplies year-one net fee recognition
 * by <b>3.73x</b> — 2,525.04 becomes 9,410.03 on the same exposure (03 § 3.6) — and it does so
 * through a one-line change to a behavioural curve. That is the UK restatement pattern in the
 * roadmap's risk register, and 03 § 6.3 explains why it lands all at once: a CPR revision is a
 * B5.4.6 catch-up across <em>every affected contract simultaneously</em>, so the movement
 * appears in a single period with no gradual signal beforehand. A version that goes effective
 * unpreviewed is exactly how a number of that size arrives unnoticed.
 *
 * <h2>The staleness position, stated</h2>
 *
 * <p><b>A preview generated against an earlier draft of the same version id is refused, and
 * refused under a different reason from an absent one, because it is worse than absent.</b> An
 * absent preview stops the version and tells whoever is looking that nobody has quantified the
 * change. A superseded preview stops nothing on a checkbox control and reads, in the audit
 * file, as evidence that somebody did — a stored record, correctly named, against the right
 * version id, with numbers in it. It is the specific failure this gate exists to catch, so
 * {@link com.crisil.eir.policy.preview.ActivationRefusalReason#looksLikeDiligence()} is a
 * published property of every refusal reason and only {@code NO_PREVIEW_STORED} answers false.
 *
 * <p>Draft identity is therefore content identity, not the version id: every preview carries a
 * {@link com.crisil.eir.policy.preview.DraftFingerprint} of the draft it was computed against,
 * and the gate matches on it. Two consequences worth stating because both are deliberate:
 *
 * <ul>
 *   <li>An <em>old</em> preview whose fingerprint matches is accepted, subject to the age
 *       horizon. Content identity means it really is a preview of this draft; the date it was
 *       run is a separate question, answered by the horizon.
 *   <li>A <em>recent</em> preview whose fingerprint does not match is refused outright, with no
 *       override and no tolerance. There is no version of "the draft changed a little" that
 *       makes a quantified delta still true — 03 § 3.6's figures move 3.73x on one assumption.
 * </ul>
 *
 * <p>The second staleness axis is the portfolio, not the draft. The whole content of a preview
 * is a portfolio-level delta, and the book turns over underneath it; a preview matching the
 * current draft but measured against a portfolio two quarters gone is not a preview of this
 * activation either. Hence {@link
 * com.crisil.eir.policy.preview.ActivationGate#DEFAULT_PREVIEW_HORIZON} — a default, and
 * configurable, because the number is a policy judgement and policy judgements in this engine
 * are data (ADR-0006).
 *
 * <p>That axis is measured from the <em>older</em> of when the preview ran and the book position
 * it reports ({@link com.crisil.eir.policy.preview.ImpactPreview#stalenessAt}). The two come
 * apart, and the gap is exploitable without anybody intending it: re-running a preview this
 * morning against a portfolio extract pulled a year ago produces a record minutes old whose
 * every figure describes a book that no longer exists. 02 § 3.2 requires the preview to be run
 * "against the live portfolio", so the book date is what the horizon is measured against
 * whenever it is the older of the two.
 *
 * <h2>What this package does not do</h2>
 *
 * <p>It does not persist anything. {@link
 * com.crisil.eir.policy.preview.ImpactPreviewRegister} is an in-memory, immutable holder so the
 * gate has a concrete meaning for the word "stored" and can be exercised end to end; 04 § 2.12's
 * {@code impact_preview_ref} is a persistence concern owned by {@code eir-persistence}.
 *
 * <p>It does not decide whether a transition from the version's current status to
 * {@code EFFECTIVE} is legal, whether the checker differs from the maker, or whether the
 * effective date is retrospective. Those are answered elsewhere, and answered better there.
 *
 * <p>It returns no {@link com.crisil.eir.domain.InvariantResult}. The gate's outcome is an
 * {@link com.crisil.eir.policy.preview.ActivationDecision}, which mirrors {@code
 * InvariantResult}'s shape — a boolean, a reason, a detail string, never a throw for a data
 * condition — because the invariant identifier register in {@code eir-domain} is owned by
 * another work unit. The identifier this gate wants is <b>PG-1</b>, "no policy version effective
 * without a current impact preview"; when it exists, {@code ActivationDecision} can be mapped
 * onto it without changing a caller.
 */
package com.crisil.eir.policy.preview;
