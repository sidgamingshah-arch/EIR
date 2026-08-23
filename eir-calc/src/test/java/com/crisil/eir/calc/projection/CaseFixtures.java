package com.crisil.eir.calc.projection;

import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.FeeClassification;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The reference case 1 term loan, and the pieces the other shapes vary from it.
 *
 * <p>Case 1 is the baseline the fixtures build on — 1,000,000 at 12% p.a. nominal
 * with monthly compounding over 24 EMIs, a 15,000 processing fee received and a
 * 10,000 DSA commission paid — so it is defined once here rather than restated in
 * every test. Dates are explicit constants: nothing in the calculation path may read
 * a clock, so a fixture that derived a disbursement date from today would be
 * asserting a moving target.
 *
 * <p>The day count is 30/360 bond basis, which is the Indian term-loan convention
 * (calculation specification 3.9). It matters only where a vector falls back to
 * actual dating; on a clean monthly schedule the periodic index is exactly
 * equivalent and the day count never gets used.
 */
final class CaseFixtures {

    static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 1);

    /** One whole period after disbursement — the condition periodic indexing needs. */
    static final LocalDate FIRST_DUE = LocalDate.of(2026, 5, 1);

    /** 12% p.a. nominal, monthly compounding: 1% per month, never 12% divided by 12. */
    static final Rate ONE_PERCENT_MONTHLY = Rate.monthly(new BigDecimal("0.01"));

    private CaseFixtures() {
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** The Case 1 loan. */
    static ContractTerms case1() {
        return annuity(24);
    }

    static ContractTerms annuity(int termPeriods) {
        return shaped(ScheduleShape.ANNUITY_EMI, termPeriods, RateType.FIXED);
    }

    static ContractTerms shaped(ScheduleShape shape, int termPeriods, RateType rateType) {
        return ContractTerms.of(Money.inr("1000000"), ONE_PERCENT_MONTHLY, termPeriods, 12,
            DISBURSEMENT, FIRST_DUE, DayCountConvention.THIRTY_360_BOND, shape, rateType);
    }

    /**
     * The Case 1 fee set: 15,000 of processing fee received (ACPIR 52) against 10,000
     * of DSA commission paid (ACPIR 53), netting to 5,000 of integral fee income.
     *
     * <p>The commission carries {@code SELLING} because that is the ACPIR 53 dividing
     * line — a selling-agent incentive capitalises and internal credit-appraisal cost
     * does not — and the posting cannot be constructed without it.
     */
    static List<FeePosting> case1Fees() {
        return List.of(
            FeePosting.received("PROCESSING_FEE", Money.inr("15000"), DISBURSEMENT,
                FeeClassification.INTEGRAL),
            FeePosting.paid("DSA_COMMISSION", Money.inr("10000"), DISBURSEMENT,
                FeeClassification.INTEGRAL, "SELLING"));
    }

    /** A single integral fee received, for the shapes where the net fee is all that matters. */
    static List<FeePosting> feeReceived(String amount) {
        return List.of(FeePosting.received("PROCESSING_FEE", Money.inr(amount), DISBURSEMENT,
            FeeClassification.INTEGRAL));
    }
}
