package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.onboarding.FeeSubmission;
import com.crisil.eir.application.onboarding.InstrumentClass;
import com.crisil.eir.application.onboarding.MeasurementCategory;
import com.crisil.eir.application.onboarding.OnboardingRequest;
import com.crisil.eir.application.onboarding.OnboardingSource;
import com.crisil.eir.application.onboarding.SppiAssessment;
import com.crisil.eir.application.onboarding.SppiOutcome;
import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.domain.Money;
import com.crisil.eir.policy.tier.TierAssignmentFeature;
import com.crisil.eir.policy.tier.TierAssignmentSegment;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * One contract's initial-recognition input, as at the boundary.
 *
 * <h2>No FVTPL filter here, unlike {@link JdbcContractSource}, and that is the point</h2>
 *
 * <p>{@code MeasurementGate} compares the declared category against the SPPI assessment and
 * concludes; where they disagree the assessment wins and ST-12 breaches. That comparison is only
 * possible if the declared category reaches the gate, so this adapter returns a request for a
 * contract declared FVTPL and lets the gate decide. FR-103's exclusion is from EIR <em>processing</em>
 * and not from the record — {@code OnboardingRequest}'s javadoc makes the same point about terms
 * being mandatory even for a contract that will be excluded, because "the FVTPL branch of the diagram
 * still writes to the database".
 *
 * <h2>The classification attributes are returned as declared, not as corrected</h2>
 *
 * <p>{@code declaredCategory} is {@code contract.measurement_category} verbatim, and
 * {@code sppiAssessment} is the triple V1 keeps whole or absent
 * ({@code contract_sppi_complete_ck}). Neither is reconciled against the other here.
 * {@code OnboardingRequest} states why: "collapsing them into one 'effective category' at the
 * ingestion boundary would make the comparison a field against itself". An adapter that helpfully
 * corrected the category would make ST-12 unfailable and would erase the evidence that a source
 * system had asserted something the assessment contradicts.
 *
 * <h2>Empty rather than an exception, on four absences</h2>
 *
 * <p>No contract, no version visible at the boundary, no schedule anchor (V3), no onboarding
 * attribute row. Each is a data condition {@code InitialRecognition} quarantines under
 * {@code MISSING_MANDATORY_FIELD}, and the port is explicit that this is not a null or a skip: "every
 * id the population names gets an outcome, and {@code OnboardingRun} refuses to be assembled
 * otherwise".
 *
 * <p>The onboarding attribute row (V3) carries the 03 § 10 tier-gate inputs, which 04 § 2.1 does not:
 * the contract table holds the <em>outcome</em> — {@code materiality_tier} and {@code tier_basis} —
 * and a gate cannot re-run on an outcome. Defaulting the segment to, say, {@code WHOLESALE} would
 * change which tier every unattributed contract is measured under, and the tier decides whether
 * approximation is permitted at all.
 */
public final class JdbcOnboardingSource extends JdbcAdapter implements OnboardingSource {

    /**
     * Binds: contract id, {@code businessAsOf}.
     *
     * <p>{@code posted_on <= businessAsOf} is the only temporal predicate available on this table:
     * {@code fee_posting} carries no {@code recorded_at}. So a fee posting loaded after a period
     * closed but dated inside it <em>would</em> be visible to a replay of that period, where a
     * backdated amendment to the terms would not. That asymmetry is a gap in 04 § 2.5 rather than in
     * this adapter, and it is reported rather than hidden behind a column chosen to look like a
     * system-time axis.
     */
    static final String SELECT_FEE_POSTINGS = """
        SELECT fp.fee_code,
               fp.amount,
               fp.posted_on,
               fp.cost_function,
               fp.drawdown_probability
          FROM fee_posting fp
         WHERE fp.contract_id = ?
           AND fp.posted_on <= ?
         ORDER BY fp.posted_on, fp.posting_id
        """;

