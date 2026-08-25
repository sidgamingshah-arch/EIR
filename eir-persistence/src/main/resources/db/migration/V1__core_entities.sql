-- =====================================================================================
-- V1 — core entities
--
-- The eight tables of docs/04-data-model.md sections 2.1 to 2.6: the contract identity,
-- its product taxonomy, its versioned terms, the two cash-flow schedules those terms
-- produce, the fee postings and the versioned rule set that classifies them, and the
-- EIR computation that is the audit anchor.
--
-- The organising principle of 04 is that the engine stores the inputs and the policy
-- version alongside every result, so that any published figure can be reproduced years
-- later under the reading then in force. Everything below serves that, and it is the
-- reason several columns that look redundant are not: policy_version_id and
-- rule_set_version_id on a solve, both annual forms of a rate, a rule set version on
-- every fee posting.
--
-- Engine: PostgreSQL 16 or later (04 section 4). PG16 features used deliberately —
-- UNIQUE NULLS NOT DISTINCT for wildcard rule-set keys, and a GiST exclusion constraint
-- for bitemporal non-overlap.
--
-- ---------------------------------------------------------------------------------
-- Physical conventions (04 section 4). These are not style; each prevents a defect.
-- ---------------------------------------------------------------------------------
--
--   Money           NUMERIC(24,6). Six decimals holds working values above presentation
--                   scale. A money column that came out NUMERIC(24,2) parses without
--                   complaint and silently truncates every working value that passes
--                   through it, which is unrecoverable once a period has closed.
--
--   Rates           NUMERIC(20,12). Twelve decimal places is the storage policy of
--                   03 section 1 exactly: the rate that is persisted is the rate that
--                   every downstream period must use (FR-404). Storing fewer places
--                   than the solver settled on makes the amortisation unreproducible
--                   from the published rate.
--
--   Never           float, double precision, real, or money. The first three are binary
--                   floating point, banned by ADR-0002 because they cannot represent
--                   0.01 and the error accumulates over 360 compounding periods into a
--                   reconciliation break with no single diagnosable cause. PostgreSQL's
--                   money type is worse for this purpose than either: its scale is
--                   fixed by a run-time locale setting, so the same DDL means different
--                   things on two servers.
--
--   Counts, months  Exact integers, never NUMERIC. Every NUMERIC in this file is
--   and ordinals    therefore either (24,6) or (20,12), which makes "no column drifted
--                   off the two scales" a property a test can assert over the text.
--
--   Identifiers     UUID supplied by the caller, with no DEFAULT. gen_random_uuid() and
--                   a sequence are both non-deterministic, and a replay of a closed
--                   period must be able to reproduce the same keys for the same inputs
--                   (FR-903). Key generation is therefore the engine's business, where
--                   it can be made a function of the inputs.
--
--   Enumerations    TEXT with a CHECK against a closed value list, rather than a
--                   PostgreSQL enum type. Two reasons: a CHECK violation names the
--                   column and the offending value, and widening a list is one ALTER
--                   rather than an ALTER TYPE that cannot run inside some transactions.
--                   Value lists are kept identical to the eir-domain enums they mirror,
--                   so that a value crossing the boundary needs no translation table.
--
--   Partitioning    CASHFLOW_LINE is range-partitioned by period_id. Partition pruning
--                   is what keeps close-period queries tractable at 10M contracts.
--
--   Indexing        (contract_id, period_id) is the trace path — the query an auditor
--                   and every reconciliation walk actually run.
--
-- ---------------------------------------------------------------------------------
-- Temporal conventions (04 section 5). Three axes; conflating any two breaks replay.
-- ---------------------------------------------------------------------------------
--
--   Business time   valid_from / valid_to. When the fact was true in the world.
--   System time     recorded_at / superseded_at. When the engine learned it.
--   Accounting time period_id. Which reporting period the row belongs to.
--
-- The axes are applied where 04 says they apply and nowhere else. CONTRACT_VERSION
-- carries the full bitemporal set because 04 section 2.3 says so. CONTRACT carries
-- none: it is the stable identity, and everything that changes about a contract lives
-- in a version or in the event stream. PRODUCT carries none. FEE_RULE_SET carries
-- effective_from and approved_at, which are its business and system time under the
-- names 04 section 2.5 gives them. EIR_COMPUTATION is never updated in place, so it
-- carries computed_as_of, recorded_at and superseded_by rather than a valid-time range.
--
-- Business time is DATE, not a timestamp: nothing in the calculation path reads a wall
-- clock, time is always an input, and 03 section 1.1 is why LocalDate rather than
-- Instant appears throughout the engine. System time is TIMESTAMPTZ because it records
-- an event in the engine's own history, which is a real instant.
--
-- ---------------------------------------------------------------------------------
-- Forward references
-- ---------------------------------------------------------------------------------
--
-- ENTITY (booking entity), POOL, POLICY_VERSION, ACCOUNTING_PERIOD, LIFECYCLE_EVENT and
-- PERIOD_BALANCE are defined outside this migration. Columns pointing at them —
-- entity_id, book_id, pool_id, policy_version_id, period_id — are therefore typed and
-- constrained but carry no foreign key here. A later migration adds the references; it
-- must not weaken the NOT NULLs, which are the part that carries the requirement.
-- =====================================================================================

-- btree_gist supplies the = operator class GiST needs for the equality half of the
-- bitemporal non-overlap constraint on CONTRACT_VERSION. It is a trusted extension from
-- PG13 onward, so the database owner can create it without superuser rights.
CREATE EXTENSION IF NOT EXISTS btree_gist;


