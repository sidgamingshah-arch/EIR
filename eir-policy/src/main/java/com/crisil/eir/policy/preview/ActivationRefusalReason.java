package com.crisil.eir.policy.preview;

/**
 * Why the impact-preview gate refused to let a policy version become {@code EFFECTIVE}.
 *
 * <p>Seven reasons rather than one boolean, because the seven call for different responses and
 * only one of them is fixed by running the preview. {@link #NO_PREVIEW_STORED} says do the work;
 * {@link #STALE_DRAFT_PREVIEW} says the work was done and then invalidated by an edit;
 * {@link #INCOHERENT_PREVIEW} says the work was done wrong and the figures cannot be trusted at
 * all. A single "no preview" refusal would present the second and third as the first, and the
 * response to the first — run a preview — leaves the third's aggregation defect in place to
 * produce another wrong one.
 *
 * <p><b>Six of the seven describe a stored record.</b> That is the whole point of
 * {@link #looksLikeDiligence()}: an absent preview is a visible gap, whereas a superseded,
 * back-dated or self-contradictory one satisfies any control that asks whether a preview exists
 * and reads in the audit file as evidence somebody quantified the change. The refusals that
 * matter are the ones that fire on records which look fine.
 */
public enum ActivationRefusalReason {

    /**
     * No preview at all for this version id.
     *
     * <p>The honest failure. Nothing to mistake for diligence, and the response is unambiguous:
     * run the preview (06 § 5, {@code POST /policy-versions/{id}/impact-preview}).
     */
    NO_PREVIEW_STORED(false),

    /**
     * A preview was supplied, but it previews a different version id.
     *
     * <p>Reads as diligence and is not even about this change. Arises where a caller carries a
     * preview across versions — a copied approval packet, a re-used draft — and the figures then
     * describe some other policy's effect on the book.
     */
    PREVIEW_FOR_A_DIFFERENT_VERSION(true),

    /**
     * A preview exists for this version id, but was computed against an earlier draft of it.
     *
     * <p><b>The reason this gate is written as a gate and not a record.</b> The version id is
     * stable across draft edits by design, so a preview keyed on the id alone goes on looking
     * current after the draft it measured has been replaced. It is worse than
     * {@link #NO_PREVIEW_STORED}: an absent preview stops the version and shows the gap, while a
     * superseded one stops nothing on a checkbox control and is filed as evidence of exactly the
     * diligence that did not happen.
     *
     * <p>Refused with no tolerance band, because there is no small edit whose effect on the
     * figures is small. 03 § 3.6: compressing assumed life from 240 months to 96 multiplies
     * year-one net fee recognition by 3.73x, from 2,525.04 to 9,410.03 on one exposure — a
     * one-line change to a curve, and a preview taken before it is wrong by a factor of nearly
     * four.
     */
    STALE_DRAFT_PREVIEW(true),

    /**
     * The preview matches the current draft but was generated too long ago.
     *
     * <p>The second staleness axis, and it is the portfolio rather than the draft. Every figure
     * in a preview is a portfolio aggregate; the book originates, amortises and closes
     * underneath it. Past the horizon the preview describes a book that is not the one about to
     * be restated, and its materiality conclusion is not transferable. See
     * {@link ActivationGate#DEFAULT_PREVIEW_HORIZON}.
     *
     * <p>Measured from the older of the generation instant and the book position the preview
     * reports ({@link ImpactPreview#stalenessAt}), because those come apart: a preview re-run
     * this morning against a year-old portfolio extract is minutes old and a year stale, and the
     * generation instant alone would wave it through.
     */
    PREVIEW_OLDER_THAN_HORIZON(true),

    /**
     * The preview claims to have been generated after the activation instant.
     *
     * <p>Not pedantry about clocks. FR-210 requires the preview to be generated <em>before</em>
     * the version can go effective, and a preview stamped in the future either came off a skewed
     * clock — in which case its age cannot be assessed either — or was written after the fact.
     * Both make it inadmissible as evidence that the change was quantified beforehand.
     */
    PREVIEW_POSTDATES_ACTIVATION(true),

    /**
     * The preview was generated after the version was approved.
     *
     * <p>07 § 4.2 requires "a stored impact preview generated <em>before</em> approval". A
     * preview run afterwards inverts the control: the checker signed off on nothing, and the
     * figures were produced to accompany a decision already taken. The ordering is what makes
     * the preview a gate rather than a filing requirement.
     */
    PREVIEW_POSTDATES_APPROVAL(true),

    /**
     * The preview's own figures contradict each other.
     *
     * <p>See {@link ImpactPreview#incoherences()}. A preview whose portfolio total cannot be
     * reconciled with its own contract count or its own largest single movement is not a
     * conservative preview or a rough one — it is a preview whose aggregation is broken, and
     * nothing in it can be relied on, including the parts that look plausible.
     */
    INCOHERENT_PREVIEW(true);

    private final boolean looksLikeDiligence;

    ActivationRefusalReason(boolean looksLikeDiligence) {
        this.looksLikeDiligence = looksLikeDiligence;
    }

    /**
     * Whether this refusal describes a stored record that would satisfy a control asking merely
     * whether a preview exists.
     *
     * <p>Published rather than private because it is the distinction the gate exists to draw,
     * and because it is what a control report should escalate on: a
     * {@link #NO_PREVIEW_STORED} refusal is a version somebody has not finished, while any of
     * the other six is a version somebody believes is ready and an audit file that would read as
     * complete.
     */
    public boolean looksLikeDiligence() {
        return looksLikeDiligence;
    }

    /**
     * Whether this reason blocks the move to {@code EFFECTIVE}. All seven do.
     *
     * <p>A method rather than an omitted concept, for the same reason
     * {@code PolicyKind.movesRecognisedIncome()} is a method: the next reason added should have
     * to answer the question rather than inherit an answer. There is deliberately no advisory
     * tier here — 06 § 5 returns {@code 409}, not a warning header, and a preview gate with a
     * soft setting is a preview gate that gets set soft.
     */
    public boolean blocksActivation() {
        return true;
    }
}