    /** Binds: contract id, then {@link Params#systemTime}. */
    static final String SELECT_ONBOARDING_ATTRIBUTE = """
        SELECT oa.counterparty_segment,
               oa.tier_features,
               oa.exposure_at_origination
          FROM contract_onboarding_attribute oa
         WHERE oa.contract_id = ?
           AND %s
        """.formatted(TemporalReads.systemTime("oa"));

    public JdbcOnboardingSource(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcOnboardingSource(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public Optional<OnboardingRequest> onboardingRequest(String contractId, AsAtBoundary boundary) {
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(boundary, "boundary");

        try (Connection connection = open()) {
            ContractTermsReader.Row terms = readTerms(connection, contractId, boundary);
            if (terms == null) {
                return Optional.empty();
            }
            Attributes attributes = readAttributes(connection, contractId, boundary,
                terms.currency());
            if (attributes == null) {
                return Optional.empty();
            }

            return Optional.of(new OnboardingRequest(
                terms.contractId(),
                instrumentClass(terms.instrumentClass()),
                measurementCategory(terms.measurementCategory()),
                sppiAssessment(terms),
                terms.initialRecognitionDate(),
                terms.productId(),
                terms.entityId(),
                terms.terms(),
                readFeePostings(connection, contractId, boundary, terms.currency()),
                attributes.segment(),
                attributes.features(),
                attributes.exposure()));

        } catch (ContractDataCondition e) {
            // Same treatment as ContractStateSource.openingState, and for the same reason: an
            // unreadable classification attribute is a data condition InitialRecognition quarantines
            // under MISSING_MANDATORY_FIELD, not a reason to abandon the batch. A genuine
            // PersistenceFailure still propagates.
            return Optional.empty();
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read the onboarding request for contract " + contractId + " as at "
                    + boundary.recordedAsAt(), e);
        }
    }

    private ContractTermsReader.Row readTerms(
        Connection connection, String contractId, AsAtBoundary boundary) throws SQLException {

        try (PreparedStatement statement =
                 connection.prepareStatement(ContractTermsReader.SELECT_BY_CONTRACT_ID)) {
            new Params(statement)
                .contractId(contractId)
                .businessTime(boundary.businessAsOf())
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? ContractTermsReader.read(rs) : null;
            }
        }
    }

