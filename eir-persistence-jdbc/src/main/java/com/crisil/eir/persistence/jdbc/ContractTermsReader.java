package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.calc.projection.ContractTerms;
import com.crisil.eir.domain.DayCountConvention;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.domain.RateType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;

/**
 * One {@code contract_version} row, as at a boundary, mapped to {@link ContractTerms}.
 *
 * <p>Shared by {@link JdbcContractStateSource} and {@link JdbcOnboardingSource} because both need
 * the same terms and the temporal predicate must be identical in both. Two copies of this query
 * would eventually differ by one {@code >=}, and the two ports would then disagree about which
 * version of a contract a run is reading — a difference that shows up as an onboarding and a
 * month-end close computing different rates for one contract, with both internally consistent.
 *
 * <h2>The storage convention this reader depends on, stated because it is load-bearing</h2>
 *
 * <p>{@code contract_version.contractual_rate} is read as the rate <b>for the compounding period
 * named by {@code compounding_basis}</b>, not as an annual rate. 04 does not spell this out, and the
 * alternative reading is unsafe rather than merely different: {@code ContractTerms} refuses a
 * {@link Rate} whose {@code periodsPerYear} disagrees with the schedule, and its message gives the
 * rule — "interest must be computed from the rate for the period the schedule is built on, never by
 * dividing an annual rate". Reading the column as annual would force this reader either to divide
 * (which that rule forbids) or to wrap it as a periodic rate anyway (which would multiply every
 * contract's interest by the compounding frequency). Reference case 1 stores
 * {@code 0.010000000000} against {@code MONTHLY} — 1% a month — and that is the convention.
 *
 * <h2>Why the anchor join is inner</h2>
 *
 * <p>{@code contract_version_schedule_anchor} (V3) carries {@code disbursement_date},
 * {@code first_due_date} and {@code term_periods}, none of which 04 § 2.3 has a column for. An
 * {@code INNER JOIN} means a contract version with no anchor produces no row, which both callers
 * turn into {@code Optional.empty()} and FR-905 quarantines per contract. A {@code LEFT JOIN} with
 * derived fallbacks would be the alternative, and V3's header says why it is refused: for a
 * commitment, {@code initial_recognition_date} is the ACPIR 23 commitment date and "not the date of
 * first drawdown", so the derivation is wrong for a whole product class and wrong silently.
 */
final class ContractTermsReader {

    /**
     * The joined projection, with both temporal predicates on {@code contract_version}.
     *
     * <p>Binds, in order: the contract id, then {@link Params#businessTime}, then
     * {@link Params#systemTime}.
     *
     * <h3>Why this query is ordered and limited, and why the order is that one</h3>
     *
     * <p><b>The two predicates do not guarantee one row.</b> V1's
     * {@code contract_version_no_overlap_ck} is an exclusion constraint {@code WHERE (superseded_at
     * IS NULL)} — it keeps the engine's <em>current</em> belief unambiguous and says nothing about
     * superseded rows. Nothing in the schema forces one version's {@code superseded_at} to equal its
     * successor's {@code recorded_at}, so a version superseded on 1 July and its replacement
     * recorded on 1 June are both visible to a boundary of 15 June, both valid from the same
     * business date. {@code JdbcContractSource.SELECT_POPULATION} already carries {@code DISTINCT}
     * for exactly this reason.
     *
     * <p>Without an {@code ORDER BY} the three callers all do {@code rs.next() ? read(rs) : null},
     * so the principal, the rate and the schedule would come from whichever row the planner returned
     * first — "a figure that depends on which row the planner returned first", which is the failure
     * {@link TemporalReads} is written to prevent and would have reintroduced one level down.
     *
     * <p><b>Latest belief wins</b>, which is the same rule {@code PolicyVersionRegistry.inForceOn}
     * applies on its own axis: among the versions visible as at the boundary, the one the engine
     * believed <em>most recently</em> by then is the one in force then. {@code version_no} and then
     * {@code contract_version_id} break the remaining ties, so the order is total and the answer is
     * reproducible across executions and across servers (FR-903).
     */
    static final String SELECT_BY_CONTRACT_ID = """
        SELECT c.contract_id,
               c.currency,
               c.entity_id,
               c.initial_recognition_date,
               c.measurement_category,
               c.instrument_class,
               c.sppi_outcome,
               c.sppi_assessed_on,
               c.sppi_approver,
               p.product_id,
               p.projection_strategy,
               cv.contract_version_id,
               cv.version_no,
               cv.valid_from,
               cv.recorded_at,
               cv.principal,
               cv.contractual_rate,
               cv.rate_type,
               cv.day_count_convention,
               cv.compounding_basis,
               cv.moratorium_months,
               cv.capitalises_interest,
               cv.eir_expected_life_months,
               cv.ecl_horizon_months,
               cv.life_divergence_basis,
               a.disbursement_date,
               a.first_due_date,
               a.term_periods,
               a.step_factor,
               a.balloon_amount,
               a.residual_value
          FROM contract c
          JOIN product p
            ON p.product_id = c.product_id
          JOIN contract_version cv
            ON cv.contract_id = c.contract_id
          JOIN contract_version_schedule_anchor a
            ON a.contract_version_id = cv.contract_version_id
         WHERE c.contract_id = ?
           AND %s
           AND %s
         ORDER BY cv.recorded_at DESC, cv.version_no DESC, cv.contract_version_id DESC
         LIMIT 1
        """.formatted(TemporalReads.businessTime("cv"), TemporalReads.systemTime("cv"));

