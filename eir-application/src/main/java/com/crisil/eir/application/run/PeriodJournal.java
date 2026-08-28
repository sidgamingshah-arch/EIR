package com.crisil.eir.application.run;

import com.crisil.eir.application.RunRequest;
import com.crisil.eir.calc.amort.AmortisationRow;
import com.crisil.eir.calc.amort.CatchUpResult;
import com.crisil.eir.calc.amort.Stage3Decomposition;
import com.crisil.eir.calc.amort.SuspenseLedger;
import com.crisil.eir.domain.Money;
import com.crisil.eir.gl.journal.DrCr;
import com.crisil.eir.gl.journal.JournalEntry;
import com.crisil.eir.gl.journal.JournalLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One period's postings for one contract, assembled from the figures the inner loop produced
 * (05 § 3.2's "PERIOD_BALANCE + journals", FR-802, 04 § 2.7).
 *
 * <p><b>The entry is built to balance and is not built balanced.</b> {@link JournalEntry}
 * deliberately permits an unbalanced entry to be constructed, because refusing one in a constructor
 * would make invariant SL-2 unfailable — and its javadoc records that this codebase has shipped
 * four controls that could not fail. The job here is therefore to construct entries that balance
 * <em>because the period's figures agree</em>, and to leave SL-2 as the thing that says whether
 * they did. Refusing to build an unbalanced entry here would move that defect rather than remove
 * it: the failure would become an exception in a batch, and what an accountant needs is by how
 * much.
 *
 * <p><b>So each block takes its two sides from two different sources.</b> That is what gives SL-2
 * something to detect:
 *
 * <ul>
 *   <li><b>Accrual block.</b> The debit is {@code AmortisationRow.eirInterest()} — the
 *       roll-forward ledger's own accretion, derived from the supplied flow vector's dates. The
 *       credits total {@code Stage3Decomposition.grossBasisInterest()} — derived from the accrual
 *       length {@link ContractPipeline} reads off the <em>contract's schedule</em>. Two independent
 *       derivations of one quantity, so a mis-dated period flow or a period carrying two accrual
 *       boundaries lands as a measured SL-2 residual instead of as a plausible number.</li>
 *   <li><b>Cash block.</b> The debit is what the cash book applied
 *       ({@code ContractPeriod.cashApplied()}); the credit is what the period's flow vector says
 *       was received ({@code AmortisationRow.cashReceived()}). A receipt posted to the wrong leg,
 *       a missing instalment, or a schedule the engine derived instead of consuming from the LMS
 *       (03 § 5.7's {@code LMS_AUTHORITATIVE} argument) all show up here.</li>
 * </ul>
 *
 * <p><b>Recovered suspended interest credits a different income account from the accrual.</b>
 * {@link #INTEREST_INCOME_ACCRUAL} versus {@link #INTEREST_INCOME_SUSPENSE_RECOVERED}, and the
 * split is load-bearing rather than presentational: S3-2 says recognised interest income on a Stage
 * 3 contract is nil, and {@link ContractPipeline} asserts that against the journal itself. Cash
 * actually received against previously suspended interest <em>is</em> recognised — that is what
 * takes it off the suspense ledger (FR-604) — so posting it to the accrual account would make S3-2
 * fail on a contract behaving exactly as ACPIR requires, and the fix would be to widen S3-2 until
 * it stopped noticing anything.
 *
 * <p><b>Where the ECL unwind is not.</b> Nowhere. Reference case 5's 2,202.72 is a component of the
 * gross-basis accrual, not an addition to it (ST-2 is an identity, not an allocation), and ACPIR
 * says nothing about where the unwind goes — 03 § 7 leaves it to Board-approved policy. It is
 * carried as data on {@link ContractComputation} for the ECL roll-forward and never posted, because
 * posting it would be this file deciding a policy question by implementation detail.
 */
public final class PeriodJournal {

    /**
     * The EIR carrying amount — the accounting overlay, not the borrower's balance.
     *
     * <p>ADR-0004 keeps the CBS authoritative for contractual balances and billing; this engine
     * computes the delta. The chart of accounts is bank-specific, so these codes are the engine's
     * defaults and a deployment maps them: {@link JournalLine} validates an account code for shape
     * and deliberately not against an enumeration this engine has no business holding.
     */
    public static final String GROSS_CARRYING_AMOUNT = "1401-EIR-RECEIVABLE";

    /** The contractual interest receivable the CBS bills and this engine does not own. */
    public static final String CONTRACTUAL_INTEREST_RECEIVABLE = "1402-CONTRACTUAL-INTEREST-RECEIVABLE";

    /** Cash. */
    public static final String CASH = "1101-BANK";

    /** Unamortised integral fee — the two-leg difference of INV-4. */
    public static final String UNAMORTISED_FEE = "2301-UNAMORTISED-FEE";

    /** Interest-in-suspense: a ledger with a balance, not a memorandum note (FR-604). */
    public static final String INTEREST_IN_SUSPENSE = "2401-INTEREST-IN-SUSPENSE";

    /** Interest income from the period's EIR accrual — the account S3-2 is asserted against. */
    public static final String INTEREST_INCOME_ACCRUAL = "4101-INTEREST-INCOME";

    /** Interest income from suspended interest actually received in cash. */
    public static final String INTEREST_INCOME_SUSPENSE_RECOVERED = "4102-INTEREST-INCOME-RECOVERED";

    /** The B5.4.6 catch-up, income or charge (reference case 3's 627.42 charge). */
    public static final String CATCH_UP = "4103-EIR-CATCH-UP";

    private PeriodJournal() {
    }

    /**
     * Assembles the period's entry.
     *
     * @param request       the run, for the stamps every posting carries (04 § 2.13)
     * @param period        the period's movements, for the cash book's own figures
     * @param row           the roll-forward's single row for the period
     * @param decomposition the period's Stage 3 decomposition, at every stage
     * @param suspense      the period's suspense ledger movement
     * @param catchUp       the B5.4.6 restatement, or null where no catch-up ran
     */
    public static JournalEntry of(
        RunRequest request,
        ContractPeriod period,
        AmortisationRow row,
        Stage3Decomposition decomposition,
        SuspenseLedger suspense,
        CatchUpResult catchUp) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(decomposition, "decomposition");
        Objects.requireNonNull(suspense, "suspense");

        List<JournalLine> lines = new ArrayList<>();

        // ---- Accrual block -------------------------------------------------------------------
        // Always present, even at nil. An entry with no lines is refused by JournalEntry — "an
        // entry that posts nothing is not a balanced entry, it is an absent one" — and every
        // contract that computed has to produce one, because the count of entries is what ties to
        // the count of contracts computed when the run's population is reconciled.
        lines.add(posting(
            GROSS_CARRYING_AMOUNT, row.eirInterest(), DrCr.DR,
            "EIR gross-basis accrual for the period, from the roll-forward ledger"));

        Money recognised = decomposition.recognisedIncome();
        Money charged = suspense.chargedToSuspense();
        // The overlay is what the gross accrual does not recognise and does not suspend: on
        // reference case 5's month 13 it is 5,506.79 − 5,298.16 = 208.63, which is the period's
        // integral-fee slice and the other side of INV-4's unamortised fee balance. Derived from
        // the DECOMPOSITION's gross figure, not from the ledger row's, which is precisely why the
        // block can fail to balance and SL-2 has something to report.
        Money overlay = decomposition.grossBasisInterest().minus(recognised).minus(charged);
        addIfMoving(lines, posting(
            INTEREST_INCOME_ACCRUAL, recognised, DrCr.CR,
            "interest income recognised for the period; nil where the stage suppresses recognition"));
        addIfMoving(lines, posting(
            INTEREST_IN_SUSPENSE, charged, DrCr.CR,
            "contractual interest billed and not recognised, charged to suspense (FR-604)"));
        addIfMoving(lines, posting(
            UNAMORTISED_FEE, overlay, DrCr.CR,
            "integral fee accreted through the EIR and neither recognised nor suspended (INV-4)"));

        // ---- Cash block ----------------------------------------------------------------------
        addIfMoving(lines, posting(
            CASH, period.cashApplied(), DrCr.DR,
            "cash received and applied this period, from the cash book"));
        addIfMoving(lines, posting(
            GROSS_CARRYING_AMOUNT, row.cashReceived(), DrCr.CR,
            "cash applied against the carrying amount, from the period's own flow vector"));

        // ---- Suspense recovery ---------------------------------------------------------------
        // Recovered suspended interest leaves the ledger and IS recognised, in its own income
        // account. See the class javadoc for why the account is not the accrual one.
        addIfMoving(lines, posting(
            INTEREST_IN_SUSPENSE, suspense.recovered(), DrCr.DR,
            "suspended interest received in cash, released from suspense"));
        addIfMoving(lines, posting(
            INTEREST_INCOME_SUSPENSE_RECOVERED, suspense.recovered(), DrCr.CR,
            "suspended interest recognised on receipt"));

        // ---- Suspense write-off --------------------------------------------------------------
        // No P&L: the interest was never recognised, so writing it off removes the suspense
        // balance and the contractual receivable it stood against, and nothing reaches income.
        // Booking a charge here would expense income that was never taken.
        addIfMoving(lines, posting(
            INTEREST_IN_SUSPENSE, suspense.writtenOff(), DrCr.DR,
            "suspended interest written off, released from suspense"));
        addIfMoving(lines, posting(
            CONTRACTUAL_INTEREST_RECEIVABLE, suspense.writtenOff(), DrCr.CR,
            "contractual interest receivable written off against suspense, never through P&L"));

        // ---- Catch-up ------------------------------------------------------------------------
        if (catchUp != null) {
            addIfMoving(lines, posting(
                GROSS_CARRYING_AMOUNT, catchUp.catchUp(), DrCr.DR,
                "B5.4.6 restatement of the carrying amount at the retained EIR"));
            addIfMoving(lines, posting(
                CATCH_UP, catchUp.catchUp(), DrCr.CR,
                "B5.4.6 catch-up recognised immediately (reference case 3 books a charge here)"));
        }

        return new JournalEntry(
            period.contractId(), request.periodId(), request.runId(), request.bookId(),
            request.boundary().businessAsOf(), List.copyOf(lines));
    }

    /**
     * The net credit to one account across an entry — credits less debits.
     *
     * <p>Used by {@link ContractPipeline} to assert S3-2 against the artefact that actually reaches
     * the ledger rather than against the field the decomposition set. The distinction is the whole
     * value of the check: {@code Stage3Decomposition} sets {@code recognisedIncome} to zero two
     * lines after deciding recognition is suppressed, so asserting nil against that field is a
     * tautology; asserting it against the journal can fail, and what it catches is a pipeline that
     * posted accrual income on a suppressed contract anyway.
     */
    public static Money netCreditTo(JournalEntry entry, String accountCode) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(accountCode, "accountCode");
        Money net = Money.zero(entry.currency());
        for (JournalLine line : entry.linesFor(accountCode)) {
            net = line.side() == DrCr.CR ? net.plus(line.amount()) : net.minus(line.amount());
        }
        return net;
    }

    /**
     * A posting whose side follows the figure's sign.
     *
     * <p>{@link JournalLine} refuses a negative amount, because "a debit of −100 and a credit of
     * 100 are the same net and different journals, and only one of them reconciles to the account
     * it was meant for". Every figure the inner loop produces is signed — a catch-up is income or a
     * charge, an accrual on a liability runs the other way — so the sign is turned into a side
     * here, once, rather than at eight call sites.
     */
    private static JournalLine posting(
        String accountCode, Money signed, DrCr sideWhenPositive, String narrative) {
        if (signed.isNegative()) {
            return new JournalLine(
                accountCode, sideWhenPositive.opposite(), signed.negate(),
                narrative + " [negative figure posted to the opposite side]");
        }
        return new JournalLine(accountCode, sideWhenPositive, signed, narrative);
    }

    /**
     * Adds a line only where it moves something.
     *
     * <p>Zero-amount lines are dropped rather than posted, so a steady-state performing contract
     * emits four lines and not ten. The one exception is the accrual debit, which is added
     * unconditionally above.
     */
    private static void addIfMoving(List<JournalLine> lines, JournalLine line) {
        if (!line.isZero()) {
            lines.add(line);
        }
    }
}
