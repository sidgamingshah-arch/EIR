package com.crisil.eir.api.modules.policy;

import com.crisil.eir.api.store.Book;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.preview.DraftFingerprint;
import com.crisil.eir.policy.preview.ImpactPreview;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Runs 06 § 5's mandatory impact preview over the book this server holds, and refuses to produce
 * one where the engine cannot honestly quantify the draft.
 *
 * <p><b>What this measures, and what it cannot.</b> 06 § 5 wants a portfolio-level movement: "for
 * a CPR curve change the preview is a catch-up across every affected contract simultaneously, so
 * the number can be material". Producing that number means re-running every affected contract
 * under the draft's own content. A draft submitted through {@code POST /api/policy-versions}
 * carries an id, a kind, a description, an effective date and a maker — and <em>no numeric
 * content</em>. There is therefore nothing for the engine to re-run the book under, and this class
 * says so rather than inventing a movement.
 *
 * <p>Two outcomes follow, and the split is the control:
 *
 * <ul>
 *   <li><b>A draft taking effect after the book's position</b> restates no published period yet.
 *       Its honest preview is <em>nil movement</em> over the measured population — which
 *       {@link ImpactPreview#isNoMovement()} documents as a legitimate answer and, more to the
 *       point, as "a quantified claim that somebody can be held to, which is the entire difference
 *       between it and an absent one".
 *   <li><b>A draft taking effect on or before the book's position</b> would restate figures the
 *       bank has already recognised, and a nil preview of that is the record
 *       {@link com.crisil.eir.policy.preview.ActivationRefusalReason#looksLikeDiligence()} is
 *       named for: a stored preview, against the right version id, with numbers in it, saying
 *       nothing happened. So <b>no preview is produced at all</b>, the approval gate goes on
 *       returning {@code 409}, and the refusal names what is missing. See
 *       {@link Refusal#RETROSPECTIVE_DRAFT_NOT_QUANTIFIED}.
 * </ul>
 *
 * <p>That second branch is the one thing in this class that can fail on a well-formed request, and
 * it is why the preview endpoint is not a rubber stamp: the draft whose approval would move
 * already-published income is exactly the draft this endpoint declines to certify.
 *
 * <p><b>The population is real.</b> {@link Position} is summed off the book's own holdings, so the
 * contract count, the gross carrying amount and the weighted-average EIR in every preview are
 * measured figures, and the weighted-average EIR is carried into the preview as both the "before"
 * and the "after" rate — a nil rate shift stated against a real portfolio rate, rather than two
 * zeroes that would also satisfy {@code weightedAverageEirShiftBps() == 0}.
 */
public final class PortfolioImpactPreview {

    /** Why no preview was produced. One reason, because there is one condition. */
    public enum Refusal {

        /**
         * The draft takes effect on or before the book's position, so it restates recognised
         * figures, and this API carries no draft content to compute the restatement from.
         *
         * <p>A refusal rather than a nil preview, and rather than a thrown exception. Nil would be
         * a false claim stored as evidence; a throw would make a data condition look like a defect
         * and would stop a batch of previews at the first one.
         */
        RETROSPECTIVE_DRAFT_NOT_QUANTIFIED
    }

    /**
     * Either a preview or the reason there is none. Exactly one is present.
     *
     * @param preview the preview to store, or null
     * @param refusal why there is none, or null
     * @param detail  what to tell the caller either way
     */
    public record Outcome(ImpactPreview preview, Refusal refusal, String detail) {

        public Outcome {
            Objects.requireNonNull(detail, "detail");
            if ((preview == null) == (refusal == null)) {
                throw new IllegalStateException(
                    "a preview outcome is either a preview or a refusal, never both and never"
                        + " neither: " + detail);
            }
        }

        /** Whether a preview was produced and may be stored. */
        public boolean produced() {
            return preview != null;
        }
    }

    /**
     * The book position a preview was measured against.
     *
     * @param contractsMeasured    every contract the population names
     * @param openingStatesOnFile  those the contract master actually carries a balance for
     * @param totalGrossCarryingAmount sum of the opening gross carrying amounts on file
     * @param weightedAverageEir   gross-carrying-amount-weighted EIR over the contracts on file
     * @param asOf                 the business date this position is stated at
     */
    public record Position(
        int contractsMeasured,
        int openingStatesOnFile,
        Money totalGrossCarryingAmount,
        Rate weightedAverageEir,
        LocalDate asOf) {

        public Position {
            Objects.requireNonNull(totalGrossCarryingAmount, "totalGrossCarryingAmount");
            Objects.requireNonNull(weightedAverageEir, "weightedAverageEir");
            Objects.requireNonNull(asOf, "asOf");
        }

        /**
         * Sums the book.
         *
         * <p><b>Contracts with no opening state are counted and then excluded from the sums, not
         * dropped.</b> That asymmetry is deliberate and it is FR-905's condition: the seeded book's
         * C-0003 is named by the population and carried by no master, so a preview that reported
         * two contracts would be quietly claiming to have measured a portfolio it could not see all
         * of. Both counts are published, and the difference is the part a checker should ask about.
         */
        public static Position measure(List<Book.Holding> holdings, LocalDate asOf) {
            Objects.requireNonNull(holdings, "holdings");
            Objects.requireNonNull(asOf, "asOf");
            Money total = Money.zero(Money.INR);
            BigDecimal weighted = BigDecimal.ZERO;
            int onFile = 0;
            int periodsPerYear = MONTHLY;
            for (Book.Holding holding : holdings) {
                if (!holding.stateOnFile()) {
                    continue;
                }
                Money gca = holding.state().openingGca();
                Rate eir = holding.state().eir();
                total = total.plus(gca);
                weighted = weighted.add(gca.amount().multiply(eir.periodic(), Precision.WORKING),
                    Precision.WORKING);
                periodsPerYear = eir.periodsPerYear();
                onFile++;
            }
            // A book with nothing on file has no weighted average, and zero is the only answer
            // that is not a fabrication: there is no rate to report. It is distinguishable from a
            // genuine zero rate by openingStatesOnFile, which is published beside it.
            BigDecimal average = total.isZero()
                ? BigDecimal.ZERO
                : weighted.divide(total.amount(), Precision.WORKING);
            return new Position(holdings.size(), onFile, total,
                Rate.periodic(average, periodsPerYear), asOf);
        }
    }

    /** Twelve. Named so the fallback in {@link Position#measure} is not a bare literal. */
    private static final int MONTHLY = 12;

    private final Position position;
    private final Instant generatedAt;

    /**
     * @param position    the book position every preview from this instance measures
     * @param generatedAt the instant every preview is stamped with — see
     *     {@code PolicyVersionsSurface} for why this surface runs on the book's own as-at rather
     *     than on a wall clock
     */
    public PortfolioImpactPreview(Position position, Instant generatedAt) {
        this.position = Objects.requireNonNull(position, "position");
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    }

    /** The book position this instance measures. */
    public Position position() {
        return position;
    }

    /** The instant previews are stamped with. */
    public Instant generatedAt() {
        return generatedAt;
    }

    /**
     * Previews {@code version} against the measured position, or refuses.
     *
     * @param version the draft being previewed
     * @param draft   content identity of what that draft says right now; the preview is bound to
     *                it, so an edit invalidates the preview rather than silently keeping it
     */
    public Outcome previewOf(PolicyVersion version, DraftFingerprint draft) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(draft, "draft");

        if (!version.effectiveFrom().isAfter(position.asOf())) {
            return new Outcome(null, Refusal.RETROSPECTIVE_DRAFT_NOT_QUANTIFIED,
                "version " + version.id() + " takes effect " + version.effectiveFrom()
                    + ", on or before the book position of " + position.asOf()
                    + ", so it restates periods already recognised. This API carries no numeric"
                    + " draft content, so the catch-up across the affected contracts cannot be"
                    + " computed, and a nil preview of a retrospective change is the record the"
                    + " gate exists to reject — it would satisfy a control that asks only whether"
                    + " a preview exists. Nothing is stored, so approval still returns 409"
                    + " (FR-210, 06 § 5)");
        }

        Money nil = Money.zero(position.totalGrossCarryingAmount().currency());
        ImpactPreview preview = new ImpactPreview(
            version.id(),
            draft,
            generatedAt,
            position.asOf(),
            0L,
            nil,
            nil,
            nil,
            position.weightedAverageEir(),
            position.weightedAverageEir());
        return new Outcome(preview, null,
            "version " + version.id() + " takes effect " + version.effectiveFrom()
                + ", after the book position of " + position.asOf() + ", so no recognised period"
                + " moves and the quantified claim is nil movement across the "
                + position.openingStatesOnFile() + " contract(s) measured. A nil preview is a"
                + " claim the maker is held to, not the absence of one — but it is also the"
                + " preview most worth a second look on a BEHAVIOURAL_CURVE version"
                + " (ImpactPreview.isNoMovement)");
    }
}