    private ContractTermsReader() {
    }

    /**
     * One contract's terms and the classification attributes that travel with them.
     *
     * @param contractId              the contract
     * @param contractVersionId       which version answered, for the audit trail
     * @param versionNo               the version ordinal, so a replay's evidence can name it
     * @param validFrom               the business date the version became true
     * @param recordedAt              the instant the engine was told — the DT-1 evidence
     * @param currency                from {@code contract.currency}
     * @param entityId                booking entity, the fee rule set's third key component
     * @param productId               the product UUID, the rule set's second key component
     * @param initialRecognitionDate  ACPIR 23 for a commitment; not the drawdown date
     * @param measurementCategory     as the source system declared it, unchecked here
     * @param instrumentClass         which side of FR-104 the contract sits on
     * @param sppiOutcome             {@code PASS} / {@code FAIL} / null
     * @param sppiAssessedOn          null iff {@code sppiOutcome} is null (V1 enforces the triple)
     * @param sppiApprover            null iff {@code sppiOutcome} is null
     * @param compoundingBasis        the raw column, kept for the period-ordinal derivation
     * @param periodsPerYear          derived from it once, so both callers agree
     * @param terms                   the projector's input
     */
    record Row(
        String contractId,
        String contractVersionId,
        int versionNo,
        LocalDate validFrom,
        java.time.Instant recordedAt,
        Currency currency,
        String entityId,
        String productId,
        LocalDate initialRecognitionDate,
        String measurementCategory,
        String instrumentClass,
        String sppiOutcome,
        LocalDate sppiAssessedOn,
        String sppiApprover,
        String compoundingBasis,
        int periodsPerYear,
        ContractTerms terms) {
    }