-- =====================================================================================
-- PRODUCT — the shared taxonomy (04 section 2.2)
--
-- One taxonomy, two consumers: the same tag drives this engine and the provisioning
-- engine's ACPIR 82 floor categories. Divergence between them is a control failure
-- rather than a mapping inconvenience, which is why the floor category lives on the
-- product master rather than being derived per engine.
--
-- This table is also where the elections that 04 insists are product-level and not
-- contract-level live: the B5.4.4 next-repricing shortcut (FR-508) and the
-- rollover-versus-new-instrument treatment (FR-513). Putting either on the contract is
-- how a book ends up with two contracts of the same product amortising to different
-- horizons for no recorded reason.
-- =====================================================================================
CREATE TABLE product (
    product_id                      UUID            NOT NULL,
    product_name                    TEXT            NOT NULL,

    -- The ACPIR 82 floor category, shared with the provisioning engine (FR-106).
    -- Deliberately not CHECK-constrained to a value list: the category set is
    -- regulatory data, and freezing it here would turn an RBI amendment into a schema
    -- migration. What the schema can carry is that it is never absent — and the rule
    -- that gold loans hold their own category and are never grouped under secured
    -- retail (ACPIR 92) is a product-master review control, enforced upstream.
    acpir_floor_category            TEXT            NOT NULL,

    -- Default materiality tier, inherited by a contract that states none (FR-107).
    -- 1 | 2 | 3 as 04 writes it; the eir-domain MaterialityTier enum spells the same
    -- three values TIER_1..TIER_3.
    default_tier                    SMALLINT        NOT NULL,

    expected_life_method            TEXT            NOT NULL,

    -- The IFRS 9 B5.4.4 next-repricing-date election, per product (FR-508).
    -- No DEFAULT: an election is a decision someone took, and a defaulted election is
    -- indistinguishable from one nobody made.
    b544_election                   BOOLEAN         NOT NULL,

    rollover_treatment              TEXT            NOT NULL,

    -- Selects the projector in eir-calc. The value list matches the projectors that
    -- exist, so an unroutable product fails at load rather than at solve time.
    projection_strategy             TEXT            NOT NULL,

    day_count_default               TEXT            NOT NULL,
    compounding_default             TEXT            NOT NULL,

    -- ACPIR 54, revolving facilities only.
    revolving_approximation         TEXT,

    -- The numeric definition of "probable" for commitment fees (FR-204). A ratio, so it
    -- carries the rate scale: the threshold and the assessed probability it is compared
    -- against must be on the same scale, or the comparison is decided by rounding.
    drawdown_probability_threshold  NUMERIC(20,12),

    CONSTRAINT product_pk
        PRIMARY KEY (product_id),
    CONSTRAINT product_name_uq
        UNIQUE (product_name),
    CONSTRAINT product_default_tier_ck
        CHECK (default_tier IN (1, 2, 3)),
    CONSTRAINT product_expected_life_method_ck
        CHECK (expected_life_method IN ('CONTRACTUAL', 'NEXT_REPRICING', 'BEHAVIOURAL')),
    CONSTRAINT product_rollover_treatment_ck
        CHECK (rollover_treatment IN ('NEW_INSTRUMENT', 'REVOLVING_SUBSTANCE')),
    CONSTRAINT product_projection_strategy_ck
        CHECK (projection_strategy IN (
            'ANNUITY',
            'BULLET',
            'INTEREST_ONLY_BULLET',
            'BALLOON',
            'STEP_SCHEDULE',
            'MORATORIUM',
            'REVOLVING',
            'DISCOUNT_INSTRUMENT',
            'TRANCHED',
            'REPRICING_SHORTCUT',
            'EXTERNAL_SCHEDULE')),
    CONSTRAINT product_day_count_default_ck
        CHECK (day_count_default IN (
            'ACT_ACT_ISDA', 'ACT_365F', 'ACT_360',
            'THIRTY_360_BOND', 'THIRTY_E_360', 'ACT_365L')),
    CONSTRAINT product_compounding_default_ck
        CHECK (compounding_default IN (
            'WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY',
            'HALF_YEARLY', 'ANNUAL', 'SEASONAL', 'CUSTOM')),
    CONSTRAINT product_revolving_approximation_ck
        CHECK (revolving_approximation IN ('FEE_OVER_RENEWAL', 'EIR_OVER_UTILISATION')),
    -- ACPIR 54 requires an approximation to be chosen for a revolving facility. A
    -- revolving product with none selected would fall through to whatever the projector
    -- happened to default to, which is the silent-default failure mode FR-905 forbids.
    CONSTRAINT product_revolving_needs_approximation_ck
        CHECK (projection_strategy <> 'REVOLVING' OR revolving_approximation IS NOT NULL),
    -- The B5.4.4 election and the expected-life method are two statements of one
    -- decision. Allowing them to disagree lets a product claim the shortcut while
    -- amortising fees to maturity, which overstates year-1 income and reconciles
    -- against nothing.
    CONSTRAINT product_b544_implies_next_repricing_ck
        CHECK (NOT b544_election OR expected_life_method = 'NEXT_REPRICING'),
    -- A probability, not a rate: bounded.
    CONSTRAINT product_drawdown_threshold_range_ck
        CHECK (drawdown_probability_threshold IS NULL
               OR (drawdown_probability_threshold >= 0
                   AND drawdown_probability_threshold <= 1))
);

COMMENT ON TABLE product IS
    '04 s2.2 — one taxonomy shared with the provisioning engine; holds the elections '
    'that ACPIR and IFRS 9 place at product level rather than contract level.';


