package com.crisil.eir.policy.reconciliation;

import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.approval.ApprovalRecord;
import java.util.Objects;

/**
 * One stated, signed-off attribution of part of a contract's CBS difference to a named cause
 * (FR-804, control C-14, invariant RC-1).
 *
 * <p><b>Why "zero unexplained" needs a type at all.</b> RC-1's statement is not "zero difference" —
 * a timing difference with a stated cause is explained and does not breach. That concession is the
 * whole risk in the control: the difference between a reconciliation and a filing cabinet is
 * whether the explanation is a <em>quantity</em> that has to add up, or a note. So an explanation
 * here carries an amount in money, and {@link ContractReconciliation} subtracts it from the
 * difference rather than reading it. An explanation that claims less than the difference leaves a
 * residual; one that claims more leaves a residual in the other direction; one that claims the
 * difference with the wrong sign leaves twice the difference. None of those is a state anybody has
 * to notice — the arithmetic surfaces all three.
 *
 * <p><b>Nothing here is refused at construction, and that is deliberate.</b> This type must be
 * able to hold every explanation a real close produces, including the bad ones: a blank narrative,
 * no named preparer, no approval, an approval by the preparer themselves. Each of those is exactly
 * the input RC-1 exists to catch, and a constructor that refused them would move the detection
 * from a reported control to an exception thrown at ingestion — where the row is discarded and the
 * difference it claimed to explain goes back to being simply unexplained, with nobody told which
 * of the two happened. {@code JournalEntry} makes the same choice for SL-2 and states the same
 * reason: the guard must not be the thing the invariant asserts.
 *
 * <p><b>Why an approval, and why four eyes.</b> RC-1 gates the close (07 § 4.3 item 4), so an
 * explanation is an exception acceptance that permits a close — which 07 § 4.2 lists among the
 * artefacts requiring a maker, a checker and an effective date. The comparison is
 * {@link FourEyes#isSelfApproval}, not a fourth local copy of the rule: {@code ApprovalRecord}'s
 * own javadoc records that the plausible route to self-approval in a bank is not somebody typing
 * their own name but a whitespace or case variant of the same directory identity arriving through
 * a second channel, and an explanation row uploaded from a spreadsheet is exactly that second
 * channel.
 *
 * @param contractId the contract this attributes a difference on; the CBS account reference that
 *                   {@code contract.source_system_ref} carries (V1 § CONTRACT), because that is
 *                   the join key both sides of this reconciliation share
 * @param reason     the stated cause, from the closed list
 * @param amount     how much of the difference this claims to explain, signed the same way as
 *                   {@link ContractReconciliation#difference()} — positive where the engine's
 *                   contractual interest exceeds the CBS's billed interest
 * @param narrative  what was relied on; blank makes the explanation ineffective rather than
 *                   unconstructable
 * @param statedBy   who prepared it; blank makes it ineffective
 * @param approval   the checker's sign-off, or {@code null} for one still in flight
 */