    /** Maps the current row; the caller has already checked {@code rs.next()}. */
    static Row read(ResultSet rs) throws SQLException {
        Currency currency = Rows.currency(rs, "currency");
        String compoundingBasis = Rows.text(rs, "compounding_basis");
        int periodsPerYear = CompoundingBasis.periodsPerYear(compoundingBasis);

        Money principal = Rows.money(rs, "principal", currency);
        Rate contractualRate = Rows.rate(rs, "contractual_rate", periodsPerYear);
        int termPeriods = Rows.integer(rs, "term_periods");
        LocalDate disbursementDate = Rows.date(rs, "disbursement_date");
        LocalDate firstDueDate = Rows.date(rs, "first_due_date");
        DayCountConvention dayCount = dayCount(Rows.text(rs, "day_count_convention"));
        BigDecimal stepFactor = Rows.decimalOrNull(rs, "step_factor");
        RateType rateType = rateType(Rows.text(rs, "rate_type"));

        Money balloon = Rows.moneyOrNull(rs, "balloon_amount", currency);
        Money residual = Rows.moneyOrNull(rs, "residual_value", currency);

        ContractTerms terms = new ContractTerms(
            principal,
            contractualRate,
            termPeriods,
            periodsPerYear,
            disbursementDate,
            firstDueDate,
            dayCount,
            ProjectionStrategies.shapeFor(Rows.text(rs, "projection_strategy"), stepFactor),
            rateType,
            currency,
            moratoriumPeriods(rs, compoundingBasis, termPeriods, disbursementDate, firstDueDate),
            rs.getBoolean("capitalises_interest"),
            balloon,
            stepFactor,
            residual,
            Rows.integer(rs, "eir_expected_life_months"),
            Rows.integer(rs, "ecl_horizon_months"),
            Rows.textOrNull(rs, "life_divergence_basis"));

        return new Row(
            Rows.text(rs, "contract_id"),
            Rows.text(rs, "contract_version_id"),
            Rows.integer(rs, "version_no"),
            Rows.date(rs, "valid_from"),
            Rows.instant(rs, "recorded_at"),
            currency,
            Rows.text(rs, "entity_id"),
            Rows.text(rs, "product_id"),
            Rows.date(rs, "initial_recognition_date"),
            Rows.text(rs, "measurement_category"),
            Rows.text(rs, "instrument_class"),
            Rows.textOrNull(rs, "sppi_outcome"),
            Rows.dateOrNull(rs, "sppi_assessed_on"),
            Rows.textOrNull(rs, "sppi_approver"),
            compoundingBasis,
            periodsPerYear,
            terms);
    }

    /**
     * {@code moratorium_months} converted to whole compounding periods.
     *
     * <p>Months, not periods, in the schema — so a six-month moratorium on a quarterly schedule is
     * two periods and not six. Six would exceed the term on a short facility and
     * {@code ContractTerms} would refuse the contract outright, which is a confusing way to learn
     * about a units mismatch.
     *
     * <p>Converted by <b>counting the contract's own periods across the moratorium window</b> rather
     * than by dividing months by a months-per-period figure. That is the only conversion available
     * for a weekly or fortnightly schedule, where there is no whole number of months in a period —
     * and it is the more accurate one everywhere else, because it is anchored on real dates: the
     * moratorium runs from the disbursement date, and how many due dates fall inside it is a fact
     * about the calendar rather than a ratio.
     */
    private static int moratoriumPeriods(ResultSet rs, String compoundingBasis, int termPeriods,
        LocalDate disbursementDate, LocalDate firstDueDate) throws SQLException {

        Integer months = Rows.integerOrNull(rs, "moratorium_months");
        if (months == null) {
            return 0;
        }
        // Due dates strictly inside (disbursement, disbursement + months]. elapsedPeriods counts
        // those strictly before its second argument, so the window end is taken one day later.
        long counted = PeriodId.elapsedPeriods(
            firstDueDate,
            disbursementDate.plusMonths(months).plusDays(1),
            CompoundingBasis.stepOf(compoundingBasis));
        int periods = (int) counted;
        if (periods >= termPeriods) {
            // ContractTerms refuses moratoriumPeriods >= termPeriods, and would do so with a
            // message about a range rather than about the contract. Refused here so the message
            // names both figures and the unit they are in.
            throw new ContractDataCondition(
                "moratorium of " + months + " months is " + periods + " periods of a "
                    + termPeriods + "-period schedule; a moratorium covering the whole term leaves"
                    + " no period in which principal is repaid, so the projector has no schedule to"
                    + " build and the contract would be refused rather than reported");
        }
        return periods;
    }

    private static DayCountConvention dayCount(String value) {
        try {
            return DayCountConvention.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "day count convention '" + value + "' is not one of the six V1's"
                    + " contract_version_day_count_ck admits. A day count cannot be defaulted: it"
                    + " decides the year fraction every actual-date discount exponent is built"
                    + " from, and ACT/360 against ACT/365F moves an annual rate by about 1.4%");
        }
    }

    private static RateType rateType(String value) {
        try {
            return RateType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "rate type '" + value + "' is neither FIXED nor FLOATING. FR-507 routes an event on"
                    + " this column together with the event's driver tag and never on an observed"
                    + " rate movement, so an unrecognised value would route a renegotiated fixed"
                    + " rate as a reset — a modification treated as a reprice, with no catch-up and"
                    + " no modification test");
        }
    }
}