-- =====================================================================================
-- CONTRACT — the stable identity (04 section 2.1)
--
-- Immutable. Everything that changes lives in CONTRACT_VERSION or in the event stream,
-- which is what lets a backdated amendment leave a closed period's published figures
-- untouched (04 section 5). Nothing here is bitemporal for the same reason: there is no
-- history to keep of a fact that cannot change.
--
-- The measurement-category and SPPI columns are the classification gate that runs before
-- any EIR work (FR-103, FR-104). They sit on the identity rather than the version
-- because a change of category is a re-recognition event, not an amendment of terms.
-- =====================================================================================
CREATE TABLE contract (
    contract_id                     UUID            NOT NULL,

    -- The CBS/LMS account number, and the join key for every reconciliation.
    source_system_ref               TEXT            NOT NULL,

    -- Booking entity. Multi-entity support means entity-specific policy and
    -- entity-specific fee rule sets; see FEE_RULE_SET below.
    entity_id                       TEXT            NOT NULL,

    product_id                      UUID            NOT NULL,

    -- ISO 4217. Drives presentation scale — which is applied exactly once, on the way
    -- out, and never to an intermediate (03 section 1.2).
    currency                        CHAR(3)         NOT NULL,

    -- The gate that runs before any EIR work (FR-103). An instrument at FVTPL carries no
    -- EIR at all and must be excluded from EIR processing entirely; the schema's part is
    -- that the category is never absent and never a value outside the three. The
    -- exclusion itself is an ingestion-boundary filter, because a cross-table rule
    -- cannot be a row constraint.
    measurement_category            TEXT            NOT NULL,

    instrument_class                TEXT            NOT NULL,

    -- The SPPI assessment, its date and its approver (FR-104). All three or none: an
    -- outcome without a date and an owner is not an assessment, it is an assertion.
    sppi_outcome                    TEXT,
    sppi_assessed_on                DATE,
    sppi_approver                   TEXT,

    -- POCI on acquisition terms — a discount reflecting inherent credit losses,
    -- ACPIR 6(3)(v), FR-110. The basis is required where the flag is set because the
    -- credit-adjusted EIR it triggers cannot be re-derived without it.
    is_poci                         BOOLEAN         NOT NULL,
    poci_basis                      TEXT,

    -- Tier with its recorded basis (FR-107). The basis is NOT NULL: a tier assignment
    -- whose reasoning was not written down cannot be defended at an equivalence test.
    materiality_tier                SMALLINT        NOT NULL,
    tier_basis                      TEXT            NOT NULL,

    -- For a commitment, the date the bank became party to the irrevocable commitment
    -- (ACPIR 23) — not the date of first drawdown.
    initial_recognition_date        DATE            NOT NULL,

    -- Parallel books: the ACPIR basis alongside IGAAP and tax on the same contract
    -- (FR-109). Not value-constrained here; the set of books a bank runs is
    -- configuration, and a bank that adds a book should not need a migration.
    book_id                         TEXT            NOT NULL,

    CONSTRAINT contract_pk
        PRIMARY KEY (contract_id),
    CONSTRAINT contract_product_fk
        FOREIGN KEY (product_id) REFERENCES product (product_id),
    -- One row per source account per book per entity. This is what makes
    -- source_system_ref usable as the reconciliation join key: without it, two rows
    -- claiming the same CBS account in the same book would both tie, and neither would.
    CONSTRAINT contract_source_ref_uq
        UNIQUE (entity_id, book_id, source_system_ref),
    CONSTRAINT contract_currency_ck
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT contract_measurement_category_ck
        CHECK (measurement_category IN ('AMORTISED_COST', 'FVOCI', 'FVTPL')),
    CONSTRAINT contract_instrument_class_ck
        CHECK (instrument_class IN ('LOAN', 'INVESTMENT', 'OFF_BALANCE_SHEET', 'LIABILITY')),
    CONSTRAINT contract_sppi_outcome_ck
        CHECK (sppi_outcome IN ('PASS', 'FAIL')),
    CONSTRAINT contract_sppi_complete_ck
        CHECK ((sppi_outcome IS NULL) = (sppi_assessed_on IS NULL)
               AND (sppi_outcome IS NULL) = (sppi_approver IS NULL)),
    -- FR-104, the asset side: an SPPI failure sends the whole instrument to FVTPL, and
    -- there is no bifurcation. A liability is outside the SPPI test, so the liability
    -- side is exempted rather than forced.
    CONSTRAINT contract_sppi_fail_implies_fvtpl_ck
        CHECK (sppi_outcome IS DISTINCT FROM 'FAIL'
               OR instrument_class = 'LIABILITY'
               OR measurement_category = 'FVTPL'),
    -- Amortised cost and FVOCI are only reachable on the asset side with a passed SPPI
    -- assessment. An asset at amortised cost with no assessment on file is the gate
    -- having been skipped, which is what FR-104 exists to make visible.
    --
    -- IS NOT DISTINCT FROM rather than =, and that is the whole constraint. A CHECK is
    -- satisfied when its expression is NULL, so `sppi_outcome = 'PASS'` accepts a row
    -- whose sppi_outcome is NULL — the missing-assessment case this constraint exists
    -- for, waved through by three-valued logic. The first live run of this migration
    -- accepted exactly that row.
    CONSTRAINT contract_amortised_cost_needs_sppi_ck
        CHECK (measurement_category = 'FVTPL'
               OR instrument_class = 'LIABILITY'
               OR sppi_outcome IS NOT DISTINCT FROM 'PASS'),
    CONSTRAINT contract_poci_basis_ck
        CHECK (NOT is_poci OR poci_basis IS NOT NULL),
    CONSTRAINT contract_materiality_tier_ck
        CHECK (materiality_tier IN (1, 2, 3))
);

COMMENT ON TABLE contract IS
    '04 s2.1 — immutable identity plus the classification gate (FR-103, FR-104). '
    'Terms live in CONTRACT_VERSION; nothing here changes, so nothing here is temporal.';

CREATE INDEX contract_product_ix ON contract (product_id);
CREATE INDEX contract_entity_book_ix ON contract (entity_id, book_id);


