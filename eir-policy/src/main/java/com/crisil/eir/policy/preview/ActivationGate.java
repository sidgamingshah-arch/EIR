package com.crisil.eir.policy.preview;

import com.crisil.eir.domain.AnywhereOnEarth;

import com.crisil.eir.policy.PolicyVersion;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * FR-210's hard gate: refuses to let a policy version become {@code EFFECTIVE} without a stored,
 * current, coherent impact preview for that exact draft.
 *
 * <p>This is the deliverable of the requirement. 06 § 5 states the same thing as a
 * {@code 409} on {@code POST /policy-versions/{id}/approve}, and 07 § 4.2 as a control over
 * seven kinds of versioned artefact. All three are describing a refusal, and the record the
 * refusal reads is the easy half.
 *
 * <p><b>Scope, stated because the boundary is the design.</b> The gate answers exactly one
 * question — is there a usable preview of this draft? — and nothing about approval workflow. It
 * does not check that the maker differs from the checker, that the current status may legally
 * advance to {@code EFFECTIVE}, or that the effective date is not retrospective; those belong
 * with the maker–checker transition, which knows the transition's own preconditions. The
 * composition is by conjunction: the transition refuses when this gate refuses, and this gate
 * has no opinion on anything else the transition checks. Written this way so the two can be
 * changed independently, and so this one can be unit-tested without a workflow.
 *
 * <p>The gate does ask the {@link com.crisil.eir.policy.PolicyKind} whether the change moves
 * recognised income, rather than assuming every change does. That method exists for this caller.
 *
 * <p><b>Refusal priority.</b> When several faults are present the gate reports the most
 * fundamental, in this order: wrong version, wrong draft, incoherent figures, generated after
 * the activation, generated after approval, too old. The order runs from "this is not a preview
 * of this thing" to "this is a preview of this thing, taken too long ago", because a message
 * naming the age of a preview that describes a different draft would send its reader to fix the
 * wrong problem.
 */
public final class ActivationGate {

    /**
     * How old a preview may be at activation, by default: 90 days, one reporting quarter.
     *
     * <p>A default and not a rule — the constructor takes a horizon, because this is a policy
     * judgement and policy judgements in this engine are versioned data rather than constants in
     * a class file (ADR-0006, and the roadmap's Phase 2 exit gate: the routing table can be
     * changed without a code deploy).
     *
     * <p>One quarter because a preview's entire content is a portfolio aggregate, and the
     * quarter is the coarsest cycle at which the portfolio it aggregates is itself reported and
     * reviewed. Beyond that the book has originated, amortised and closed underneath the
     * preview, and its materiality conclusion no longer transfers to the book that will actually
     * be restated. A tighter horizon would be defensible; an unbounded one is not, because it
     * makes "a preview was run once" sufficient forever.
     *
     * <p>Applied to {@link ImpactPreview#stalenessAt}, not to the generation instant, so that a
     * preview re-run today against an old portfolio extract is measured by the book it saw
     * rather than by the moment somebody pressed the button.
     */
    public static final Duration DEFAULT_PREVIEW_HORIZON = Duration.ofDays(90);

    private final Duration previewHorizon;

    /**
     * @param previewHorizon how old a matching preview may be at activation; must be positive,
     *     since a zero or negative horizon would refuse every preview ever generated and a gate
     *     that refuses everything is indistinguishable from a gate nobody can satisfy
     */
    public ActivationGate(Duration previewHorizon) {
        Objects.requireNonNull(previewHorizon, "previewHorizon");
        if (previewHorizon.isZero() || previewHorizon.isNegative()) {
            throw new IllegalArgumentException(
                "the preview horizon must be positive, got " + previewHorizon
                    + "; a non-positive horizon refuses every preview, including one generated"
                    + " a second ago");
        }
        this.previewHorizon = previewHorizon;
    }

    /** A gate with {@link #DEFAULT_PREVIEW_HORIZON}. */
    public static ActivationGate withDefaultHorizon() {
        return new ActivationGate(DEFAULT_PREVIEW_HORIZON);
    }

    /** The horizon this gate applies. */
    public Duration previewHorizon() {
        return previewHorizon;
    }

