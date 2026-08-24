package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.InvariantId;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The positive assertion behind invariant PC-1.
 *
 * <p>Under RBI's 2023 framework penal amounts are <em>charges</em>, not penal
 * interest: not capitalised, bearing no further interest, and so unable to enter
 * an EIR cash-flow stream or the gross carrying amount at all. The specification
 * requires that exclusion to be <b>asserted positively each period</b> rather
 * than assumed, and the reason is specific: legacy core banking systems routinely
 * book penal amounts into the interest ledger, so the engine has to prove the
 * exclusion held rather than trust that it did.
 *
 * <p>Rejecting a {@link FeePosting} classified {@code EXCLUDED_BY_DIRECTION}
 * covers the fee route only. It does not cover the
 * {@code LMS_AUTHORITATIVE} route, where a billed instalment arrives as a single
 * net amount with no components and no classification — and that is the route the
 * specification marks strongly preferred in production. A penal charge already
 * folded into a billed interest line by the source system is invisible to a
 * classification check that never sees it.
 *
 * <p>So an externally-supplied schedule cannot support PC-1 on its own. It needs
 * an {@link Attestation} from whoever screened the feed. Absent one, PC-1 is
 * reported <b>unsatisfied</b> — not thrown. That is deliberate: an unattested
 * feed is a control gap to surface at the period close, not a reason to abort a
 * projection that is otherwise arithmetically sound.
 */
public final class PenalChargeScreen {

    private PenalChargeScreen() {
    }

    /**
     * A declaration that a billed feed was screened for amounts excluded by
     * Direction, and what was removed.
     *
     * @param sourceSystem   the feed screened
     * @param screenedBy     who screened it — a person or a control identifier
     * @param screenedOn     when. An input, never a clock read.
     * @param amountRemoved  the excluded total stripped before the feed was handed
     *     over. Zero is a legitimate and common answer; it is not the same as
     *     "nobody looked", which is the absence of an attestation.
     */
    public record Attestation(
        String sourceSystem, String screenedBy, LocalDate screenedOn, Money amountRemoved) {

        public Attestation {
            Objects.requireNonNull(sourceSystem, "sourceSystem");
            Objects.requireNonNull(screenedBy, "screenedBy");
            Objects.requireNonNull(screenedOn, "screenedOn");
            Objects.requireNonNull(amountRemoved, "amountRemoved");
            if (sourceSystem.isBlank()) {
                throw new IllegalArgumentException("sourceSystem must name the feed screened");
            }
            if (screenedBy.isBlank()) {
                throw new IllegalArgumentException("screenedBy must identify the screener");
            }
            if (amountRemoved.isNegative()) {
                throw new IllegalArgumentException(
                    "amountRemoved must not be negative, got " + amountRemoved);
            }
        }
    }

    /**
     * PC-1 over a classified fee set: satisfied when no posting is excluded by
     * Direction.
     *
     * <p>{@link FeePosting} already refuses such a posting at construction, so
     * reaching here with one is not expected. The assertion is emitted anyway,
     * because a control that only fires when something is already impossible is
     * not evidence that the exclusion held — and PC-1 has to be evidence.
     */
    public static InvariantResult overFeePostings(List<FeePosting> fees) {
        Objects.requireNonNull(fees, "fees");
        int excluded = 0;
        for (FeePosting fee : fees) {
            if (fee.classification() == FeeClassification.EXCLUDED_BY_DIRECTION) {
                excluded++;
            }
        }
        if (excluded == 0) {
            return InvariantResult.pass(
                InvariantId.PC_1,
                "no posting classified EXCLUDED_BY_DIRECTION entered the projection ("
                    + fees.size() + " posting(s) screened)");
        }
        return InvariantResult.fail(
            InvariantId.PC_1,
            excluded + " of " + fees.size() + " postings are classified EXCLUDED_BY_DIRECTION and"
                + " must not enter any EIR stream or the gross carrying amount",
            BigDecimal.valueOf(excluded));
    }

    /**
     * PC-1 over an externally-supplied billed schedule.
     *
     * <p>Satisfied only where an {@link Attestation} accompanies the feed. A
     * billed line carries a net amount and no components, so there is nothing here
     * to inspect — the assertion rests on the screening having happened upstream,
     * and its absence is the finding.
     */
    public static InvariantResult overBilledSchedule(
        String sourceLabel, int lineCount, Attestation attestation) {
        if (attestation == null) {
            return InvariantResult.fail(
                InvariantId.PC_1,
                "externally-supplied schedule (" + sourceLabel + ", " + lineCount + " line(s)) carries"
                    + " no penal-charge attestation. A billed line arrives as a net amount with no"
                    + " components, so a penal amount already folded into it by the source system"
                    + " cannot be detected here. PC-1 requires the exclusion to be asserted, and"
                    + " nothing in this feed asserts it.",
                BigDecimal.valueOf(lineCount));
        }
        return InvariantResult.pass(
            InvariantId.PC_1,
            "schedule from " + attestation.sourceSystem() + " screened by " + attestation.screenedBy()
                + " on " + attestation.screenedOn() + "; excluded amount removed "
                + attestation.amountRemoved() + " (" + lineCount + " line(s))");
    }
}