-- =====================================================================================
-- CONTRACT_VERSION — terms as at a point in time (04 section 2.3)
--
-- A new row on every contractual change, never an update. Fully bitemporal: business
-- time says when the terms were true of the contract, system time says when the engine
-- was told. Both axes are needed and neither substitutes for the other — a backdated
-- amendment is a row with an earlier valid_from and a current recorded_at, and it is
-- precisely that combination that lets the already-closed periods keep their published
-- figures while the amendment produces a restatement in the current period.
--
-- The two-lives rule (FR-108) is enforced here as a constraint rather than a comment.
-- eir_expected_life_months and ecl_horizon_months are separate columns with no
-- derivation between them, and life_divergence_basis is required where they differ.
-- Collapsing the two into one field is the most common data-model defect in this domain
-- and the kind that cannot be fixed later without reworking every historical
-- computation, so the schema refuses the row rather than trusting the loader.
-- =====================================================================================
CREATE TABLE contract_version (
    contract_version_id             UUID            NOT NULL,
    contract_id                     UUID            NOT NULL,
    version_no                      INTEGER         NOT NULL,

    -- Business time. valid_to NULL means "still true"; the exclusion constraint below
    -- reads it as an unbounded upper bound.
    valid_from                      DATE            NOT NULL,
    valid_to                        DATE,

    -- System time. superseded_at NULL means "this is what the engine currently
    -- believes". A replay of a closed period reads the version set as at that period's
    -- recorded_at boundary, not today's (04 section 5), which is why this is a stored
    -- instant and not an inference from row order.
    recorded_at                     TIMESTAMPTZ     NOT NULL,
    superseded_at                   TIMESTAMPTZ,

    principal                       NUMERIC(24,6)   NOT NULL,
    contractual_rate                NUMERIC(20,12)  NOT NULL,

    -- FIXED | FLOATING. Routing keys off this together with the event's driver tag, and
    -- never off the observation that a rate moved (FR-507): a renegotiated fixed rate is
    -- a modification, not a reset.
    rate_type                       TEXT            NOT NULL,

    -- Floating-rate terms. Present as a set or absent as a set; see the checks.
    benchmark_id                    TEXT,
    -- Basis points carried on the rate scale so that benchmark-plus-spread arithmetic
    -- never leaves the 12dp domain on the way to a contractual rate.
    spread_bps                      NUMERIC(20,12),
    reset_frequency                 TEXT,
    next_reset_date                 DATE,

    day_count_convention            TEXT            NOT NULL,
    compounding_basis               TEXT            NOT NULL,

    contractual_maturity_date       DATE,

    -- ACPIR 51 expected life, and ACPIR 46(1) maximum contractual period. Two
    -- attributes, never derived from one another (FR-108).
    eir_expected_life_months        INTEGER         NOT NULL,
    ecl_horizon_months              INTEGER         NOT NULL,
    life_divergence_basis           TEXT,

    -- ACPIR 9(6)(i); interest during construction pre-COD. capitalises_interest is
    -- stated rather than inferred from moratorium_type, because a principal-only
    -- moratorium may or may not capitalise and the difference changes the flow vector.
    moratorium_type                 TEXT,
    moratorium_months               SMALLINT,
    capitalises_interest            BOOLEAN         NOT NULL,

    -- Optionality, stored as supplied. TEXT rather than JSONB deliberately: JSONB
    -- normalises key order and discards duplicate keys, and a stored input the database
    -- has silently rewritten cannot support the byte-identical replay FR-903 requires.
    -- The blueprint layer in eir-calc parses these.
    call_terms                      TEXT,
    put_terms                       TEXT,
    extension_terms                 TEXT,
    prepayment_terms                TEXT,

    -- The ACPIR 51 fallback to contractual cash flows over the full contractual term,
    -- as an explicit per-contract election with its justification (FR-310). Its
    -- incidence is reported on, which is only possible because the election is a column
    -- rather than the absence of an expected-life estimate.
    acpir_51_fallback               BOOLEAN         NOT NULL,
    fallback_justification          TEXT,

    CONSTRAINT contract_version_pk
        PRIMARY KEY (contract_version_id),
    CONSTRAINT contract_version_contract_fk
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT contract_version_no_uq
        UNIQUE (contract_id, version_no),
    CONSTRAINT contract_version_no_positive_ck
        CHECK (version_no > 0),

    -- Temporal integrity, both axes.
    CONSTRAINT contract_version_business_time_ck
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT contract_version_system_time_ck
        CHECK (superseded_at IS NULL OR superseded_at >= recorded_at),
    -- The bitemporal rule of 04 section 5, as a constraint: within the engine's current
    -- belief, one contract has one set of terms at any business date. Superseded rows
    -- are excluded, so correcting history is adding a row rather than rewriting one.
    -- daterange reads a NULL valid_to as unbounded.
    CONSTRAINT contract_version_no_overlap_ck
        EXCLUDE USING gist (
            contract_id WITH =,
            daterange(valid_from, valid_to) WITH &&
        ) WHERE (superseded_at IS NULL),

    -- The two-lives rule (FR-108), enforced in the schema.
    CONSTRAINT contract_version_expected_life_positive_ck
        CHECK (eir_expected_life_months > 0),
    CONSTRAINT contract_version_ecl_horizon_positive_ck
        CHECK (ecl_horizon_months > 0),
    CONSTRAINT contract_version_life_divergence_basis_ck
        CHECK (eir_expected_life_months = ecl_horizon_months
               OR life_divergence_basis IS NOT NULL),

    CONSTRAINT contract_version_rate_type_ck
        CHECK (rate_type IN ('FIXED', 'FLOATING')),
    CONSTRAINT contract_version_reset_frequency_ck
        CHECK (reset_frequency IN (
            'WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY',
            'HALF_YEARLY', 'ANNUAL', 'SEASONAL', 'CUSTOM')),
    -- A floating rate whose benchmark or reset cadence the engine cannot identify cannot
    -- be reset, and would be treated as fixed — the pre-ACPIR position, reproduced
    -- invisibly.
    CONSTRAINT contract_version_floating_terms_ck
        CHECK (rate_type <> 'FLOATING'
               OR (benchmark_id IS NOT NULL AND reset_frequency IS NOT NULL)),
    -- Conversely, a fixed rate carrying reset terms is contradictory data, and because
    -- routing keys off rate_type (FR-507) it is the kind of contradiction that decides a
    -- reset-versus-modification question the wrong way.
    CONSTRAINT contract_version_fixed_has_no_reset_ck
        CHECK (rate_type <> 'FIXED'
               OR (reset_frequency IS NULL AND next_reset_date IS NULL)),

    CONSTRAINT contract_version_day_count_ck
        CHECK (day_count_convention IN (
            'ACT_ACT_ISDA', 'ACT_365F', 'ACT_360',
            'THIRTY_360_BOND', 'THIRTY_E_360', 'ACT_365L')),
    CONSTRAINT contract_version_compounding_basis_ck
        CHECK (compounding_basis IN (
            'WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY',
            'HALF_YEARLY', 'ANNUAL', 'SEASONAL', 'CUSTOM')),

    CONSTRAINT contract_version_moratorium_type_ck
        CHECK (moratorium_type IN (
            'PRINCIPAL_ONLY', 'PRINCIPAL_AND_INTEREST', 'INTEREST_ONLY')),
    CONSTRAINT contract_version_moratorium_months_ck
        CHECK ((moratorium_type IS NULL AND moratorium_months IS NULL)
               OR (moratorium_type IS NOT NULL
                   AND moratorium_months IS NOT NULL
                   AND moratorium_months > 0)),

    CONSTRAINT contract_version_fallback_justification_ck
        CHECK (NOT acpir_51_fallback OR fallback_justification IS NOT NULL)
);

COMMENT ON TABLE contract_version IS
    '04 s2.3 — bitemporal terms. New row per change, never an update; business time and '
    'system time are separate axes and a backdated amendment needs both.';

COMMENT ON COLUMN contract_version.eir_expected_life_months IS
    'ACPIR 51 expected life. Never derived from ecl_horizon_months (FR-108).';