public record DifferenceExplanation(
    String contractId,
    int periodId,
    DifferenceReason reason,
    Money amount,
    String narrative,
    String statedBy,
    ApprovalRecord approval) {

    public DifferenceExplanation {
        // contractId, reason and amount are the identity and the quantity: without all three the
        // row cannot be placed against a difference or subtracted from one, so their absence is a
        // caller defect rather than a fact about the book. The four fields RC-1 judges — narrative,
        // preparer, approval and whether the approval is the preparer — are all nullable or
        // blankable on purpose. See the class javadoc.
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(reason, "reason");
        // periodId is as much identity as contractId, and its absence was a defect. The
        // aggregator validates BOTH figure sides against the period it is reconciling — "a
        // comparison across two periods produces a difference that looks plausible and reconciles
        // to nothing" — and then applied whatever explanations it was handed with no period at
        // all, on the one input that can make the deviation SMALLER. An explanation approved in
        // 2019 closed a 2027 difference. It is worse for BILLING_DAY_TIMING, which this package
        // documents as self-reversing: a carried-forward timing explanation is guaranteed to have
        // the wrong sign next period.
        ContractualLegInterest.requirePeriodId(periodId);
        Objects.requireNonNull(amount, "amount");
        contractId = contractId.strip();
        if (contractId.isEmpty()) {
            throw new IllegalArgumentException(
                "an explanation names no contract; it cannot be placed against a difference");
        }
        narrative = narrative == null ? "" : narrative.strip();
        statedBy = statedBy == null ? "" : statedBy.strip();
    }

    /**
     * An explanation prepared and signed off — the normal, effective shape.
     */
    public static DifferenceExplanation approved(
        String contractId,
        int periodId,
        DifferenceReason reason,
        Money amount,
        String narrative,
        String statedBy,
        ApprovalRecord approval) {
        return new DifferenceExplanation(
            contractId, periodId, reason, amount, narrative, statedBy,
            Objects.requireNonNull(approval, "approval"));
    }

    /**
     * An explanation prepared and not yet signed off.
     *
     * <p>Named rather than left to a {@code null} argument because a preparation in flight is a
     * normal state at any point during a close, and it needs to be constructible without reading
     * like an omission. It is not effective, so the difference it describes stays in RC-1's
     * deviation until somebody signs it — which is the intended pressure.
     */
    public static DifferenceExplanation prepared(
        String contractId,
        int periodId,
        DifferenceReason reason,
        Money amount,
        String narrative,
        String statedBy) {
        return new DifferenceExplanation(
            contractId, periodId, reason, amount, narrative, statedBy, null);
    }

    /**
     * Whether this explanation is entitled to reduce the unexplained difference.
     *
     * <p>Four conditions, and each corresponds to a way an explanation fails to be one: it says
     * nothing ({@code narrative} blank), nobody prepared it ({@code statedBy} blank), nobody
     * checked it ({@code approval} absent), or the person who prepared it checked it
     * ({@link FourEyes#isSelfApproval}). The amount is not among them — an explanation whose
     * amount is wrong is effective and simply fails to close the difference, which is a louder
     * finding than being ignored.
     */
    public boolean isEffective() {
        return ineffectiveBecause() == null;
    }

    /**
     * Why this explanation cannot reduce the difference, or {@code null} where it can.
     *
     * <p>The string goes into RC-1's detail. "Unexplained because nobody signed it" and
     * "unexplained because nobody looked at it" send whoever is clearing the break to two
     * different places, and a deviation figure alone distinguishes neither.
     */
    public String ineffectiveBecause() {
        if (narrative.isEmpty()) {
            return "no narrative: a reason code with no statement of what was relied on is a"
                + " category, not an explanation";
        }
        if (statedBy.isEmpty()) {
            return "no preparer named";
        }
        if (approval == null) {
            return "not approved: RC-1 gates the close, so an explanation permitting one needs a"
                + " checker (07 section 4.2)";
        }
        if (FourEyes.isSelfApproval(statedBy, approval.checker())) {
            return "self-approved by " + statedBy + ": an explanation checked by its own preparer"
                + " is one person's assertion, not four eyes";
        }
        return null;
    }

    /** Whether the preparer signed off their own explanation. */
    public boolean isSelfApproved() {
        return approval != null && FourEyes.isSelfApproval(statedBy, approval.checker());
    }

    /** The amount this claims, at presentation scale, for a report line. */
    public Money presentedAmount() {
        return amount.atPresentationScale();
    }

    /** A one-line audit sentence. */
    public String describe() {
        String head = contractId + ": " + presentedAmount() + " — " + reason.statement();
        String tail = isEffective()
            ? " [" + statedBy + ", " + approval.describe() + "]"
            : " [INEFFECTIVE: " + ineffectiveBecause() + "]";
        return head + tail;
    }

    /**
     * The identity of this explanation as a filed row, for duplicate detection.
     *
     * <p>Every field, because a genuine second explanation on one contract differs in at least
     * one of them — "several per contract is normal" is true, and two rows identical in reason,
     * amount, narrative, preparer and approval are not two explanations, they are one filed twice.
     * That matters because this is the input that <em>reduces</em> a difference: the aggregator
     * refuses a duplicate engine line and a duplicate CBS line with careful reasoning, and had no
     * guard at all on the third collection. A close spreadsheet re-uploaded — which this package's
     * own four-eyes argument names as the threat model — doubled every claim in the book.
     */
    public String identityKey() {
        return contractId + "|" + periodId + "|" + reason + "|" + amount.amount().toPlainString()
            + "|" + narrative + "|" + statedBy
            + "|" + (approval == null ? "-" : approval.checker() + "@" + approval.checkedOn());
    }

    /** Whether this explanation was filed for {@code period}. */
    public boolean appliesToPeriod(int period) {
        return periodId == period;
    }
}
