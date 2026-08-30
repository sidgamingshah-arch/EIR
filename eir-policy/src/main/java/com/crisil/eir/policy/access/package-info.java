/**
 * FR-906's role model: who may start a run, who may close a period, who may approve, and who may
 * only read — 07 § 7's RBAC and segregation of duties, as values.
 *
 * <p><b>What was missing.</b> {@code eir-api}'s README and its own close response both said it:
 * "No authentication or authorisation. FR-906's role model is unimplemented, so the maker-checker
 * identity on a close is whatever the caller typed." That is a real hole rather than a cosmetic one,
 * because every control in the close gate rests on identities the caller asserts about themselves.
 * The exception acceptance carries a maker and a checker, {@code PeriodCloseGate} refuses a
 * self-approved one, and nothing anywhere asked whether the caller was entitled to be either name.
 *
 * <p><b>What this package is, and what it is not.</b> It is the <em>authorisation</em> half: given a
 * name, what may it do, and is it already on the other side of the control. It is not
 * authentication. 07 § 7 specifies OAuth2 client credentials over TLS 1.3 with an external secret
 * manager, and none of that is in this repository. {@link
 * com.crisil.eir.policy.access.RoleRegister} says so at length, {@link
 * com.crisil.eir.policy.access.AuthorisationRequest#assertedIdentity()} is named for it, and every
 * HTTP response built on it repeats it — a header-asserted identity presented as authentication
 * would be worse than the honest absence it replaces.
 *
 * <p><b>Why this lives in {@code eir-policy}.</b> A role table is versioned, approved policy data —
 * the same kind of thing as a fee rule set or a routing table version, and 07 § 4.2 puts
 * "materiality thresholds and tier assignment rules" under the same maker–checker control. It sits
 * above {@code eir-domain} so that it can use {@link com.crisil.eir.domain.FourEyes}, and below
 * {@code eir-api} so that the batch, the console and any future caller share one answer instead of
 * each edge inventing its own.
 *
 * <p><b>The five types.</b> {@link com.crisil.eir.policy.access.Capability} is the closed set of
 * acts — closed because 07 § 5 forbids a manual rate override permanently, and an act that cannot be
 * named cannot be granted. {@link com.crisil.eir.policy.access.Role} maps the four journeys of
 * 02 § 3 onto capability sets. {@link com.crisil.eir.policy.access.Principal} is an identity and its
 * roles — a <em>set</em> of roles, which is what makes segregation of duties a runtime check rather
 * than a property of the role table. {@link com.crisil.eir.policy.access.RoleRegister} is the grant
 * table. {@link com.crisil.eir.policy.access.AccessControl} decides, returning a
 * {@link com.crisil.eir.policy.access.AccessDecision} with an
 * {@link com.crisil.eir.policy.access.AccessRefusal} where refused.
 *
 * <p><b>One rule, one spelling.</b> The maker-cannot-be-the-checker comparison is
 * {@link com.crisil.eir.domain.FourEyes#sameIdentity}, borrowed and not restated. It was written
 * four times in this codebase with two answers, and the fourth spelling — a plain {@code equals} —
 * guarded the routing table version that decides how every event is treated.
 *
 * <p><b>What this package does not decide.</b> Whether a given {@code ExceptionAcceptance} is
 * self-approved: that is a fact about the artefact's two named signatories, held by
 * {@code ExceptionAcceptance.isSelfApproved()} and refused by
 * {@code CloseGateRefusal.SELF_APPROVED_ACCEPTANCE}. Restating it here would be a sixth spelling
 * whose answer could disagree with the fifth.
 *
 * <p><b>Refusals are returned, never thrown</b>, like every other gate in this module. A throw here
 * means a defect in the gate: a {@code null} register, a blank identity, a grant table with one
 * identity in it twice.
 */
package com.crisil.eir.policy.access;