COMMENT ON COLUMN contract_version.ecl_horizon_months IS
    'ACPIR 46(1) maximum contractual period. A separate attribute, not a synonym.';

-- Business-time lookup: the terms in force on a date, in the engine's current belief.
CREATE INDEX contract_version_business_time_ix
    ON contract_version (contract_id, valid_from, valid_to);
-- System-time lookup: the version set as at a replay boundary.
CREATE INDEX contract_version_system_time_ix
    ON contract_version (contract_id, recorded_at);
CREATE INDEX contract_version_current_ix
    ON contract_version (contract_id) WHERE superseded_at IS NULL;


-- =====================================================================================
-- CASHFLOW_SCHEDULE (04 section 2.4)
--
-- Exactly two schedules per contract version: CONTRACTUAL and EXPECTED. The pair is the
-- basis of the two reconciliation legs, and the uniqueness constraint below is what
-- makes "the contractual leg" a well-defined phrase rather than a query that might
-- return two rows.
--
-- source records whether the schedule came from the LMS or was derived here. FR-102
-- prefers the former: an externally-supplied schedule is a first-class input, and
-- deriving one where the LMS already has one introduces a second answer to a question
-- that already had one.
-- =====================================================================================
CREATE TABLE cashflow_schedule (
    schedule_id                     UUID            NOT NULL,
    contract_version_id             UUID            NOT NULL,
    kind                            TEXT            NOT NULL,
    source                          TEXT            NOT NULL,

    -- How the rounding residue is absorbed. Stored per schedule rather than assumed,
    -- because the choice moves cash between periods and must be reproducible.
    residue_policy                  TEXT            NOT NULL,

    -- The CPR curve version behind a behavioural expected life. Recorded so that a
    -- back-tested curve change is attributable to the schedules it produced.
    cpr_curve_version               TEXT,

    CONSTRAINT cashflow_schedule_pk
        PRIMARY KEY (schedule_id),
    CONSTRAINT cashflow_schedule_version_fk
        FOREIGN KEY (contract_version_id)
        REFERENCES contract_version (contract_version_id),
    CONSTRAINT cashflow_schedule_kind_uq
        UNIQUE (contract_version_id, kind),
    CONSTRAINT cashflow_schedule_kind_ck
        CHECK (kind IN ('CONTRACTUAL', 'EXPECTED')),
    CONSTRAINT cashflow_schedule_source_ck
        CHECK (source IN ('LMS_AUTHORITATIVE', 'DERIVED')),
    CONSTRAINT cashflow_schedule_residue_policy_ck
        CHECK (residue_policy IN (
            'FINAL_PERIOD_PLUG', 'FIRST_PERIOD_PLUG',
            'SPREAD_LAST_N', 'LMS_AUTHORITATIVE')),
    -- A CPR curve describes expected behaviour. Attaching one to the contractual
    -- schedule would mean the contractual leg had absorbed an estimate, and the leg
    -- would no longer tie to the CBS.
    CONSTRAINT cashflow_schedule_cpr_on_expected_ck
        CHECK (kind = 'EXPECTED' OR cpr_curve_version IS NULL)
);

COMMENT ON TABLE cashflow_schedule IS
    '04 s2.4 — exactly one CONTRACTUAL and one EXPECTED schedule per contract version.';


-- =====================================================================================
-- CASHFLOW_LINE (04 section 2.4), range-partitioned by period_id (04 section 4)
--
-- The flow vector, as rows. Partitioned because this table and PERIOD_BALANCE are where
-- the volume is: partition pruning is what keeps a close-period query tractable, and a
-- closed period's partitions can be set read-only as a unit.
--
-- contract_id and period_id are carried here even though 04 section 2.4's field list
-- reaches the contract only through schedule -> version -> contract. Two reasons, both
-- from 04 section 4: period_id is the partition key and a partitioned table's keys must
-- contain it, and (contract_id, period_id) is the trace path — the index an auditor's
-- walk and every reconciliation actually use. Denormalising one column beats making the
-- trace query climb three joins per contract at 10M contracts.
-- =====================================================================================
CREATE TABLE cashflow_line (
    line_id                         UUID            NOT NULL,
    schedule_id                     UUID            NOT NULL,

    -- Denormalised from the owning schedule's contract version; see the note above.
    contract_id                     UUID            NOT NULL,

    -- Accounting time, and the partition key. YYYYMM as an integer: range bounds read
    -- the way dates do, ordering is the natural one, and a month cannot be misparsed.
    period_id                       INTEGER         NOT NULL,

    sequence_no                     INTEGER         NOT NULL,
    flow_date                       DATE            NOT NULL,

    -- Signed from the holder's perspective: a disbursement is negative, a receipt
    -- positive. NUMERIC(24,6) — the working scale, not the presentation scale, because a
    -- schedule line is an input to a solve and 03 section 1.2 forbids rounding an input
    -- to currency scale on the way in.
    amount                          NUMERIC(24,6)   NOT NULL,

    -- Mirrors the eir-domain FlowKind enum exactly.
    kind                            TEXT            NOT NULL,

    -- The rate-component driver tag, mirroring eir-domain RateDriver. Feeds event
    -- routing (ADR-0006): the mechanism is decided on what a component compensates for,
    -- not on the observation that a rate moved.
    driver                          TEXT,

    -- Contingent flows are rejected by the projector (FR-206) — prepayment penalties,
    -- late fees and bounce charges are recognised in the period the event occurs and
    -- never enter the inception projection. The flag is carried rather than assumed
    -- false so that the screen's decision is auditable on the row it screened.
    is_contingent                   BOOLEAN         NOT NULL,

    -- The partition key is part of both keys, as PostgreSQL requires of a partitioned
    -- table. line_id alone is still unique in practice — it is a supplied UUID — but the
    -- database can only enforce uniqueness that includes the partition key.
    CONSTRAINT cashflow_line_pk
        PRIMARY KEY (line_id, period_id),
    CONSTRAINT cashflow_line_sequence_uq
        UNIQUE (schedule_id, sequence_no, period_id),
    CONSTRAINT cashflow_line_schedule_fk
        FOREIGN KEY (schedule_id) REFERENCES cashflow_schedule (schedule_id),
    CONSTRAINT cashflow_line_contract_fk
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT cashflow_line_period_id_ck
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    CONSTRAINT cashflow_line_sequence_no_ck
        CHECK (sequence_no >= 0),
    CONSTRAINT cashflow_line_kind_ck
        CHECK (kind IN (
            'DISBURSEMENT',
            'PRINCIPAL',
            'INTEREST',
            'COMBINED_EMI',
            'INTEGRAL_FEE_RECEIVED',
            'INTEGRAL_COST_PAID',
            'BALLOON',
            'EXPECTED_PREPAYMENT',
            'RESIDUAL_VALUE',
            'NOTIONAL_REDEMPTION')),
    CONSTRAINT cashflow_line_driver_ck
        CHECK (driver IN (
            'TIME_VALUE_OF_MONEY',
            'CREDIT_RISK_MARKET',
            'CREDIT_RATCHET_PREDETERMINED',
            'ESG_LINKED',
            'STEP_UP_PREDETERMINED',
            'BEHAVIOURAL_ESTIMATE',
            'DISBURSEMENT_TIMING',
            'NEGOTIATED'))
) PARTITION BY RANGE (period_id);