    private List<FeeSubmission> readFeePostings(Connection connection, String contractId,
        AsAtBoundary boundary, Currency currency) throws SQLException {

        List<FeeSubmission> submissions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_FEE_POSTINGS)) {
            new Params(statement)
                .contractId(contractId)
                .date(boundary.businessAsOf());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    // Amount and sign both as posted. V1 stores fee_posting.amount "signed as the
                    // contract holder sees it" — a received fee positive, a paid cost negative — and
                    // FeeSubmission's own factories apply abs() or negate() precisely because a feed
                    // may not. Constructed directly here so the stored sign survives: flipping it
                    // would move a cost into the fee stream and raise the EIR instead of lowering
                    // it.
                    submissions.add(new FeeSubmission(
                        Rows.text(rs, "fee_code"),
                        Rows.money(rs, "amount", currency),
                        Rows.date(rs, "posted_on"),
                        Rows.textOrNull(rs, "cost_function"),
                        Rows.decimalOrNull(rs, "drawdown_probability")));
                }
            }
        }
        return List.copyOf(submissions);
    }

    private Attributes readAttributes(Connection connection, String contractId,
        AsAtBoundary boundary, Currency currency) throws SQLException {

        try (PreparedStatement statement =
                 connection.prepareStatement(SELECT_ONBOARDING_ATTRIBUTE)) {
            new Params(statement)
                .contractId(contractId)
                .systemTime(boundary.recordedAsAt());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Attributes(
                    segment(Rows.text(rs, "counterparty_segment")),
                    features(rs.getArray("tier_features")),
                    Rows.moneyOrNull(rs, "exposure_at_origination", currency));
            }
        }
    }

    /**
     * The asserted 03 § 10 features.
     *
     * <p>An unknown name is refused rather than dropped. A dropped feature is a contract that misses
     * a tier rule it should have matched — {@code CARD_OR_KCC_REVOLVER} is matched ahead of both tenor
     * rules, so losing it sends a revolver to a tenor-based tier and then to a projector that "bent
     * into an annuity shape produces a number with no meaning".
     */
    private static Set<TierAssignmentFeature> features(Array array) throws SQLException {
        EnumSet<TierAssignmentFeature> features = EnumSet.noneOf(TierAssignmentFeature.class);
        if (array == null) {
            return features;
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] elements)) {
            throw new PersistenceFailure(
                "contract_onboarding_attribute.tier_features did not read back as an array;"
                    + " the column is TEXT[] and a scalar here means the schema has changed");
        }
        for (Object element : elements) {
            if (element == null) {
                continue;
            }
            String name = element.toString().strip().toUpperCase(Locale.ROOT);
            try {
                features.add(TierAssignmentFeature.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new ContractDataCondition(
                    "tier feature '" + name + "' is not a TierAssignmentFeature. Dropping it would"
                        + " remove a rule the contract should have matched — losing"
                        + " CARD_OR_KCC_REVOLVER, for instance, routes a revolver to a tenor tier"
                        + " and then to an annuity projector, which ScheduleShape calls 'a number"
                        + " with no meaning'");
            }
        }
        return features;
    }

    private static TierAssignmentSegment segment(String value) {
        try {
            return TierAssignmentSegment.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "counterparty segment '" + value + "' is not a TierAssignmentSegment; V3's"
                    + " ck_contract_onboarding_attribute_segment admits the three the enum names."
                    + " The segment decides which tier a contract is measured under, and the tier"
                    + " decides whether approximation is permitted at all");
        }
    }

    private static InstrumentClass instrumentClass(String value) {
        try {
            return InstrumentClass.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "instrument class '" + value + "' is not an InstrumentClass; V1's"
                    + " contract_instrument_class_ck admits the four the enum names. FR-104 puts the"
                    + " SPPI test on the asset side only, so the class decides whether the gate runs"
                    + " at all");
        }
    }

    private static MeasurementCategory measurementCategory(String value) {
        try {
            return MeasurementCategory.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "measurement category '" + value + "' is not a MeasurementCategory; V1's"
                    + " contract_measurement_category_ck admits the three the enum names, and FR-103"
                    + " turns entirely on which of the three a contract is in");
        }
    }

    /**
     * The SPPI triple, or null.
     *
     * <p>V1's {@code contract_sppi_complete_ck} keeps outcome, date and approver "all three or none:
     * an outcome without a date and an owner is not an assessment, it is an assertion". So a partial
     * triple cannot reach here from a schema with the constraint in place — and if it does, it is
     * refused rather than filled in, because a fabricated assessor is the one field an equivalence
     * test would ask about.
     */
    private static SppiAssessment sppiAssessment(ContractTermsReader.Row terms) {
        if (terms.sppiOutcome() == null) {
            return null;
        }
        if (terms.sppiAssessedOn() == null || terms.sppiApprover() == null) {
            throw new ContractDataCondition(
                "contract " + terms.contractId() + " carries an SPPI outcome of "
                    + terms.sppiOutcome() + " with no assessment date or approver. V1's"
                    + " contract_sppi_complete_ck refuses that row: 'an outcome without a date and"
                    + " an owner is not an assessment, it is an assertion'");
        }
        SppiOutcome outcome;
        try {
            outcome = SppiOutcome.valueOf(terms.sppiOutcome().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ContractDataCondition(
                "SPPI outcome '" + terms.sppiOutcome() + "' is neither PASS nor FAIL; FR-104 sends"
                    + " a failure to FVTPL with no bifurcation, so an unreadable outcome cannot be"
                    + " treated as either");
        }
        return new SppiAssessment(outcome, terms.sppiAssessedOn(), terms.sppiApprover());
    }

    private record Attributes(
        TierAssignmentSegment segment, Set<TierAssignmentFeature> features, Money exposure) {
    }
}