    /**
     * Decides whether {@code version} may become {@code EFFECTIVE} on the evidence of everything
     * stored for it.
     *
     * <p>Named distinctly from {@link #decide(PolicyVersion, DraftFingerprint, ImpactPreview,
     * Instant)} rather than overloading it, because that method's contract accepts {@code null}
     * to mean "none is stored" and two four-argument overloads would make a literal {@code null}
     * ambiguous at every call site — a cast at each one, on the path that most needs to read
     * plainly.
     *
     * <p>Selection walks the previews of the current draft content newest first and takes the
     * first that the gate permits. Not merely the newest: the newest matching preview can be
     * unusable for a reason unrelated to the draft — a skewed clock, a broken aggregation — while
     * an older preview of the same content is perfectly good, and refusing with a usable preview
     * on file is a false refusal. Where none is usable, the refusal reported is the one for the
     * newest, since that is the attempt the maker will be looking at.
     *
     * <p>Where the register holds previews for the version but none of the current draft, the
     * refusal is {@link ActivationRefusalReason#STALE_DRAFT_PREVIEW} and names the newest of
     * them — the distinction from an empty register is the point, and the message has to make the
     * reader look at the draft rather than at whether a preview exists.
     *
     * @param version the version being activated
     * @param currentDraft content identity of what the version says right now
     * @param register what has been previewed and stored
     * @param activationInstant when the move to {@code EFFECTIVE} is being attempted
     */
    public ActivationDecision decideFromRegister(
        PolicyVersion version,
        DraftFingerprint currentDraft,
        ImpactPreviewRegister register,
        Instant activationInstant) {

        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(register, "register");
        Objects.requireNonNull(activationInstant, "activationInstant");

        Optional<ActivationDecision> exemption = exemption(version);
        if (exemption.isPresent()) {
            return exemption.get();
        }

        List<ImpactPreview> matching = register.matchingFor(version.id(), currentDraft);
        if (!matching.isEmpty()) {
            ActivationDecision newestDecision = null;
            for (ImpactPreview candidate : matching) {
                ActivationDecision decision =
                    decide(version, currentDraft, candidate, activationInstant);
                if (decision.permitted()) {
                    return decision;
                }
                if (newestDecision == null) {
                    newestDecision = decision;
                }
            }
            return newestDecision;
        }

        List<ImpactPreview> stored = register.storedFor(version.id());
        if (stored.isEmpty()) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.NO_PREVIEW_STORED,
                "no impact preview is stored for " + version.id() + " (" + currentDraft
                    + "). FR-210 requires a portfolio-level preview generated before the version"
                    + " can go effective");
        }
        ImpactPreview newest = register.newestFor(version.id()).orElseThrow();
        return ActivationDecision.refuse(version.id(),
            ActivationRefusalReason.STALE_DRAFT_PREVIEW,
            stored.size() + " impact preview(s) are stored for " + version.id()
                + " but none covers the current draft " + currentDraft
                + "; the newest covers " + newest.draftFingerprint() + " generated "
                + newest.generatedAt() + ". A preview of a superseded draft is worse than none:"
                + " it satisfies a control that asks only whether a preview exists");
    }

    /**
     * Decides against a single supplied preview, which may be {@code null} to mean none is
     * stored.
     *
     * <p>The entry point the maker–checker transition composes with, since it will already hold
     * whichever preview the persistence layer resolved from {@code impact_preview_ref}
     * (04 § 2.12). {@code null} rather than an {@link Optional} parameter because the caller is
     * reading a nullable persisted reference, and forcing a wrap at every call site buys nothing
     * that this method's contract does not already state.
     */
    public ActivationDecision decide(
        PolicyVersion version,
        DraftFingerprint currentDraft,
        ImpactPreview preview,
        Instant activationInstant) {

        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(activationInstant, "activationInstant");

        Optional<ActivationDecision> exemption = exemption(version);
        if (exemption.isPresent()) {
            return exemption.get();
        }

        if (preview == null) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.NO_PREVIEW_STORED,
                "no impact preview is stored for " + version.id() + " (" + currentDraft
                    + "). FR-210 requires a portfolio-level preview generated before the version"
                    + " can go effective");
        }

        // 1. Not a preview of this version at all. Reported first because every later message
        // would otherwise describe some other policy's effect on the book as though it were this
        // one's.
        if (!preview.previews(version.id())) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.PREVIEW_FOR_A_DIFFERENT_VERSION,
                "the supplied preview previews " + preview.policyVersionId() + ", not "
                    + version.id() + ". Its figures are a different policy's impact on the book");
        }

        // 2. Right version, wrong draft. The refusal this gate exists for — see
        // ActivationRefusalReason.STALE_DRAFT_PREVIEW.
        if (!preview.coversDraft(currentDraft)) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.STALE_DRAFT_PREVIEW,
                "the stored preview for " + version.id() + " covers " + preview.draftFingerprint()
                    + ", generated " + preview.generatedAt() + ", but the current draft is "
                    + currentDraft + ". The version id is stable across draft edits, so this"
                    + " preview goes on looking current while measuring content that has been"
                    + " replaced");
        }

        // 3. A preview of the right draft whose own figures do not tie. Checked before the two
        // time-order tests because a timely preview with a broken aggregation is not a preview:
        // nothing in it can be relied on, so its timestamp is not the interesting fact about it.
        List<String> incoherences = preview.incoherences();
        if (!incoherences.isEmpty()) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.INCOHERENT_PREVIEW,
                "the stored preview for " + version.id() + " contradicts itself: "
                    + String.join("; ", incoherences)
                    + ". Its aggregation is broken, so no figure in it can be relied on");
        }

        // 4. Stamped after the activation it is meant to have preceded.
        if (preview.generatedAt().isAfter(activationInstant)) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.PREVIEW_POSTDATES_ACTIVATION,
                "the stored preview for " + version.id() + " is stamped "
                    + preview.generatedAt() + ", after the attempted activation at "
                    + activationInstant + ". FR-210 requires it to be generated before the"
                    + " version can go effective, and a future stamp also makes its age"
                    + " unassessable");
        }

        // 5. Generated after the checker signed off, which inverts the control (07 § 4.2).
        // Conservative on time zone: approvedOn is a date, and the refusal fires only past the
        // last instant that date could still be current anywhere on Earth.
        LocalDate approvedOn = version.approvedOn();
        if (approvedOn != null) {
            Instant latestPossibleApprovalInstant = AnywhereOnEarth.firstInstantAfter(approvedOn);
            if (!preview.generatedAt().isBefore(latestPossibleApprovalInstant)) {
                return ActivationDecision.refuse(version.id(),
                    ActivationRefusalReason.PREVIEW_POSTDATES_APPROVAL,
                    "the stored preview for " + version.id() + " was generated "
                        + preview.generatedAt() + ", after the approval dated " + approvedOn
                        + " had ended in every real time zone. 07 § 4.2 requires the preview to"
                        + " precede approval; run afterwards, it accompanies a decision already"
                        + " taken rather than informing it");
            }
        }

        // 6. A preview of the right draft, in the right order, measured against a book that has
        // since moved on. Measured from the OLDER of the generation instant and the book date it
        // reports — see ImpactPreview.stalenessAt. Using the generation instant alone would let a
        // preview re-run today against a year-old portfolio extract through untouched, and that
        // is the one thing the horizon exists to stop.
        Duration staleness = preview.stalenessAt(activationInstant);
        if (staleness.compareTo(previewHorizon) > 0) {
            return ActivationDecision.refuse(version.id(),
                ActivationRefusalReason.PREVIEW_OLDER_THAN_HORIZON,
                "the stored preview for " + version.id() + " matches the current draft but is "
                    + staleness.toDays() + " days stale at the attempted activation, against a"
                    + " horizon of " + previewHorizon.toDays() + " days — generated "
                    + preview.generatedAt() + " against the book at " + preview.portfolioAsOf()
                    + ". Its figures are portfolio aggregates and the book has originated,"
                    + " amortised and closed underneath them");
        }

        return ActivationDecision.permit(version.id(),
            "activation permitted on " + preview.describe() + "; " + staleness.toDays()
                + " days stale against a horizon of " + previewHorizon.toDays() + " days");
    }

    /**
     * The one case where no preview is required: a kind of policy whose changes do not move
     * recognised income.
     *
     * <p>Asked of {@link com.crisil.eir.policy.PolicyKind#movesRecognisedIncome()} rather than
     * assumed, which is the reason that method exists. <b>No kind answers false in this build</b>
     * — all six do move recognised income — so this branch is currently unreachable, and it is
     * written anyway because the alternative is a hard-coded {@code true} in the gate. A future
     * kind governing, say, a reporting label would then be stuck demanding a portfolio-level
     * preview that nobody can produce for it, and the pressure to resolve that lands on the
     * gate's strictness rather than on the taxonomy.
     */
    private static Optional<ActivationDecision> exemption(PolicyVersion version) {
        if (version.kind().movesRecognisedIncome()) {
            return Optional.empty();
        }
        return Optional.of(ActivationDecision.permit(version.id(),
            "no impact preview required: " + version.kind() + " changes do not move recognised"
                + " income (PolicyKind.movesRecognisedIncome)"));
    }
}