COMMENT ON TABLE cashflow_line IS
    '04 s2.4 and s4 — the flow vector as rows, range-partitioned by period_id. '
    'amount is NUMERIC(24,6): a schedule line is a solver input, never rounded to '
    'presentation scale on the way in.';

-- Indian financial years, April to March. Bounds are inclusive of the lower and
-- exclusive of the upper, so FY2026-27 is [202604, 202704) — April 2026 through
-- March 2027. The run extends to March 2030, the ACPIR 50 boundary by which legacy
-- cohorts must be under the EIR regime; provisioning further ahead than the horizon
-- the programme plans against would be inventing retention policy here.
--
-- There is deliberately no DEFAULT partition. An insert for an unprovisioned period
-- fails loudly instead of landing in a catch-all that no pruning can skip and no
-- read-only switch can freeze. A hard stop per FR-905 beats a silent default.
CREATE TABLE cashflow_line_fy2027 PARTITION OF cashflow_line
    FOR VALUES FROM (202604) TO (202704);
CREATE TABLE cashflow_line_fy2028 PARTITION OF cashflow_line
    FOR VALUES FROM (202704) TO (202804);
CREATE TABLE cashflow_line_fy2029 PARTITION OF cashflow_line
    FOR VALUES FROM (202804) TO (202904);
CREATE TABLE cashflow_line_fy2030 PARTITION OF cashflow_line
    FOR VALUES FROM (202904) TO (203004);

-- The trace path (04 section 4). Declared on the parent so every existing and future
-- partition carries it.
CREATE INDEX cashflow_line_contract_period_ix
    ON cashflow_line (contract_id, period_id);
-- Reading a schedule back in projection order.
CREATE INDEX cashflow_line_schedule_sequence_ix
    ON cashflow_line (schedule_id, sequence_no);


-- =====================================================================================
-- FEE_RULE_SET — the versioned classifier (04 section 2.5)
--
-- (fee_code, product_id, entity_id, effective_from) -> classification, with maker,
-- checker, approved_at and impact_preview_ref. Immutable once approved, and referenced
-- by every posting it classified so that a classification can be replayed under the rule
-- that produced it rather than under today's.
--
-- Resolution is most-specific-wins: a NULL product_id or entity_id is a wildcard. That
-- makes NULL a meaningful value in the key, so the uniqueness constraint is declared
-- NULLS NOT DISTINCT — under the default treatment two identical wildcard rules for one
-- fee code would both be accepted and resolution would become order-dependent.
--
-- An unmapped fee code raises rather than defaults (FR-202). A mandatory default per fee
-- code is therefore a rule-set approval control: the schema can guarantee at most one
-- default per code per date, which it does below, but the existence of one is not
-- expressible as a row constraint.
--
-- Immutability once approved is a privilege matter — no UPDATE or DELETE grant on this
-- table for the engine's role — and not something a CHECK can carry. It is called out
-- here so the grant script is written knowing it is load-bearing.
-- =====================================================================================
CREATE TABLE fee_rule_set (
    rule_set_version_id             UUID            NOT NULL,

    fee_code                        TEXT            NOT NULL,
    -- NULL = applies to every product. Most-specific-wins.
    product_id                      UUID,
    -- NULL = applies to every booking entity.
    entity_id                       TEXT,

    -- Business time. A rule is superseded by a later effective_from for the same key,
    -- never edited, which is what "immutable once approved" means in practice.
    effective_from                  DATE            NOT NULL,

    -- Mirrors the eir-domain FeeClassification enum exactly.
    classification                  TEXT            NOT NULL,

    -- Maker-checker, and the impact preview without which a version cannot be approved
    -- (the Phase 2 exit gate). All four NOT NULL: this table is the reason a
    -- classification can be defended, and an unapproved rule that classified a posting
    -- would remove that.
    maker                           TEXT            NOT NULL,
    checker                         TEXT            NOT NULL,
    approved_at                     TIMESTAMPTZ     NOT NULL,
    impact_preview_ref              TEXT            NOT NULL,

    CONSTRAINT fee_rule_set_pk
        PRIMARY KEY (rule_set_version_id),
    CONSTRAINT fee_rule_set_product_fk
        FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT fee_rule_set_key_uq
        UNIQUE NULLS NOT DISTINCT (fee_code, product_id, entity_id, effective_from),
    CONSTRAINT fee_rule_set_classification_ck
        CHECK (classification IN (
            'INTEGRAL',
            'AS_INCURRED',
            'OVER_COMMITMENT_PERIOD',
            'SEPARATE_SERVICE',
            'EXCLUDED_BY_DIRECTION')),
    -- Maker-checker means two people. One person in both roles is the control not
    -- operating, and it is cheaper to refuse the row than to detect it later.
    CONSTRAINT fee_rule_set_maker_is_not_checker_ck
        CHECK (maker <> checker)
);

COMMENT ON TABLE fee_rule_set IS
    '04 s2.5 — versioned, maker-checked fee classification. NULL product_id or '
    'entity_id is a wildcard; the key is NULLS NOT DISTINCT so wildcards cannot '
    'duplicate and make resolution order-dependent.';

