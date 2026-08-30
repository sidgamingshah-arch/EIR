package com.crisil.eir.persistence.jdbc;

import com.crisil.eir.application.port.AsAtBoundary;
import com.crisil.eir.application.port.PolicySource;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.registry.PolicyVersionRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The policy versions the boundary can see, assembled into a {@link PolicyVersionRegistry}.
 *
 * <h2>The decision-time filter is the whole substance of this adapter</h2>
 *
 * <p>{@code policy_version} has no {@code recorded_at} / {@code superseded_at} pair. Its system-time
 * axis is {@code approved_at}, and V2 constrains the table so that this works: nothing reaches
 * {@code APPROVED}, {@code EFFECTIVE} or {@code SUPERSEDED} without "a checker, an approval timestamp
 * and a stored impact preview" ({@code ck_policy_version_approval_complete}). So "which readings did
 * the engine have on this date" is answerable as {@code approved_at <= recordedAsAt}, and 05 § 3.3
 * requires exactly that: a replay reads "the policy and rule-set versions effective then".
 *
 * <p>Without the filter, a routing-table amendment approved this morning would enter the replay of
 * every closed period. Every figure would be internally consistent, none would reproduce, and DT-1
 * would fire with no way to say which input drifted — which is the failure the {@code port} package
 * javadoc describes in full.
 *
 * <h2>Business time is left to the registry, on purpose</h2>
 *
 * <p>This query does <b>not</b> filter on {@code effective_from <= businessAsOf}, and that is a
 * deliberate division of labour rather than an omission. {@code PolicyVersionRegistry.inForceOn} owns
 * the business-time question and answers it under latest-wins, which its own javadoc explains needs
 * no end-date column. Pre-filtering here would give the registry a truncated history and quietly
 * break two of its other answers: {@code historyOf} would omit versions, and
 * {@code supersessionCoherentFor} — whose job is to report a set that is answerable but wrong — would
 * report coherence it had not actually checked.
 *
 * <p>{@code DRAFT} and {@code PENDING_APPROVAL} are excluded, which V2 states as the rule on its own
 * exclusion constraint: "drafts and rejected submissions may overlap freely, because nothing resolves
 * against them". {@code SUPERSEDED} is included, and V2 gives the reason: "a closed period still
 * resolves against a superseded version (invariant DT-1)".
 *
 * <h2>{@code approved_at} is narrowed to a date at UTC, and the zone is named rather than inherited</h2>
 *
 * <p>{@code PolicyVersion.approvedOn} is a {@link LocalDate}; the column is {@code TIMESTAMPTZ}. Any
 * conversion needs a zone, and taking the JVM default would make {@code isRetrospective()} answer
 * differently on two servers for a version approved near midnight — with the approval date being the
 * evidence that a retrospective restatement was noticed. UTC is chosen because it is the only zone
 * that does not vary with configuration, and it is named here so the convention is on the record
 * rather than implied.
 */
public final class JdbcPolicySource extends JdbcAdapter implements PolicySource {

    /** Binds: {@code recordedAsAt}. */
    static final String SELECT_VISIBLE_VERSIONS = """
        SELECT pv.policy_version_id,
               pv.policy_kind,
               pv.version_label,
               pv.description,
               pv.effective_from,
               pv.status,
               pv.maker,
               pv.checker,
               pv.approved_at
          FROM policy_version pv
         WHERE pv.status IN ('APPROVED', 'EFFECTIVE', 'SUPERSEDED')
           AND pv.approved_at <= ?
         ORDER BY pv.policy_kind, pv.effective_from, pv.policy_version_id
        """;

    public JdbcPolicySource(DataSource dataSource) {
        this(dataSource, DEFAULT_BOOK);
    }

    public JdbcPolicySource(DataSource dataSource, String bookId) {
        super(dataSource, bookId);
    }

    @Override
    public PolicyVersionRegistry policyVersions(AsAtBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");

        List<PolicyVersion> versions = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement =
                 connection.prepareStatement(SELECT_VISIBLE_VERSIONS)) {

            new Params(statement).instant(boundary.recordedAsAt());

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    versions.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceFailure(
                "could not read the policy version set as at " + boundary.recordedAsAt(), e);
        }
        // Not wrapped: PolicyVersionRegistry.of refuses two approved versions of one kind sharing an
        // effective date, and an id naming two versions. Both are configuration defects rather than
        // data conditions — "whichever we found first is not an accounting answer" — so the
        // IllegalArgumentException it raises is the right outcome and its message is better than
        // anything this class could add.
        return PolicyVersionRegistry.of(versions);
    }

    private static PolicyVersion read(ResultSet rs) throws SQLException {
        String id = Rows.text(rs, "policy_version_id");
        String description = Rows.textOrNull(rs, "description");
        if (description == null) {
            // V2 leaves description nullable; PolicyVersion refuses a blank one, because "an
            // unexplained version is not an audit trail". Refused here with the id named rather
            // than substituting version_label: synthesising a description would manufacture
            // exactly the audit trail that refusal exists to require, and every report downstream
            // would show a plausible sentence nobody wrote.
            throw new PersistenceFailure(
                "policy version " + id + " has no description. PolicyVersion refuses a blank one"
                    + " ('an unexplained version is not an audit trail'), and substituting the"
                    + " version label here would manufacture the audit trail rather than record"
                    + " one. Populate policy_version.description");
        }
        return new PolicyVersion(
            id,
            kind(Rows.text(rs, "policy_kind"), id),
            description,
            Rows.date(rs, "effective_from"),
            Rows.text(rs, "maker"),
            Rows.textOrNull(rs, "checker"),
            approvedOn(Rows.instantOrNull(rs, "approved_at")),
            status(Rows.text(rs, "status"), id));
    }

    private static LocalDate approvedOn(Instant approvedAt) {
        return approvedAt == null ? null : approvedAt.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static PolicyKind kind(String value, String id) {
        try {
            return PolicyKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PersistenceFailure(
                "policy version " + id + " has kind '" + value + "', which is not a PolicyKind."
                    + " V2's ck_policy_version_kind admits six values and takes them verbatim from"
                    + " the enum; a seventh in the table means the two vocabularies have diverged,"
                    + " and resolving policy under a kind the engine does not model would silently"
                    + " apply no policy at all");
        }
    }

    private static PolicyVersionStatus status(String value, String id) {
        try {
            return PolicyVersionStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PersistenceFailure(
                "policy version " + id + " has status '" + value + "', which is not a"
                    + " PolicyVersionStatus. The query already restricts to the three operative"
                    + " states, so an unmatched value means V2's ck_policy_version_status has been"
                    + " widened without the enum following");
        }
    }
}
