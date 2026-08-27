package com.crisil.eir.gl.posting;

import com.crisil.eir.domain.Money;
import java.util.Objects;

/**
 * A difference between the sub-ledger and a GL control account that has a stated cause (FR-803,
 * control C-13).
 *
 * <p>FR-803 says "zero <b>unexplained</b> difference", and this is the value that makes the word
 * mean something. Without it SL-1 has to be either "zero difference" — red at every close where a
 * suspense posting crossed a cut-off, and therefore suppressed within a quarter — or it has to
 * silently tolerate differences, which is no control at all.
 *
 * <h2>Sign convention</h2>
 *
 * <p>{@link #amount()} is signed in the same direction as the difference it explains, which
 * throughout this package is <b>sub-ledger less GL</b>. So:
 *
 * <ul>
 *   <li>the engine has booked 4,820.55 of interest suspense that the GL takes tomorrow — the
 *       difference is <b>+4,820.55</b> and the explanation is <b>+4,820.55</b>;
 *   <li>somebody put a 1,000.00 debit through the GL by hand — the GL is 1,000.00 higher, the
 *       difference is <b>−1,000.00</b> and the explanation is <b>−1,000.00</b>.
 * </ul>
 *
 * <p>Signed rather than a magnitude plus a direction enum, and that is the opposite choice from
 * {@link com.crisil.eir.gl.journal.JournalLine}. The reason the journal keeps direction in its own
 * field is that a debit and a credit are different postings with the same net; an explanation is not
 * a posting, it is an adjustment to a residual, and a residual is a signed number. Giving it a
 * direction enum would need a second convention mapping that enum onto the residual's sign, and two
 * conventions is how a +4,820.55 explanation ends up applied to a −4,820.55 difference and doubling
 * it.
 *
 * <h2>A nil explanation is refused, and it is not the invariant's condition</h2>
 *
 * <p>An explanation of nothing explains nothing. Refusing it at construction is safe under this
 * repository's rule against guarding the thing an invariant asserts, because SL-1 asserts that the
 * <em>unexplained residual</em> is nil, not that explanations are non-nil: an account with no
 * explanation at all is exactly the input that makes SL-1 fail, and it is reached by supplying no
 * {@code ExplainedDifference}, not by supplying a zero one.
 *
 * @param accountCode the control account the difference sits on
 * @param amount      signed, sub-ledger less GL; non-nil
 * @param cause       the stated cause
 * @param narrative   what happened, in words; mandatory, and mandatory even for {@link
 *                    DifferenceCause#OTHER}
 */
public record ExplainedDifference(
    String accountCode, Money amount, DifferenceCause cause, String narrative) {

    public ExplainedDifference {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(accountCode, "accountCode");
        accountCode = accountCode.strip();
        if (accountCode.isEmpty()) {
            throw new IllegalArgumentException(
                "an explanation with no account code explains a difference on no account");
        }
        narrative = narrative == null ? "" : narrative.strip();
        if (narrative.isEmpty()) {
            // The cause alone is a label. A reviewer signing off a suppressed difference needs the
            // fact behind the label, and C-13 is a control somebody signs.
            throw new IllegalArgumentException(
                "explanation of " + amount.atPresentationScale() + " on " + accountCode
                    + " states cause " + cause + " with no narrative; the cause is a category and"
                    + " the narrative is the fact, and a difference is suppressed on the fact");
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException(
                "explanation on " + accountCode + " is for nil; an explanation of nothing explains"
                    + " nothing. An account with no explanation is what makes SL-1 fail, and that"
                    + " is expressed by filing none, not by filing a nil one");
        }
    }

    /** A timing difference. */
    public static ExplainedDifference timing(
        String accountCode, Money amount, String narrative) {
        return new ExplainedDifference(accountCode, amount, DifferenceCause.TIMING, narrative);
    }

    /** A journal posted directly in the GL, outside the engine. */
    public static ExplainedDifference manualGlJournal(
        String accountCode, Money amount, String narrative) {
        return new ExplainedDifference(
            accountCode, amount, DifferenceCause.MANUAL_GL_JOURNAL, narrative);
    }

    /** Whether this cause is expected to reverse itself next period. */
    public boolean selfReversing() {
        return cause.selfReversing();
    }

    @Override
    public String toString() {
        return accountCode + " " + amount.atPresentationScale() + " [" + cause + "] " + narrative;
    }
}