-- At most one wildcard default per fee code per effective date. The default itself is
-- mandatory (FR-202), but its existence is an approval-time check, not a row constraint.
CREATE UNIQUE INDEX fee_rule_set_default_uq
    ON fee_rule_set (fee_code, effective_from)
    WHERE product_id IS NULL AND entity_id IS NULL;

-- Resolution reads by code and date, then narrows.
CREATE INDEX fee_rule_set_resolution_ix
    ON fee_rule_set (fee_code, effective_from DESC);


-- =====================================================================================
-- FEE_POSTING (04 section 2.5)
--
-- A fee or cost as posted, with the resolved classification and the rule set version
-- that resolved it. The version reference is what makes a classification replayable:
-- without it, re-running a closed period under today's rule set produces a different
-- answer and no way to tell which reading was applied at the time.
--
-- cost_function is NOT NULL, and that is FR-203 rather than tidiness. It is the ACPIR 53
-- selling-versus-processing line: an employee selling-agent incentive is capitalisable
-- and internal credit-appraisal cost is not, and a posting that does not say which it is
-- cannot be classified. Such a posting is rejected, never assumed.
-- =====================================================================================
CREATE TABLE fee_posting (
    posting_id                      UUID            NOT NULL,
    contract_id                     UUID            NOT NULL,
    fee_code                        TEXT            NOT NULL,

    -- Signed as the contract holder sees it, on the working scale, for the same reason
    -- as CASHFLOW_LINE.amount: an integral fee enters a solve.
    amount                          NUMERIC(24,6)   NOT NULL,

    posted_on                       DATE            NOT NULL,

    -- Who paid. Not value-constrained: the party set is a source-system code list, and
    -- what the engine decides on is cost_function and classification below.
    payer                           TEXT            NOT NULL,

    -- The ACPIR 53 line. Rejected if absent (FR-203).
    cost_function                   TEXT            NOT NULL,

    -- The resolved value. EXCLUDED_BY_DIRECTION carries penal charges under RBI's 2023
    -- framework: they cannot enter any EIR stream or the gross carrying amount, they are
    -- not capitalised, and no further interest accrues on them. Legacy core banking
    -- systems routinely route penal amounts through the interest ledger, so the
    -- classification is asserted positively each period (invariant PC-1) rather than
    -- trusted.
    classification                  TEXT            NOT NULL,

    -- The rule set version that produced the classification above. Enables replay.
    rule_set_version_id             UUID            NOT NULL,

    -- Required on commitment fees (FR-204), compared against the owning product's
    -- drawdown_probability_threshold. A ratio on the rate scale, so the comparison is
    -- decided by the numbers and not by rounding.
    drawdown_probability            NUMERIC(20,12),

    linked_facility_id              TEXT,

    CONSTRAINT fee_posting_pk
        PRIMARY KEY (posting_id),
    CONSTRAINT fee_posting_contract_fk
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT fee_posting_rule_set_fk
        FOREIGN KEY (rule_set_version_id)
        REFERENCES fee_rule_set (rule_set_version_id),
    CONSTRAINT fee_posting_cost_function_ck
        CHECK (cost_function IN ('SELLING', 'PROCESSING', 'ADMIN', 'OTHER')),
    CONSTRAINT fee_posting_classification_ck
        CHECK (classification IN (
            'INTEGRAL',
            'AS_INCURRED',
            'OVER_COMMITMENT_PERIOD',
            'SEPARATE_SERVICE',
            'EXCLUDED_BY_DIRECTION')),
    CONSTRAINT fee_posting_drawdown_probability_range_ck
        CHECK (drawdown_probability IS NULL
               OR (drawdown_probability >= 0 AND drawdown_probability <= 1)),
    -- A fee recognised over the commitment period got there by a drawdown-probability
    -- assessment falling below the product threshold (FR-204). The assessment is the
    -- reason for the treatment, so the treatment cannot be recorded without it. The
    -- wider rule — every commitment fee code carries an assessment — is enforced by the
    -- rule set, because which codes are commitment fees is data.
    CONSTRAINT fee_posting_commitment_needs_probability_ck
        CHECK (classification <> 'OVER_COMMITMENT_PERIOD'
               OR drawdown_probability IS NOT NULL)
);

COMMENT ON TABLE fee_posting IS
    '04 s2.5 — a posting with its resolved classification and the rule set version that '
    'resolved it. cost_function is NOT NULL: the ACPIR 53 line, FR-203.';

CREATE INDEX fee_posting_contract_posted_ix ON fee_posting (contract_id, posted_on);
CREATE INDEX fee_posting_fee_code_ix ON fee_posting (fee_code);
CREATE INDEX fee_posting_rule_set_ix ON fee_posting (rule_set_version_id);


-- =====================================================================================
-- EIR_COMPUTATION — the audit anchor (04 section 2.6)
--
-- One row per solve. This is the table an auditor reads, and it is never archived.
--
-- Two columns carry the whole replay story: policy_version_id and rule_set_version_id
-- are the reading in force when the rate was struck. Without them a re-solve years later
-- uses today's policy and produces a different rate, with nothing to show which of the
-- two answers was the published one.
--
-- Rows are never updated in place. A superseding solve is a new row, and superseded_by
-- points forward to it.
-- =====================================================================================
CREATE TABLE eir_computation (
    computation_id                  UUID            NOT NULL,

    -- Exactly one of the two. A pool EIR is struck on a closed cohort and belongs to no
    -- single contract; a contract-level solve belongs to no pool. POOL is defined outside
    -- this migration, so pool_id carries no foreign key yet.
    contract_id                     UUID,
    pool_id                         UUID,

    -- Accounting time. Not in 04 section 2.6's field list, and carried because section 4
    -- names (contract_id, period_id) as the trace path: the index an auditor walks a
    -- period on.
    period_id                       INTEGER         NOT NULL,

    -- Business time: the date as at which the solve was performed. A date, not an
    -- instant — nothing in the calculation path reads a wall clock, and the as-of date is
    -- an input (04 section 5, 03 section 1.1).
    computed_as_of                  DATE            NOT NULL,

    -- System time: when the engine recorded this solve. Paired with superseded_by, which
    -- needs an orderable system-time axis to mean anything — "never updated in place"
    -- only supports replay if the chain of beliefs can be ordered.
    recorded_at                     TIMESTAMPTZ     NOT NULL,

    -- Why the solve happened. TRIGGER is a non-reserved keyword in PostgreSQL, so 04's
    -- field name is used unquoted.
    trigger                         TEXT            NOT NULL,

    -- The persisted and used rate (FR-404): 12 decimal places, and every downstream
    -- period uses this stored value rather than re-deriving one.
    rate_periodic                   NUMERIC(20,12),

    -- Both annual forms, both stored, both labelled (FR-405). An unlabelled "annual EIR"
    -- is ambiguous between these two by a compounding factor, and the ambiguity is
    -- resolved by storing both rather than by convention.
    rate_effective_annual           NUMERIC(20,12),
    rate_nominal_annual             NUMERIC(20,12),

    rate_kind                       TEXT            NOT NULL,

    -- PERIODIC_INDEX | ACTUAL_DATE, with the outcome of the precondition check that
    -- decided it. The check outcome is stored because the two conventions agree only
    -- where the preconditions hold, and a later reader needs to know they were tested.
    convention                      TEXT            NOT NULL,
    convention_precondition_result  TEXT            NOT NULL,

    -- GCA-zero for an EIR; amortised cost for a credit-adjusted EIR. Money, so the
    -- working scale.
    opening_carrying_amount         NUMERIC(24,6)   NOT NULL,

    solver_method                   TEXT            NOT NULL,
    iterations                      INTEGER         NOT NULL,

    -- The NPV residual left at the stored 12dp rate. Money-dimensioned: it is what the
    -- discounted flows fail to cancel, and it is the number that shows the stored rate
    -- was good enough to publish.
    residual_at_stored_rate         NUMERIC(24,6),

    status                          TEXT            NOT NULL,

    -- The exact flow vector solved. A reference rather than a copy: the vector lives in
    -- CASHFLOW_LINE, and duplicating it here would create a second version of a fact.
    flow_vector_ref                 TEXT            NOT NULL,

    -- The reading in force. Without these, replay is impossible. POLICY_VERSION is
    -- defined outside this migration, hence no foreign key on policy_version_id yet.
    policy_version_id               UUID            NOT NULL,
    rule_set_version_id             UUID            NOT NULL,

    -- Forward pointer to the solve that replaced this one. Never an update.
    superseded_by                   UUID,

    CONSTRAINT eir_computation_pk
        PRIMARY KEY (computation_id),
    CONSTRAINT eir_computation_contract_fk
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT eir_computation_rule_set_fk
        FOREIGN KEY (rule_set_version_id)
        REFERENCES fee_rule_set (rule_set_version_id),
    CONSTRAINT eir_computation_superseded_by_fk
        FOREIGN KEY (superseded_by) REFERENCES eir_computation (computation_id),
    CONSTRAINT eir_computation_subject_ck
        CHECK ((contract_id IS NULL) <> (pool_id IS NULL)),
    CONSTRAINT eir_computation_period_id_ck
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    CONSTRAINT eir_computation_not_self_superseding_ck
        CHECK (superseded_by IS NULL OR superseded_by <> computation_id),
    CONSTRAINT eir_computation_trigger_ck
        CHECK (trigger IN (
            'INITIAL_RECOGNITION',
            'RESET',
            'MODIFICATION',
            'DERECOGNITION',
            'POOL_EXIT',
            'TRANSITION')),
    CONSTRAINT eir_computation_rate_kind_ck
        CHECK (rate_kind IN ('EIR', 'CREDIT_ADJUSTED_EIR', 'DEEMED_EIR')),
    CONSTRAINT eir_computation_convention_ck
        CHECK (convention IN ('PERIODIC_INDEX', 'ACTUAL_DATE')),
    CONSTRAINT eir_computation_precondition_result_ck
        CHECK (convention_precondition_result IN (
            'SATISFIED', 'NOT_SATISFIED', 'NOT_APPLICABLE')),
    CONSTRAINT eir_computation_solver_method_ck
        CHECK (solver_method IN ('NEWTON', 'BISECTION_FALLBACK')),
    CONSTRAINT eir_computation_iterations_ck
        CHECK (iterations >= 0),
    CONSTRAINT eir_computation_status_ck
        CHECK (status IN ('SOLVED', 'REQUIRES_REVIEW', 'NO_SOLUTION', 'MULTIPLE_ROOTS')),
    -- No silent fallback to the contractual rate (FR-402). A NO_SOLUTION carries no rate
    -- at all, and anything that did solve carries one: a row with a rate and a status of
    -- NO_SOLUTION would be the pre-ACPIR position recorded as though it had been
    -- computed, which is the failure this constraint exists to make impossible.
    CONSTRAINT eir_computation_no_solution_has_no_rate_ck
        CHECK ((status = 'NO_SOLUTION') = (rate_periodic IS NULL)),
    -- All three forms travel together (FR-405), so a reader is never handed the periodic
    -- rate with one annual form missing and left to guess which was meant.
    CONSTRAINT eir_computation_rate_forms_together_ck
        CHECK ((rate_periodic IS NULL) = (rate_effective_annual IS NULL)
               AND (rate_periodic IS NULL) = (rate_nominal_annual IS NULL)),
    -- The residual is a property of a stored rate; there is none to report where no rate
    -- was stored.
    CONSTRAINT eir_computation_residual_with_rate_ck
        CHECK ((rate_periodic IS NULL) = (residual_at_stored_rate IS NULL))
);

COMMENT ON TABLE eir_computation IS
    '04 s2.6 — one row per solve, never updated in place, never archived. '
    'policy_version_id and rule_set_version_id are the reading in force: without them '
    'replay is impossible.';

COMMENT ON COLUMN eir_computation.rate_periodic IS
    'The persisted and used rate, NUMERIC(20,12) (FR-404). NULL only where status is '
    'NO_SOLUTION — there is no fallback to the contractual rate (FR-402).';

-- The trace path (04 section 4).
CREATE INDEX eir_computation_contract_period_ix
    ON eir_computation (contract_id, period_id);
CREATE INDEX eir_computation_pool_period_ix
    ON eir_computation (pool_id, period_id) WHERE pool_id IS NOT NULL;
-- The current belief for a contract as at a date, for the amortisation path.
CREATE INDEX eir_computation_current_ix
    ON eir_computation (contract_id, computed_as_of) WHERE superseded_by IS NULL;
-- Replay by system-time boundary.
CREATE INDEX eir_computation_recorded_at_ix
    ON eir_computation (recorded_at);
