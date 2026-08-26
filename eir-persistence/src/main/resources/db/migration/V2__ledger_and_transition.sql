-- =====================================================================================
-- V2 — ledger, policy, run and transition entities
--
-- The second half of the schema of docs/04-data-model.md. V1 carries the identity and
-- computation core (CONTRACT, PRODUCT, CONTRACT_VERSION, CASHFLOW_SCHEDULE,
-- CASHFLOW_LINE, FEE_POSTING, EIR_COMPUTATION, CANDIDATE_ROOT). This file carries
-- everything a run writes or a policy governs:
--
--   04 § 2.7   LIFECYCLE_EVENT           the event stream and its routing decision
--   04 § 2.8   PERIOD_BALANCE            the sub-ledger row  (range-partitioned)
--   04 § 1/§4  JOURNAL_ENTRY             the double-entry leg (range-partitioned)
--   04 § 2.9   STAGE_ASSIGNMENT, SUSPENSE_ENTRY
--   04 § 2.10  POOL
--   04 § 2.11  HEDGE_RELATIONSHIP, BASIS_ADJUSTMENT
--   04 § 2.12  POLICY_VERSION
--   04 § 2.13  ACCOUNTING_PERIOD, AMORTISATION_RUN
--   04 § 3     EXCEPTION
--   04 § 6     TRANSITION_FAIR_VALUE, LEGACY_COHORT, DEEMED_EIR_DERIVATION,
--              ECL_DISCOUNT_BASIS
--
-- The organising principle of 04 applies throughout: the engine stores the inputs and
-- the policy version alongside every result, so any published figure can be reproduced
-- years later under the reading then in force. Where a column here looks like
-- bookkeeping about bookkeeping — a rule-set version, an ECL engine version, a maker
-- and a checker — that is exactly what it is for, and dropping it makes replay
-- impossible rather than merely inconvenient.
--
-- -------------------------------------------------------------------------------------
-- Physical rules (04 § 4), applied without exception
-- -------------------------------------------------------------------------------------
--   Money      NUMERIC(24,6)
--   Rates      NUMERIC(20,12)
--
-- Both are spelled out at every single column rather than hidden behind a CREATE
-- DOMAIN. A domain would read better and would centralise the decision, but the defect
-- this precision exists to prevent — a money column that came out NUMERIC(24,2), parses
-- fine, and silently truncates working precision — is caught by reading the DDL and by
-- querying information_schema.columns. A literal is unambiguous to both. There are
-- exactly two numeric shapes in this file and any third one is a defect.
--
-- Never float, double precision, real, or money. ADR-0002 bans binary floating point
-- because it cannot represent 0.01, and the error accumulated over 360 compounding
-- periods produces a reconciliation break with no single diagnosable cause.
-- PostgreSQL's `money` type is additionally fixed-scale and locale-dependent: its scale
-- follows lc_monetary, so the same DDL means different things on two servers.
--
-- -------------------------------------------------------------------------------------
-- No column defaults to now()
-- -------------------------------------------------------------------------------------
-- 04 § 5: "Nothing in the calculation path reads a wall clock. Time is an input,
-- always." That extends to the audit metadata on these rows. A DEFAULT now() would make
-- a replay of a closed period produce rows differing from the published ones in their
-- timestamps, and invariant DT-1 asks for bit-identical reproduction (FR-903). Every
-- timestamp here is supplied by the caller, which is also the only way a backdated
-- correction can carry the recorded_at it actually had. The two views that do read the
-- clock are reporting views and say so.
--
-- -------------------------------------------------------------------------------------
-- Cross-file foreign keys — the deliberate trade
-- -------------------------------------------------------------------------------------
-- This file is written against a V1 it cannot see, so a plain FOREIGN KEY to CONTRACT
-- would make V2 fail to run standalone. Both obvious answers are bad: no referential
-- integrity at all leaves contract_id as a number that can point at nothing, and a hard
-- FK makes the migration unrunnable and therefore unverifiable on its own.
--
-- The choice made here is the third option. Every intra-file relationship is a real,
-- immediately-enforced FOREIGN KEY — and there are more of those than there might have
-- been, because POLICY_VERSION lives in this file and so every "…_version_id" column
-- resolves locally. Every cross-file relationship is declared as data in the
-- clearly-marked DEFERRED FOREIGN KEYS section at the foot of the file, which adds each
-- constraint if and only if the parent table exists. Run under Flyway after V1 the
-- constraints are created and the schema has full referential integrity; run standalone
-- the section emits one NOTICE per skipped constraint and the file still completes. A
-- column-type disagreement with V1 raises a WARNING naming the constraint rather than
-- aborting, because the remedy is a one-line type change and whoever reconciles the two
-- files needs to see every mismatch, not the first.
--
-- The assumption that section rests on, stated once so it is greppable and so the
-- reconciliation has a single home: **surrogate keys owned by V1 are BIGINT**
-- (contract_id, product_id, entity_id, contract_version_id, eir_computation_id,
-- schedule_id), and **book_id is TEXT** — a short book code such as 'ACPIR', 'IGAAP',
-- 'TAX' (04 § 2.1, FR-109) rather than a surrogate, because 04 has no BOOK entity to be
-- the parent of one.
--
-- POLICY_VERSION is the one key here that is not a surrogate: its primary key is TEXT,
-- because eir-policy's PolicyVersion record already identifies a version by a String id
-- and the schema should adopt that identifier rather than invent a surrogate the policy
-- module has no field to carry. Every "…_version_id" column in this file therefore
-- resolves to POLICY_VERSION in this file — routing table version, rule set version,
-- pool definition version — which is what PolicyKind's ROUTING_TABLE, FEE_RULE_SET and
-- POOL_DEFINITION members say those artefacts are.
--
-- -------------------------------------------------------------------------------------
-- Enumerations are CHECK constraints, not PostgreSQL ENUM types
-- -------------------------------------------------------------------------------------
-- A CHECK keeps the vocabulary visible in the DDL and in pg_constraint, diffs legibly,
-- and can drop a value. An ENUM type cannot drop a value, bakes in an ordering nothing
-- should read, and hides the vocabulary in a catalogue nobody greps. Where 04 does not
-- enumerate a vocabulary this file says so and leaves the column free rather than
-- inventing one.
-- =====================================================================================


-- =====================================================================================
-- SECTION 1 — Policy and calendar
--
-- These come first because almost everything below points at them: a run belongs to a
-- period, and a result is meaningless without the reading in force when it was produced.
-- =====================================================================================

-- btree_gist supplies the GiST equality operator class for a scalar column, which is
-- what lets an EXCLUDE constraint combine "same policy kind" with "overlapping
-- effective range" — see ck/ex_policy_version_no_overlap below. It is a standard
-- PostgreSQL contrib module and, since PostgreSQL 13, a trusted one, so a database
-- owner can create it without superuser rights.
--
-- The alternative to the extension is a trigger, and it is the wrong alternative here: a
-- trigger that queries for an overlapping row cannot see an uncommitted concurrent
-- insert, so two sessions approving two versions at once both pass. An EXCLUDE
-- constraint is an index and does not have that hole.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- POLICY_VERSION (04 § 2.12) — the interpretive hierarchy as data.
--
-- What ACPIR leaves silent, this table resolves: which reading applies, which
-- driver-to-mechanism routing table version is in force, the materiality thresholds and
-- tier rules, the residue policy, the liability-side election, the day-count defaults.
-- Immutable once approved. It is a table rather than configuration in code because
-- ADR-0006's whole argument is that an IASB amendment to B5.4.5 must be a table change
-- and not a re-engineering event — and because an auditor asking "under which reading
-- was this figure published" needs a row to be handed, not a git tag.
--
-- The vocabulary of policy_kind and status is taken verbatim from eir-policy's
-- PolicyKind and PolicyVersionStatus. Two spellings of one vocabulary is a defect
-- waiting to happen, so where the Java already names the values the SQL follows it
-- rather than paraphrasing.
CREATE TABLE policy_version (
    policy_version_id               TEXT             NOT NULL,
    policy_kind                     TEXT             NOT NULL,
    version_label                   TEXT             NOT NULL,
    description                     TEXT,
    -- Business time. A version approved in March to take effect on 1 April is approved
    -- and not yet operative, which is why effective_from is not approved_at.
    effective_from                  DATE             NOT NULL,
    effective_to                    DATE,
    status                          TEXT             NOT NULL,
    -- Maker–checker (FR-210). Two distinct people, enforced below, because ACPIR
    -- forbids manual rate override (ADR-0008) and that makes the approval record the
    -- only evidence that anyone looked.
    maker                           TEXT             NOT NULL,
    checker                         TEXT,
    approved_at                     TIMESTAMPTZ,
    -- The quantified impact preview. FR-210 makes it mandatory before a version goes
    -- effective: "signed off but nobody has seen what it does to the book" is a state a
    -- single approved flag cannot express.
    impact_preview_ref              TEXT,
    -- The readings themselves. JSONB rather than a column per position because there are
    -- thirty-three policy positions in the ACPIR reference and they are not all settled;
    -- a settled one that turns out to be load-bearing earns its own column, and until
    -- then a schema migration per position would be noise.
    acpir_silence_resolutions       JSONB,
    tier_assignment_rules           JSONB,
    -- Thresholds get real columns because the engine reads them on every contract.
    materiality_threshold_amount    NUMERIC(24,6),
    materiality_threshold_rate      NUMERIC(20,12),
    residue_policy                  TEXT,
    liability_side_election         TEXT,
    day_count_default               TEXT,
    compounding_default             TEXT,
    superseded_by                   TEXT,
    CONSTRAINT pk_policy_version
        PRIMARY KEY (policy_version_id),
    CONSTRAINT uq_policy_version_kind_label
        UNIQUE (policy_kind, version_label),
    CONSTRAINT fk_policy_version_superseded_by
        FOREIGN KEY (superseded_by) REFERENCES policy_version (policy_version_id),
    -- eir-policy PolicyKind, complete.
    CONSTRAINT ck_policy_version_kind
        CHECK (policy_kind IN (
            'FEE_RULE_SET',
            'ROUTING_TABLE',
            'TIER_ASSIGNMENT',
            'POOL_DEFINITION',
            'BEHAVIOURAL_CURVE',
            'COMMITMENT_THRESHOLD')),
    -- eir-policy PolicyVersionStatus, complete.
    CONSTRAINT ck_policy_version_status
        CHECK (status IN (
            'DRAFT',
            'PENDING_APPROVAL',
            'APPROVED',
            'EFFECTIVE',
            'SUPERSEDED')),
    -- Four eyes. A version whose maker is its own checker is not approved, it is
    -- self-certified, and the constraint is here rather than only in the service layer
    -- because the service layer is not what an auditor reads.
    --
    -- Compared case-folded and trimmed, which is not fussiness. A raw `checker <> maker`
    -- is defeated by a trailing space: 'alice' approved by 'alice ' passes, and the two
    -- render identically in every report anyone would review. Both functions are
    -- IMMUTABLE and so are legal in a CHECK.
    CONSTRAINT ck_policy_version_four_eyes
        CHECK (checker IS NULL
               OR lower(btrim(checker)) <> lower(btrim(maker))),
    -- And the identities are stored in the form they are compared in, so that a report
    -- reading the column and a constraint reading a normalisation of it agree.
    CONSTRAINT ck_policy_version_maker_normalised
        CHECK (maker = btrim(maker) AND maker <> ''),
    CONSTRAINT ck_policy_version_checker_normalised
        CHECK (checker IS NULL OR (checker = btrim(checker) AND checker <> '')),
    -- The FR-210 gate as a constraint: nothing reaches an approved state without a
    -- checker, an approval timestamp and a stored impact preview.
    CONSTRAINT ck_policy_version_approval_complete
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL')
               OR (checker IS NOT NULL
                   AND approved_at IS NOT NULL
                   AND impact_preview_ref IS NOT NULL)),
    CONSTRAINT ck_policy_version_effective_range
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_policy_version_residue_policy
        CHECK (residue_policy IS NULL OR residue_policy IN (
            'FINAL_PERIOD_PLUG',
            'FIRST_PERIOD_PLUG',
            'SPREAD_LAST_N',
            'LMS_AUTHORITATIVE')),
    -- At most one version of a kind in force on any date.
    --
    -- This is the constraint that makes "which reading was in force" a question with one
    -- answer, which is the entire purpose of the table. Without it two open-ended
    -- EFFECTIVE routing tables insert happily and the resolution query returns two rows —
    -- and the engine then recognises income under whichever the planner returned first,
    -- which is the class of defect nobody notices until a replay disagrees with a
    -- published figure.
    --
    -- Restricted to the operative and approved states: drafts and rejected submissions
    -- may overlap freely, because nothing resolves against them. SUPERSEDED is included
    -- because a closed period still resolves against a superseded version (invariant
    -- DT-1), so it has to have been unambiguous when it was live.
    --
    -- The range is half-open, '[)': a version effective from 1 April does not overlap one
    -- that ended on 1 April. NULL effective_to is an unbounded upper end, which is what
    -- daterange does with it.
    CONSTRAINT ex_policy_version_no_overlap
        EXCLUDE USING gist (
            policy_kind WITH =,
            daterange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status IN ('APPROVED', 'EFFECTIVE', 'SUPERSEDED'))
);

COMMENT ON TABLE policy_version IS
    '04 s2.12 - the interpretive hierarchy as data. Immutable once approved; retained '
    'after supersession because a closed period must still resolve against the version '
    'in force when it closed (invariant DT-1).';

-- ACCOUNTING_PERIOD (04 § 2.13) — accounting time, the third axis of 04 § 5.
--
-- period_id is an INTEGER encoded YYYYMM rather than a surrogate, and that is a
-- physical-design decision worth defending: it is the range partition key for
-- PERIOD_BALANCE and JOURNAL_ENTRY, so it must be monotonic in reporting order, and a
-- partition bound of FROM (202704) TO (202705) is legible to whoever is reading an
-- EXPLAIN plan at 2am during close. A surrogate would need a lookup to tell you which
-- period a partition holds.
--
-- CLOSED is immutable (FR-902). A correction to a closed period does not reopen it; it
-- creates a restatement artefact in the current period referencing the original. The
-- read-only enforcement for closed periods is in SECTION 5.
CREATE TABLE accounting_period (
    period_id                       INTEGER          NOT NULL,
    fiscal_year_label               TEXT             NOT NULL,
    period_start_date               DATE             NOT NULL,
    period_end_date                 DATE             NOT NULL,
    status                          TEXT             NOT NULL,
    closing_started_at              TIMESTAMPTZ,
    closed_at                       TIMESTAMPTZ,
    closed_by                       TEXT,
    -- The system-time boundary a replay of this period must read as at (04 § 5). Held
    -- explicitly because "as at the close" is otherwise a wall-clock guess, and a replay
    -- that guesses reads today's version set and fails DT-1.
    version_cutoff_at               TIMESTAMPTZ,
    CONSTRAINT pk_accounting_period
        PRIMARY KEY (period_id),
    CONSTRAINT ck_accounting_period_id_shape
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    -- The encoding is only useful if it cannot drift from the dates it encodes.
    CONSTRAINT ck_accounting_period_id_matches_dates
        CHECK (period_id = EXTRACT(YEAR FROM period_start_date)::INTEGER * 100
                           + EXTRACT(MONTH FROM period_start_date)::INTEGER),
    CONSTRAINT ck_accounting_period_dates
        CHECK (period_end_date >= period_start_date),
    CONSTRAINT ck_accounting_period_status
        CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    -- FR-902: a closed period names who closed it, when, and the system-time boundary a
    -- replay must read as at — or it is not closed.
    CONSTRAINT ck_accounting_period_closure_attested
        CHECK (status <> 'CLOSED'
               OR (closed_at IS NOT NULL
                   AND closed_by IS NOT NULL
                   AND version_cutoff_at IS NOT NULL))
);

COMMENT ON TABLE accounting_period IS
    '04 s2.13 - accounting time. period_id is YYYYMM and is the range partition key of '
    'PERIOD_BALANCE and JOURNAL_ENTRY. CLOSED is immutable (FR-902).';

-- AMORTISATION_RUN (04 § 2.13) — one row per execution of the engine over a period.
--
-- Runs are idempotent and replayable: the same inputs and policy version produce
-- byte-identical output (FR-903). The two digest columns are what makes that claim
-- checkable rather than aspirational — a replay whose output_digest differs from the run
-- it replays has found a defect, and without the digests the only way to notice is a
-- row-by-row comparison nobody performs.
CREATE TABLE amortisation_run (
    run_id                          UUID             ,
    period_id                       INTEGER          NOT NULL,
    book_id                         TEXT             NOT NULL,
    status                          TEXT             NOT NULL,
    started_at                      TIMESTAMPTZ      NOT NULL,
    completed_at                    TIMESTAMPTZ,
    contracts_processed             BIGINT           NOT NULL DEFAULT 0,
    exceptions_raised               BIGINT           NOT NULL DEFAULT 0,
    -- The invariant sweep's verdicts for this run, one entry per invariant id of 03 § 9.
    -- JSONB rather than a child table because the set of invariants is fixed by the
    -- specification and the results are read as a block, never joined against.
    invariant_results               JSONB,
    is_replay                       BOOLEAN          NOT NULL DEFAULT FALSE,
    replay_of_run_id                UUID  ,
    -- The reading in force. Without these a replay cannot resolve the same policy and
    -- FR-903 is unverifiable.
    policy_version_id               TEXT,
    rule_set_version_id             TEXT,
    routing_table_version_id        TEXT,
    input_digest                    TEXT,
    output_digest                   TEXT,
    CONSTRAINT pk_amortisation_run
        PRIMARY KEY (run_id),
    -- Redundant against the primary key on its own, and there for a reason: it is the
    -- unique constraint the composite foreign keys from PERIOD_BALANCE and JOURNAL_ENTRY
    -- point at, which is what stops a run for one period writing a row stamped with
    -- another. See the note on fk_period_balance_run_period.
    CONSTRAINT uq_amortisation_run_run_period
        UNIQUE (run_id, period_id),
    CONSTRAINT fk_amortisation_run_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    CONSTRAINT fk_amortisation_run_replay_of
        FOREIGN KEY (replay_of_run_id) REFERENCES amortisation_run (run_id),
    CONSTRAINT fk_amortisation_run_policy_version
        FOREIGN KEY (policy_version_id) REFERENCES policy_version (policy_version_id),
    CONSTRAINT fk_amortisation_run_rule_set_version
        FOREIGN KEY (rule_set_version_id) REFERENCES policy_version (policy_version_id),
    CONSTRAINT fk_amortisation_run_routing_table_version
        FOREIGN KEY (routing_table_version_id)
            REFERENCES policy_version (policy_version_id),
    -- 04 does not enumerate run status. This is the minimum vocabulary the (run_id,
    -- status) monitoring index of 04 § 4 implies, named here rather than left free
    -- because a monitor that must cope with unknown statuses cannot distinguish "not
    -- finished yet" from a typo.
    CONSTRAINT ck_amortisation_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'ABORTED')),
    CONSTRAINT ck_amortisation_run_replay_consistent
        CHECK (is_replay = (replay_of_run_id IS NOT NULL)),
    CONSTRAINT ck_amortisation_run_completion
        CHECK (status NOT IN ('COMPLETED', 'FAILED', 'ABORTED')
               OR completed_at IS NOT NULL),
    CONSTRAINT ck_amortisation_run_duration
        CHECK (completed_at IS NULL OR completed_at >= started_at),
    CONSTRAINT ck_amortisation_run_counts
        CHECK (contracts_processed >= 0 AND exceptions_raised >= 0)
);

-- 04 § 4 asks for (run_id, status) for run monitoring. run_id alone is already the
-- primary key, so this index exists for the shape the specification names: it answers
-- "what state is this run in" from the index without touching the heap, and it is the
-- same shape carried on EXCEPTION below, where the selectivity actually matters.
CREATE INDEX ix_amortisation_run_run_status
    ON amortisation_run (run_id, status);
CREATE INDEX ix_amortisation_run_period_status
    ON amortisation_run (period_id, status);


-- =====================================================================================
-- SECTION 2 — The event stream
-- =====================================================================================

-- LIFECYCLE_EVENT (04 § 2.7) — what happened to a contract, and how it was routed.
--
-- The routing decision is stored with the event, together with the version of the
-- mapping table that produced it. That is the entire point of ADR-0006: the treatment is
-- keyed off the instrument's rate type and the event's driver tag, never off the
-- observation that the rate moved (FR-507). A renegotiated fixed rate is a modification,
-- not a reset, and only the driver tag can tell you which happened.
--
-- Note what is evidence and what is decision. ten_percent_test_result and
-- qualitative_triggers are evidence (FR-511); substantiality_conclusion is a human
-- decision with a named owner and a timestamp. Storing the conclusion without decided_by
-- would make the engine appear to have decided, which is exactly the misrepresentation
-- FR-511 exists to prevent.
CREATE TABLE lifecycle_event (
    event_id                        UUID             ,
    contract_id                     UUID             NOT NULL,
    event_date                      DATE             NOT NULL,
    -- 04 does not enumerate event_type, and this file does not invent a vocabulary for
    -- it. Routing keys off driver and rate type (FR-507), both of which ARE enumerated;
    -- a CHECK here would constrain the source system's label without constraining
    -- anything the engine reads.
    event_type                      TEXT             NOT NULL,
    driver                          TEXT             NOT NULL,
    routed_mechanism                TEXT             NOT NULL,
    -- Which mapping produced the routing. ADR-0006.
    routing_table_version_id        TEXT,
    -- FR-509: carried on the event, never inferred. Whether a prepayment shortened the
    -- tenor or reduced the instalment changes the remaining flow vector, and the two are
    -- indistinguishable from the balance alone.
    prepayment_variant              TEXT,
    -- Catch-up (FR-506): restate the GCA to the PV of revised flows at the ORIGINAL EIR.
    -- The original rate is stored on the event because invariant CU-1 requires the
    -- persisted EIR before and after to be bit-identical, and that is only checkable if
    -- the rate the catch-up used is on the row.
    catch_up_amount                 NUMERIC(24,6),
    original_eir_used               NUMERIC(20,12),
    -- The 10% test. The ratio is the evidence; the result is its reading against the
    -- threshold. Both stored, because a ratio of 0.0999 and a threshold decision are
    -- different facts and an auditor asks for the first.
    ten_percent_test_ratio          NUMERIC(20,12),
    ten_percent_test_result         TEXT,
    qualitative_triggers            TEXT[],
    substantiality_conclusion       TEXT,
    decided_by                      TEXT,
    decided_at                      TIMESTAMPTZ,
    -- Deterministic intra-period ordering. Two events on one date must amortise in a
    -- fixed order or the run is not reproducible (FR-903).
    sequence_within_date            INTEGER          NOT NULL,
    -- System time (04 § 5): when the engine learned of the event, as against when it
    -- happened. A backdated event carries an earlier event_date and a current
    -- recorded_at, and the already-closed periods keep their published figures.
    recorded_at                     TIMESTAMPTZ      NOT NULL,
    superseded_at                   TIMESTAMPTZ,
    CONSTRAINT pk_lifecycle_event
        PRIMARY KEY (event_id),
    CONSTRAINT uq_lifecycle_event_ordering
        UNIQUE (contract_id, event_date, sequence_within_date),
    CONSTRAINT fk_lifecycle_event_routing_table_version
        FOREIGN KEY (routing_table_version_id)
            REFERENCES policy_version (policy_version_id),
    -- 04 § 2.7 driver vocabulary, complete.
    CONSTRAINT ck_lifecycle_event_driver
        CHECK (driver IN (
            'TIME_VALUE_OF_MONEY',
            'CREDIT_RISK_MARKET',
            'CREDIT_RATCHET_PREDETERMINED',
            'ESG_LINKED',
            'STEP_UP_PREDETERMINED',
            'BEHAVIOURAL_ESTIMATE',
            'DISBURSEMENT_TIMING',
            'NEGOTIATED')),
    -- 04 § 2.7 routed_mechanism vocabulary, complete.
    CONSTRAINT ck_lifecycle_event_routed_mechanism
        CHECK (routed_mechanism IN (
            'RESET',
            'CATCH_UP',
            'MODIFICATION_TEST',
            'DERECOGNITION',
            'NONE')),
    CONSTRAINT ck_lifecycle_event_prepayment_variant
        CHECK (prepayment_variant IS NULL
               OR prepayment_variant IN ('TENOR_REDUCED', 'EMI_REDUCED')),
    -- A routed CATCH_UP without its amount and its original rate cannot satisfy CU-1 or
    -- CU-2, so the row is rejected rather than stored half-formed.
    CONSTRAINT ck_lifecycle_event_catch_up_complete
        CHECK (routed_mechanism <> 'CATCH_UP'
               OR (catch_up_amount IS NOT NULL AND original_eir_used IS NOT NULL)),
    CONSTRAINT ck_lifecycle_event_ten_percent_result
        CHECK (ten_percent_test_result IS NULL
               OR ten_percent_test_result IN (
                   'ABOVE_THRESHOLD',
                   'BELOW_THRESHOLD',
                   'NOT_APPLICABLE')),
    -- FR-511: the conclusion is a human decision, so it does not exist without its owner
    -- and the moment they took it.
    CONSTRAINT ck_lifecycle_event_conclusion_owned
        CHECK (substantiality_conclusion IS NULL
               OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_lifecycle_event_conclusion_vocabulary
        CHECK (substantiality_conclusion IS NULL
               OR substantiality_conclusion IN ('SUBSTANTIAL', 'NOT_SUBSTANTIAL')),
    CONSTRAINT ck_lifecycle_event_sequence_non_negative
        CHECK (sequence_within_date >= 0),
    CONSTRAINT ck_lifecycle_event_system_time
        CHECK (superseded_at IS NULL OR superseded_at >= recorded_at)
);

CREATE INDEX ix_lifecycle_event_contract_date
    ON lifecycle_event (contract_id, event_date, sequence_within_date);
CREATE INDEX ix_lifecycle_event_mechanism
    ON lifecycle_event (routed_mechanism, event_date);


-- =====================================================================================
-- SECTION 3 — The sub-ledger
--
-- PERIOD_BALANCE and JOURNAL_ENTRY are the two range-partitioned tables of 04 § 4, and
-- the partitioning is load-bearing rather than decorative: 10M contracts x 12 periods x
-- ~30 columns is roughly 120M PERIOD_BALANCE rows a year, and partition pruning is what
-- keeps close-period queries tractable.
--
-- Partition granularity is one partition per period, not per year. Three reasons, in
-- order of weight: a close-period query names one period and prunes to a single ~10M-row
-- partition instead of a 120M-row one; 04 § 4 sets a closed period's partitions
-- read-only, which is only expressible if a period owns its own partitions; and the
-- retention rule archives closed-period partitions to columnar storage, which is a
-- detach-and-move of one partition rather than a delete of one twelfth of a table.
--
-- Partition names follow <table>_p<YYYYMM> exactly, because SECTION 5's create and close
-- helpers derive the name from the period id. A hand-named partition that does not
-- follow the convention will not be found by them.
-- =====================================================================================

-- PERIOD_BALANCE (04 § 2.8) — one row per contract per period per book. The reporting
-- workhorse and the row a trace query lands on.
--
-- Two legs, deliberately. opening_gca/closing_gca is the EIR leg; opening_contractual/
-- closing_contractual is the contractual leg that ties to the CBS. The difference
-- between them IS the unamortised fee balance, which is why the derived columns below
-- are GENERATED rather than written by the engine.
--
-- **INV-4 as a generated column.** 04 § 2.8: unamortised_fee is derived as
-- closing_contractual − closing_gca, "never accumulated independently", and
-- fee_amortised as eir_interest − contractual_interest. An accumulator can drift from
-- the balances it describes; a derivation cannot. Expressing them as GENERATED ALWAYS
-- … STORED moves invariant INV-4 from something the sweep checks after the fact to
-- something the database cannot represent a violation of. There is no INSERT that can
-- put a drifted unamortised fee balance into this table.
--
-- product_id is denormalised onto the row. It is a property of the contract, not of the
-- balance, and normally would not be here — but 04 § 4 asks for a (period_id,
-- product_id) index for reporting rollups, and a rollup that has to join 120M rows to
-- CONTRACT to find the product defeats the pruning the partitioning bought. The
-- denormalisation is the price of that index, and it is stated rather than accidental.
CREATE TABLE period_balance (
    balance_id                      UUID             ,
    contract_id                     UUID             NOT NULL,
    period_id                       INTEGER          NOT NULL,
    book_id                         TEXT             NOT NULL,
    run_id                          UUID             NOT NULL,
    -- Denormalised from CONTRACT for the (period_id, product_id) rollup index.
    product_id                      UUID  ,
    -- The EIR leg.
    opening_gca                     NUMERIC(24,6)    NOT NULL,
    closing_gca                     NUMERIC(24,6)    NOT NULL,
    -- The contractual leg. Ties to the CBS.
    opening_contractual             NUMERIC(24,6)    NOT NULL,
    closing_contractual             NUMERIC(24,6)    NOT NULL,
    -- The two interest legs.
    eir_interest                    NUMERIC(24,6)    NOT NULL,
    contractual_interest            NUMERIC(24,6)    NOT NULL,
    -- Derived, not stored. See the INV-4 note above.
    fee_amortised                   NUMERIC(24,6)
        GENERATED ALWAYS AS (eir_interest - contractual_interest) STORED,
    unamortised_fee                 NUMERIC(24,6)
        GENERATED ALWAYS AS (closing_contractual - closing_gca) STORED,
    cash_received                   NUMERIC(24,6)    NOT NULL DEFAULT 0,
    catch_up_amount                 NUMERIC(24,6)    NOT NULL DEFAULT 0,
    -- Impairment interaction (04 § 2.8, spec 03 § 7).
    stage                           SMALLINT         NOT NULL,
    allowance                       NUMERIC(24,6)    NOT NULL DEFAULT 0,
    -- IFRS 9's Stage 3 net-basis interest, AC × EIR. Not in 04's minimum field list; it
    -- is here because the four-way Stage 3 reconciliation (FR-602…606) needs it and
    -- because it is the (b) of invariant ST-2, which without it has nothing to add to
    -- the unwind.
    net_basis_interest              NUMERIC(24,6),
    -- allowance × EIR. Computed, NOT recognised (FR-603). ACPIR does not recognise
    -- income on a Stage 3 asset, but ECL is a present-value measure discounted at the
    -- EIR (ACPIR 50) so the discount unwinds mechanically whether or not anything is
    -- recognised. The engine's job is to compute it and keep it out of the P&L.
    shadow_unwind                   NUMERIC(24,6),
    -- Zero when stage 3 (invariant S3-2), enforced below.
    recognised_interest_income      NUMERIC(24,6)    NOT NULL,
    suspense_movement               NUMERIC(24,6)    NOT NULL DEFAULT 0,
    -- Both retained (FR-609, invariant PF-1). The floor is an ACPIR 82 overlay and the
    -- pre-floor figure is the ECL model's own answer; reporting only the post-floor
    -- number loses the ability to say how much of the allowance is the floor.
    ecl_pre_floor                   NUMERIC(24,6),
    ecl_post_floor                  NUMERIC(24,6),
    -- Hedge interaction (04 § 2.11).
    basis_adjustment_amortised      NUMERIC(24,6)    NOT NULL DEFAULT 0,
    -- The solve this row amortised against, and the rate it used. Held on the row
    -- because a trace query answers "which rate produced this interest" without
    -- reconstructing the event stream, and because EIR_COMPUTATION is never archived
    -- (04 § 4) so the reference stays resolvable for the life of the ledger.
    eir_computation_id              UUID  ,
    rate_periodic_used              NUMERIC(20,12),
    CONSTRAINT pk_period_balance
        PRIMARY KEY (balance_id, period_id),
    -- One row per contract per period per book per run. The partition key is part of it
    -- because PostgreSQL requires a unique constraint on a partitioned table to include
    -- the partition key — and here that is no imposition: period_id belongs in the
    -- natural key anyway.
    CONSTRAINT uq_period_balance_natural
        UNIQUE (contract_id, period_id, book_id, run_id),
    CONSTRAINT fk_period_balance_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    -- Composite, not just (run_id), and this is the constraint that matters most on this
    -- table.
    --
    -- With separate foreign keys on run_id and period_id, a run over April can insert a
    -- row stamped June: both parents exist, both checks pass, and the row lands in June's
    -- partition. Nothing downstream recovers from that, and the damage is not confined to
    -- one row — the close gate finds a period's exceptions through
    -- amortisation_run.period_id, so a misfiled balance is a period whose figures were
    -- produced by a run the close never examined. Pointing at (run_id, period_id) makes
    -- the two agree by construction.
    CONSTRAINT fk_period_balance_run_period
        FOREIGN KEY (run_id, period_id)
            REFERENCES amortisation_run (run_id, period_id),
    CONSTRAINT ck_period_balance_stage
        CHECK (stage IN (1, 2, 3)),
    -- Invariant S3-2: recognised interest income on a Stage 3 contract is 0. This is the
    -- India divergence from IFRS 9 and it has no analogue to fall back on, so it is a
    -- constraint and not a report.
    CONSTRAINT ck_period_balance_s3_2_no_stage3_income
        CHECK (stage <> 3 OR recognised_interest_income = 0),
    -- FR-603: the shadow unwind is computed on a Stage 3 row, always. A Stage 3 row
    -- without it means the unwind was not computed, which is the failure mode this
    -- catches — silently recognising nothing looks identical to correctly recognising
    -- nothing.
    CONSTRAINT ck_period_balance_stage3_unwind_computed
        CHECK (stage <> 3 OR shadow_unwind IS NOT NULL),
    CONSTRAINT ck_period_balance_allowance_non_negative
        CHECK (allowance >= 0),
    -- PF-1: both floors or neither. One of the two present means the pair was not
    -- retained and the comparison FR-609 asks for cannot be made.
    CONSTRAINT ck_period_balance_floor_duality
        CHECK ((ecl_pre_floor IS NULL) = (ecl_post_floor IS NULL)),
    -- PF-1, the other half. "Both retained" is only the storage requirement; the
    -- substantive rule is that the floor RAISES the reported figure or does nothing, and
    -- the duality check above is satisfied by a post-floor number BELOW the pre-floor
    -- one — which is not a floor. Added after FloorApplication was written and asserted
    -- the same rule in Java: one rule stated in two places with only one of them stating
    -- it is this schema's recurring defect, and the pair of columns is exactly where a
    -- caller that computed the provision elsewhere would land.
    CONSTRAINT ck_period_balance_floor_raises
        CHECK (ecl_pre_floor IS NULL OR ecl_post_floor >= ecl_pre_floor)
) PARTITION BY RANGE (period_id);

COMMENT ON TABLE period_balance IS
    '04 s2.8 - the sub-ledger row, one per contract per period per book per run. '
    'Range-partitioned by period_id, one partition per period (04 s4). fee_amortised '
    'and unamortised_fee are generated, which makes invariant INV-4 impossible to '
    'represent a violation of rather than merely checked after the fact.';
COMMENT ON COLUMN period_balance.unamortised_fee IS
    'Derived as closing_contractual - closing_gca (invariant INV-4). Never an '
    'independent accumulator: an accumulator can drift from the balances it describes.';

-- The first ACPIR period, spelled out so the partition structure is visible in the DDL
-- itself and not only in the loop that follows in SECTION 5. April 2027 is the first
-- period on the new basis: ACPIR is effective 1 April 2027 and the fair-value transition
-- (ACPIR 19) lands the day before.
CREATE TABLE period_balance_p202704
    PARTITION OF period_balance
    FOR VALUES FROM (202704) TO (202705);

-- The DEFAULT partition is a safety net, not a destination. Without it an insert for an
-- uncreated period fails the run; with it the row lands somewhere findable. But rows must
-- be moved out before the real partition can be attached — ATTACH scans the default and
-- refuses if it holds a row belonging in the incoming bound — so anything in here is an
-- operational defect to clear, and vw_period_balance_in_default exists to surface it.
CREATE TABLE period_balance_p_default
    PARTITION OF period_balance DEFAULT;

-- 04 § 4 indexing, all three named shapes. Created on the partitioned parent so every
-- present and future partition inherits them.
--
-- (contract_id, period_id) is the trace path: one contract's history across periods,
-- which is the query an auditor drives and the one a reconciliation break is
-- investigated with.
CREATE INDEX ix_period_balance_contract_period
    ON period_balance (contract_id, period_id);
-- (period_id, product_id) is the reporting rollup. Leading with period_id lets the
-- planner prune to the period's partition and then walk the product order within it.
CREATE INDEX ix_period_balance_period_product
    ON period_balance (period_id, product_id);
-- Run monitoring and, more often, run cleanup: a failed run's rows are found by run_id.
CREATE INDEX ix_period_balance_run
    ON period_balance (run_id);
-- Stage 3 populations are read as a set every close (suppression, suspense, the four-way
-- reconciliation). Partial rather than full because Stage 3 is a small fraction of the
-- book and an index over the other 97% of rows would be dead weight.
CREATE INDEX ix_period_balance_stage3
    ON period_balance (period_id, contract_id)
    WHERE stage = 3;

-- JOURNAL_ENTRY — the double-entry leg of the sub-ledger.
--
-- 04 names this table in the entity map (PERIOD_BALANCE ||--o{ JOURNAL_ENTRY : posts)
-- and in the § 4 partitioning rule, but gives it no field list. The fields below are the
-- minimum that invariant SL-2 and FR-802 require, and they are flagged as inferred
-- rather than specified. Journal construction proper belongs to eir-gl in Phase 5; what
-- belongs here is the partitioned table 04 § 4 names, so that the close-period read-only
-- rule covers the postings and not only the balances.
--
-- Amount is unsigned and the side is a separate column rather than a signed amount.
-- Signed amounts make SL-2 a sum-to-zero check that passes when both legs are wrong in
-- opposite directions, and make "total debits posted this run" a filtered aggregate
-- instead of a sum.
CREATE TABLE journal_entry (
    entry_id                        UUID             ,
    period_id                       INTEGER          NOT NULL,
    run_id                          UUID             NOT NULL,
    contract_id                     UUID             NOT NULL,
    balance_id                      UUID  ,
    book_id                         TEXT             NOT NULL,
    posted_on                       DATE             NOT NULL,
    account_code                    TEXT             NOT NULL,
    dr_cr                           TEXT             NOT NULL,
    amount                          NUMERIC(24,6)    NOT NULL,
    currency                        CHAR(3)          NOT NULL,
    narrative                       TEXT,
    journal_batch_ref               TEXT,
    reversal_of_entry_id            UUID  ,
    CONSTRAINT pk_journal_entry
        PRIMARY KEY (entry_id, period_id),
    CONSTRAINT fk_journal_entry_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    -- Composite for the same reason as fk_period_balance_run_period: a posting cannot be
    -- stamped with a period other than the one its run was for.
    CONSTRAINT fk_journal_entry_run_period
        FOREIGN KEY (run_id, period_id)
            REFERENCES amortisation_run (run_id, period_id),
    CONSTRAINT ck_journal_entry_side
        CHECK (dr_cr IN ('DR', 'CR')),
    CONSTRAINT ck_journal_entry_amount_unsigned
        CHECK (amount >= 0),
    -- ISO 4217, upper case. The presentation scale of the contract's currency is a
    -- CONTRACT concern (04 § 2.1); what matters here is that the code is well-formed.
    CONSTRAINT ck_journal_entry_currency
        CHECK (currency ~ '^[A-Z]{3}$')
) PARTITION BY RANGE (period_id);

COMMENT ON TABLE journal_entry IS
    '04 s4 partitioning rule and s1 entity map. Field list inferred from invariant SL-2 '
    'and FR-802 - 04 specifies none. Journal construction belongs to eir-gl, roadmap '
    'Phase 5.';

CREATE TABLE journal_entry_p202704
    PARTITION OF journal_entry
    FOR VALUES FROM (202704) TO (202705);
CREATE TABLE journal_entry_p_default
    PARTITION OF journal_entry DEFAULT;

CREATE INDEX ix_journal_entry_contract_period
    ON journal_entry (contract_id, period_id);
CREATE INDEX ix_journal_entry_run
    ON journal_entry (run_id, period_id);
CREATE INDEX ix_journal_entry_account
    ON journal_entry (period_id, account_code);


-- =====================================================================================
-- SECTION 4 — Impairment interaction, pools and hedges
-- =====================================================================================

-- STAGE_ASSIGNMENT (04 § 2.9) — the ECL engine's verdict, as received.
--
-- ecl_engine_version is NOT NULL, and that is the whole point of the table. Re-running a
-- period with today's ECL output produces a different answer and invariant DT-1 fails;
-- the version is what makes a Stage 3 replay deterministic. received_at is when the
-- engine learned it (system time, 04 § 5) and is supplied, not defaulted, for the same
-- reason.
CREATE TABLE stage_assignment (
    stage_assignment_id             UUID             ,
    contract_id                     UUID             NOT NULL,
    period_id                       INTEGER          NOT NULL,
    stage                           SMALLINT         NOT NULL,
    allowance                       NUMERIC(24,6)    NOT NULL,
    ecl_engine_version              TEXT             NOT NULL,
    ecl_model_run_ref               TEXT,
    received_at                     TIMESTAMPTZ      NOT NULL,
    CONSTRAINT pk_stage_assignment
        PRIMARY KEY (stage_assignment_id),
    CONSTRAINT uq_stage_assignment_contract_period
        UNIQUE (contract_id, period_id),
    CONSTRAINT fk_stage_assignment_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    CONSTRAINT ck_stage_assignment_stage
        CHECK (stage IN (1, 2, 3)),
    CONSTRAINT ck_stage_assignment_allowance_non_negative
        CHECK (allowance >= 0)
);

-- SUSPENSE_ENTRY (04 § 2.9) — a first-class ledger object, not a memorandum (FR-604).
--
-- ACPIR suppresses income on a Stage 3 asset, and the contractual interest still billed
-- has to go somewhere. Under the Indian treatment it goes to interest-in-suspense, and a
-- memorandum note cannot be reconciled, cannot be aged, and cannot be reported. So it is
-- a ledger object with an opening balance, movements and a closing balance, and the
-- continuity identity below is enforced rather than asserted afterwards.
CREATE TABLE suspense_entry (
    suspense_entry_id               UUID             ,
    contract_id                     UUID             NOT NULL,
    period_id                       INTEGER          NOT NULL,
    book_id                         TEXT             NOT NULL,
    opening_balance                 NUMERIC(24,6)    NOT NULL,
    contractual_interest_suspended  NUMERIC(24,6)    NOT NULL DEFAULT 0,
    released_on_recovery            NUMERIC(24,6)    NOT NULL DEFAULT 0,
    written_off                     NUMERIC(24,6)    NOT NULL DEFAULT 0,
    closing_balance                 NUMERIC(24,6)    NOT NULL,
    CONSTRAINT pk_suspense_entry
        PRIMARY KEY (suspense_entry_id),
    CONSTRAINT uq_suspense_entry_contract_period_book
        UNIQUE (contract_id, period_id, book_id),
    CONSTRAINT fk_suspense_entry_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    -- Continuity. Every movement column defaults to 0 rather than NULL precisely so that
    -- this identity always evaluates: a NULL movement makes the CHECK NULL, which
    -- passes, and a constraint that passes on missing data is worse than no constraint
    -- because it reads as protection.
    CONSTRAINT ck_suspense_entry_continuity
        CHECK (closing_balance = opening_balance
                                 + contractual_interest_suspended
                                 - released_on_recovery
                                 - written_off),
    CONSTRAINT ck_suspense_entry_balances_non_negative
        CHECK (opening_balance >= 0 AND closing_balance >= 0),
    -- The movements are magnitudes and the direction is the column's name. Without this
    -- the continuity check above is satisfied by a recovery posted as a negative and a
    -- suspension posted as a negative recovery: same closing balance, different ledger,
    -- and only one of them reconciles to the cash book. SuspenseLedger refuses the same
    -- thing in Java, and the constraint that mattered was the one the schema was missing.
    CONSTRAINT ck_suspense_entry_movements_non_negative
        CHECK (contractual_interest_suspended >= 0
               AND released_on_recovery >= 0
               AND written_off >= 0)
);

CREATE INDEX ix_suspense_entry_contract_period
    ON suspense_entry (contract_id, period_id);

-- POOL (04 § 2.10) — Tier 2 pooled measurement.
--
-- A pool is a closed cohort, always: members are never added after the EIR is struck.
-- Adding a member to a struck pool would change a rate that has already been used to
-- recognise income, which is a restatement dressed up as a data load, so
-- is_closed_cohort is constrained to TRUE rather than merely defaulted to it.
--
-- The back-test is mandatory quarterly (FR-409). A breach forces the pool to
-- contract-level measurement — it does not stop it, which is why
-- ExceptionCategory.POOL_BACKTEST_BREACH reports stopsTheContract() false — and the
-- constraint below makes the forcing automatic rather than discretionary: a recorded
-- variance above the threshold with forced_to_contract_level still false is a row this
-- table will not hold.
CREATE TABLE pool (
    pool_id                         UUID             ,
    definition_version_id           TEXT             NOT NULL,
    struck_on                       DATE             NOT NULL,
    -- Product × origination month × rate band × tenor band (04 § 2.10). The criteria are
    -- held both as the structured bands the engine matches on and as the JSONB record of
    -- the approved definition, because the approved artefact is what an auditor reads and
    -- the bands are what the matcher runs.
    homogeneity_criteria            JSONB            NOT NULL,
    product_id                      UUID  ,
    origination_month               INTEGER,
    rate_band_lower                 NUMERIC(20,12),
    rate_band_upper                 NUMERIC(20,12),
    tenor_band_lower_months         INTEGER,
    tenor_band_upper_months         INTEGER,
    is_closed_cohort                BOOLEAN          NOT NULL DEFAULT TRUE,
    pool_eir                        NUMERIC(20,12),
    member_count                    BIGINT,
    last_backtest_on                DATE,
    backtest_variance               NUMERIC(20,12),
    backtest_threshold              NUMERIC(20,12),
    forced_to_contract_level        BOOLEAN          NOT NULL DEFAULT FALSE,
    forced_on                       DATE,
    CONSTRAINT pk_pool
        PRIMARY KEY (pool_id),
    CONSTRAINT fk_pool_definition_version
        FOREIGN KEY (definition_version_id) REFERENCES policy_version (policy_version_id),
    -- 04 § 2.10: "Always true."
    CONSTRAINT ck_pool_closed_cohort_always
        CHECK (is_closed_cohort),
    CONSTRAINT ck_pool_origination_month_shape
        CHECK (origination_month IS NULL
               OR (origination_month BETWEEN 190001 AND 999912
                   AND origination_month % 100 BETWEEN 1 AND 12)),
    CONSTRAINT ck_pool_rate_band
        CHECK (rate_band_lower IS NULL
               OR rate_band_upper IS NULL
               OR rate_band_upper >= rate_band_lower),
    CONSTRAINT ck_pool_tenor_band
        CHECK (tenor_band_lower_months IS NULL
               OR tenor_band_upper_months IS NULL
               OR tenor_band_upper_months >= tenor_band_lower_months),
    -- FR-409: a recorded breach forces contract level.
    CONSTRAINT ck_pool_backtest_breach_forces_contract_level
        CHECK (backtest_variance IS NULL
               OR backtest_threshold IS NULL
               OR backtest_variance <= backtest_threshold
               OR forced_to_contract_level),
    CONSTRAINT ck_pool_forced_dated
        CHECK (NOT forced_to_contract_level OR forced_on IS NOT NULL)
);

COMMENT ON TABLE pool IS
    '04 s2.10 - Tier 2 pooled measurement. Closed cohort, always. Quarterly back-test '
    'mandatory (FR-409); a breach forces contract-level measurement rather than stopping '
    'the pool.';

-- HEDGE_RELATIONSHIP (04 § 2.11).
--
-- designated_risk admits exactly one value. FR-701 permits benchmark interest rate risk
-- only, never credit spread, and the way to encode "never" is to leave the other value
-- out of the vocabulary rather than to add a rule that rejects it. A column that can hold
-- 'CREDIT_SPREAD' will eventually hold it.
--
-- amortisation_schedule_id is NOT NULL where status is DISCONTINUED — invariant HB-1 as a
-- constraint. 04 § 2.11 is explicit about why this is a database constraint and not only
-- a runtime check: the frozen basis adjustment is the most commonly observed defect in
-- this area, and it persists silently for years once established. A runtime check runs
-- when the code runs; a constraint runs when the row is written, including by the
-- data-fix script nobody reviewed.
CREATE TABLE hedge_relationship (
    relationship_id                 UUID             ,
    hedged_contract_id              UUID  ,
    hedged_portfolio_id             UUID  ,
    hedging_instrument_ref          TEXT             NOT NULL,
    designated_risk                 TEXT             NOT NULL,
    level                           TEXT             NOT NULL,
    status                          TEXT             NOT NULL,
    designated_on                   DATE             NOT NULL,
    discontinued_on                 DATE,
    amortisation_schedule_id        UUID  ,
    CONSTRAINT pk_hedge_relationship
        PRIMARY KEY (relationship_id),
    -- Exactly one hedged item: an instrument or a portfolio, never both and never
    -- neither.
    CONSTRAINT ck_hedge_relationship_one_hedged_item
        CHECK (num_nonnulls(hedged_contract_id, hedged_portfolio_id) = 1),
    -- FR-701. Benchmark rate risk only.
    CONSTRAINT ck_hedge_relationship_designated_risk
        CHECK (designated_risk = 'BENCHMARK_INTEREST_RATE'),
    CONSTRAINT ck_hedge_relationship_level
        CHECK (level IN ('INSTRUMENT', 'PORTFOLIO')),
    -- The IAS 39 89-94 portfolio carve-out hedges a portfolio; an instrument-level
    -- designation hedges an instrument. Letting the two disagree makes `level`
    -- decorative.
    CONSTRAINT ck_hedge_relationship_level_matches_item
        CHECK ((level = 'PORTFOLIO') = (hedged_portfolio_id IS NOT NULL)),
    CONSTRAINT ck_hedge_relationship_status
        CHECK (status IN ('LIVE', 'DISCONTINUED')),
    -- Invariant HB-1.
    CONSTRAINT ck_hedge_relationship_hb1_discontinued_has_schedule
        CHECK (status <> 'DISCONTINUED' OR amortisation_schedule_id IS NOT NULL),
    CONSTRAINT ck_hedge_relationship_discontinued_dated
        CHECK ((status = 'DISCONTINUED') = (discontinued_on IS NOT NULL)),
    CONSTRAINT ck_hedge_relationship_dates
        CHECK (discontinued_on IS NULL OR discontinued_on >= designated_on)
);

COMMENT ON CONSTRAINT ck_hedge_relationship_hb1_discontinued_has_schedule
    ON hedge_relationship IS
    'Invariant HB-1 as a constraint. A discontinued hedge whose basis adjustment has no '
    'amortisation schedule freezes that adjustment on the hedged item indefinitely, and '
    'does so silently. 04 s2.11 makes this a schema constraint deliberately.';

-- BASIS_ADJUSTMENT (04 § 2.11) — the hedged item's fair-value adjustment and its
-- amortisation. Once the relationship is discontinued the adjustment must run off to zero
-- over the remaining life, and the continuity constraint is what makes a frozen
-- adjustment visible in the data rather than only in a report nobody runs.
CREATE TABLE basis_adjustment (
    basis_adjustment_id             UUID             ,
    relationship_id                 UUID             NOT NULL,
    period_id                       INTEGER          NOT NULL,
    opening_balance                 NUMERIC(24,6)    NOT NULL,
    fair_value_change_recognised    NUMERIC(24,6)    NOT NULL DEFAULT 0,
    amortised_in_period             NUMERIC(24,6)    NOT NULL DEFAULT 0,
    closing_balance                 NUMERIC(24,6)    NOT NULL,
    amortisation_method             TEXT,
    amortisation_schedule_id        UUID  ,
    CONSTRAINT pk_basis_adjustment
        PRIMARY KEY (basis_adjustment_id),
    CONSTRAINT uq_basis_adjustment_relationship_period
        UNIQUE (relationship_id, period_id),
    CONSTRAINT fk_basis_adjustment_relationship
        FOREIGN KEY (relationship_id) REFERENCES hedge_relationship (relationship_id),
    CONSTRAINT fk_basis_adjustment_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    CONSTRAINT ck_basis_adjustment_continuity
        CHECK (closing_balance = opening_balance
                                 + fair_value_change_recognised
                                 - amortised_in_period),
    CONSTRAINT ck_basis_adjustment_method
        CHECK (amortisation_method IS NULL
               OR amortisation_method IN ('EIR_RECALCULATED', 'STRAIGHT_LINE'))
);


-- =====================================================================================
-- SECTION 5 — Exception queue, and the closed-period read-only mechanism
-- =====================================================================================

-- EXCEPTION (04 § 3) — the quarantine queue.
--
-- Every category is a hard stop for that contract, never a silent default (FR-905). The
-- alternative to raising an exception is not "carry on with a reasonable assumption"; it
-- is publishing a wrong rate that nothing downstream can distinguish from a right one.
-- ACPIR forbids manual override (ADR-0008), so quarantining the contract and naming the
-- defect is the only available response to bad input.
--
-- The ten categories below are exactly the ten of 04 § 3 and of eir-policy's
-- ExceptionCategory. Two of them do not stop their contract — STALE_EQUIVALENCE_TEST
-- demotes a Tier 3 population to Tier 2 and POOL_BACKTEST_BREACH forces a pool to
-- contract level — and stops_the_contract is a generated column rather than a written
-- one so that it cannot disagree with the category it derives from, exactly as
-- ExceptionCategory.stopsTheContract() cannot.
--
-- There is no blocks_close column: every category blocks the close (04 § 3), which makes
-- a column holding TRUE on every row a place for a defect to hide rather than a fact.
-- ExceptionCategory.blocksClose() returns a constant for the same reason.
CREATE TABLE exception (
    exception_id                    UUID             ,
    raised_by_run_id                UUID             NOT NULL,
    -- A contract-level exception names a contract; a pool back-test breach names a pool.
    -- At least one, because an exception nothing can be traced to cannot be resolved.
    contract_id                     UUID  ,
    pool_id                         UUID  ,
    category                        TEXT             NOT NULL,
    detail                          TEXT             NOT NULL,
    payload_ref                     TEXT,
    status                          TEXT             NOT NULL,
    raised_at                       TIMESTAMPTZ      NOT NULL,
    resolved_by                     TEXT,
    resolved_at                     TIMESTAMPTZ,
    resolution_note                 TEXT,
    -- "Unresolved exceptions block the close unless explicitly accepted with approval"
    -- (04 § 3). Acceptance is therefore a distinct status with its own named approver and
    -- reference, not a resolution — the two are different facts, and conflating them
    -- loses the ability to report how much of a close was accepted rather than fixed.
    accepted_by                     TEXT,
    accepted_at                     TIMESTAMPTZ,
    approval_ref                    TEXT,
    -- Mirrors ExceptionCategory.stopsTheContract(). Generated so the two cannot drift.
    stops_the_contract              BOOLEAN
        GENERATED ALWAYS AS (category NOT IN ('STALE_EQUIVALENCE_TEST',
                                              'POOL_BACKTEST_BREACH')) STORED,
    CONSTRAINT pk_exception
        PRIMARY KEY (exception_id),
    CONSTRAINT fk_exception_run
        FOREIGN KEY (raised_by_run_id) REFERENCES amortisation_run (run_id),
    CONSTRAINT fk_exception_pool
        FOREIGN KEY (pool_id) REFERENCES pool (pool_id),
    -- The ten categories of 04 § 3, in the order 04 lists them.
    CONSTRAINT ck_exception_category
        CHECK (category IN (
            'UNMAPPED_FEE_CODE',
            'MISSING_COST_FUNCTION',
            'NO_SOLUTION',
            'MULTIPLE_ROOTS',
            'MISSING_MANDATORY_FIELD',
            'PENAL_CHARGE_REJECTED',
            'IC1_BREACH',
            'STALE_EQUIVALENCE_TEST',
            'DISCONTINUED_HEDGE_NO_SCHEDULE',
            'POOL_BACKTEST_BREACH')),
    CONSTRAINT ck_exception_status
        CHECK (status IN ('OPEN', 'RESOLVED', 'ACCEPTED_WITH_APPROVAL')),
    CONSTRAINT ck_exception_has_subject
        CHECK (num_nonnulls(contract_id, pool_id) >= 1),
    CONSTRAINT ck_exception_resolution_complete
        CHECK (status <> 'RESOLVED'
               OR (resolved_by IS NOT NULL
                   AND resolved_at IS NOT NULL
                   AND resolution_note IS NOT NULL)),
    CONSTRAINT ck_exception_acceptance_approved
        CHECK (status <> 'ACCEPTED_WITH_APPROVAL'
               OR (accepted_by IS NOT NULL
                   AND accepted_at IS NOT NULL
                   AND approval_ref IS NOT NULL))
);

COMMENT ON TABLE exception IS
    '04 s3 - the exception queue. Ten categories, mirroring '
    'com.crisil.eir.policy.exception.ExceptionCategory. Every category is a hard stop '
    'for that contract and blocks the close (FR-905).';

-- 04 § 4's (run_id, status) shape, where it earns its keep: the close asks "does this run
-- have anything still OPEN" and the answer must not require reading every exception the
-- run raised.
CREATE INDEX ix_exception_run_status
    ON exception (raised_by_run_id, status);
CREATE INDEX ix_exception_contract
    ON exception (contract_id)
    WHERE contract_id IS NOT NULL;
CREATE INDEX ix_exception_open_by_category
    ON exception (category)
    WHERE status = 'OPEN';

-- -------------------------------------------------------------------------------------
-- Closed-period read-only enforcement (04 § 4)
--
-- PostgreSQL has no per-table read-only flag, so "a closed period's partitions are set
-- read-only" has to be built. Three mechanisms, and the first is the one that enforces:
--
--   1. A BEFORE row trigger on the partitioned parents, propagated to every partition,
--      which refuses any INSERT, UPDATE or DELETE touching a period whose status is
--      CLOSED. This is the mechanism that holds regardless of who connects and with what
--      privileges, including a superuser running an unreviewed data fix — which is the
--      case FR-902 actually has to survive. It costs one lookup per written row: a
--      primary-key probe into a table with one row per month, served from shared buffers.
--
--   2. A guard on ACCOUNTING_PERIOD itself, so that CLOSED cannot be un-set. Mechanism 1
--      reads accounting_period.status, and without this its whole protection is one
--      UPDATE away: reopen the period, rewrite the balances, close it again. The same
--      unreviewed data fix that mechanism 1 exists to survive would otherwise disable
--      it in a line. This is FR-902's "CLOSED is immutable" at its source of truth
--      rather than at its consequence.
--
--   3. A REVOKE of INSERT/UPDATE/DELETE/TRUNCATE on the closed period's partitions from
--      the application role, issued by ledger_close_accounting_period below. Its scope is
--      narrower than it looks and the narrowness is worth stating, because the obvious
--      reading of it is wrong: PostgreSQL checks DML privileges on the *parent* a
--      statement names, not on the partition a row routes to, so this does NOT stop
--      `INSERT INTO period_balance` — which is the application's normal write path.
--      Verified on PostgreSQL 16, not assumed. What it does cover is direct-to-partition
--      access — bulk loaders, COPY, archival and detach tooling all name the partition —
--      and TRUNCATE of the partition, which row triggers never see. So it is defence in
--      depth against a second class of writer, not a cheaper substitute for the trigger,
--      and the trigger is not optional.
-- -------------------------------------------------------------------------------------

CREATE FUNCTION ledger_reject_write_to_closed_period()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ledger_reject_write$
DECLARE
    v_period_id INTEGER;
    v_status    TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_period_id := OLD.period_id;
    ELSE
        v_period_id := NEW.period_id;
    END IF;

    SELECT ap.status
      INTO v_status
      FROM accounting_period ap
     WHERE ap.period_id = v_period_id;

    IF v_status = 'CLOSED' THEN
        RAISE EXCEPTION
            'accounting period % is CLOSED: %.% is read-only (04 s4, FR-902). A '
            'correction to a closed period creates a restatement artefact in the '
            'current period referencing the original; it does not rewrite history.',
            v_period_id, TG_TABLE_SCHEMA, TG_TABLE_NAME
            USING ERRCODE = 'read_only_sql_transaction';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$ledger_reject_write$;

COMMENT ON FUNCTION ledger_reject_write_to_closed_period() IS
    'Enforces 04 s4 "a closed period''s partitions are set read-only" and FR-902 '
    '"CLOSED is immutable" at row level on the partitioned ledger tables.';

CREATE TRIGGER trg_period_balance_closed_period
    BEFORE INSERT OR UPDATE OR DELETE ON period_balance
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_write_to_closed_period();

CREATE TRIGGER trg_journal_entry_closed_period
    BEFORE INSERT OR UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_write_to_closed_period();

-- Mechanism 2: CLOSED is a terminal state on the row that says so.
--
-- The columns that record who closed the period and when, and the system-time boundary a
-- replay must read as at, are frozen with it. A restatement lives in the current period
-- and references the original (FR-902); it does not edit the closed period's attestation.
CREATE FUNCTION ledger_reject_reopen_of_closed_period()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $ledger_reject_reopen$
BEGIN
    IF OLD.status <> 'CLOSED' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'accounting period % is CLOSED and cannot be deleted (FR-902)', OLD.period_id
            USING ERRCODE = 'read_only_sql_transaction';
    END IF;

    IF NEW.status <> 'CLOSED'
       OR NEW.closed_at IS DISTINCT FROM OLD.closed_at
       OR NEW.closed_by IS DISTINCT FROM OLD.closed_by
       OR NEW.version_cutoff_at IS DISTINCT FROM OLD.version_cutoff_at
       OR NEW.period_start_date IS DISTINCT FROM OLD.period_start_date
       OR NEW.period_end_date IS DISTINCT FROM OLD.period_end_date THEN
        RAISE EXCEPTION
            'accounting period % is CLOSED: CLOSED is immutable (FR-902), and its '
            'closure attestation is what a replay of the period reads. A correction '
            'creates a restatement artefact in the current period referencing the '
            'original.', OLD.period_id
            USING ERRCODE = 'read_only_sql_transaction';
    END IF;

    RETURN NEW;
END;
$ledger_reject_reopen$;

COMMENT ON FUNCTION ledger_reject_reopen_of_closed_period() IS
    'FR-902 at its source of truth. Without it the closed-period read-only trigger on '
    'the ledger tables is one UPDATE away from being disabled, because that trigger '
    'reads accounting_period.status.';

CREATE TRIGGER trg_accounting_period_closed_is_terminal
    BEFORE UPDATE OR DELETE ON accounting_period
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_reopen_of_closed_period();

-- Creates the two ledger partitions for a period. The name is derived from the period id,
-- which is what lets the close routine find them again without parsing partition bounds
-- out of the catalogue.
--
-- All or nothing: the function runs in one transaction, so a failure on the second parent
-- rolls back the first. That is the behaviour to want, and it is also why the check for
-- rows in the DEFAULT partition runs over both parents before either partition is
-- created — the diagnosis should name the problem, not surface as PostgreSQL's partition
-- scan error on whichever table happened to be attempted first.
CREATE FUNCTION ledger_create_period_partitions(p_period_id INTEGER)
RETURNS VOID
LANGUAGE plpgsql
AS $ledger_create_partitions$
DECLARE
    v_next_period_id INTEGER;
    v_parent         TEXT;
    v_stranded       BIGINT;
BEGIN
    IF p_period_id % 100 NOT BETWEEN 1 AND 12 THEN
        RAISE EXCEPTION 'period id % is not of the form YYYYMM', p_period_id;
    END IF;

    -- December rolls to January of the next year: 202712 + 89 = 202801.
    v_next_period_id := CASE
        WHEN p_period_id % 100 = 12 THEN p_period_id + 89
        ELSE p_period_id + 1
    END;

    -- Attaching a partition to a table that has a DEFAULT partition scans the default and
    -- refuses if it holds a row belonging in the incoming bound. That is the correct
    -- behaviour and a legitimate reason for this function to fail — a run wrote to the
    -- period before its partition existed — but PostgreSQL's message describes the scan,
    -- not the remediation. So the condition is diagnosed here instead.
    FOREACH v_parent IN ARRAY ARRAY['period_balance', 'journal_entry']
    LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE period_id = $1',
                       format('%s_p_default', v_parent))
           INTO v_stranded
          USING p_period_id;

        IF v_stranded > 0 THEN
            RAISE EXCEPTION
                '%_p_default holds % row(s) for period %: move them into the period''s '
                'partition (or out of the ledger) before creating it. Attaching a '
                'partition over rows already sitting in the DEFAULT partition is refused '
                'by design. See vw_period_balance_in_default.',
                v_parent, v_stranded, p_period_id;
        END IF;
    END LOOP;

    FOREACH v_parent IN ARRAY ARRAY['period_balance', 'journal_entry']
    LOOP
        IF to_regclass(format('public.%I', format('%s_p%s', v_parent, p_period_id)))
           IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%s) TO (%s)',
                format('%s_p%s', v_parent, p_period_id),
                v_parent,
                p_period_id,
                v_next_period_id);
        END IF;
    END LOOP;
END;
$ledger_create_partitions$;

COMMENT ON FUNCTION ledger_create_period_partitions(INTEGER) IS
    'Creates period_balance_p<YYYYMM> and journal_entry_p<YYYYMM>. The naming convention '
    'is load-bearing: ledger_close_accounting_period derives the partition names from '
    'the period id.';

-- Closes a period. Time is an input, not now(): 04 § 5, and a close whose timestamp comes
-- from the server clock cannot be replayed.
--
-- The unresolved-exception gate is 04 § 3 as code rather than as procedure. An exception
-- left OPEN blocks the close; accepting it requires the named approver that the
-- ACCEPTED_WITH_APPROVAL status carries. Nothing here can be bypassed by closing the
-- period in a different order.
CREATE FUNCTION ledger_close_accounting_period(
    p_period_id      INTEGER,
    p_closed_by      TEXT,
    p_closed_at      TIMESTAMPTZ,
    p_version_cutoff TIMESTAMPTZ,
    p_app_role       TEXT DEFAULT NULL)
RETURNS VOID
LANGUAGE plpgsql
AS $ledger_close_period$
DECLARE
    v_status     TEXT;
    v_open_count BIGINT;
    v_partition  TEXT;
BEGIN
    SELECT ap.status
      INTO v_status
      FROM accounting_period ap
     WHERE ap.period_id = p_period_id
       FOR UPDATE;

    IF v_status IS NULL THEN
        RAISE EXCEPTION 'accounting period % does not exist', p_period_id;
    END IF;
    IF v_status = 'CLOSED' THEN
        RAISE EXCEPTION
            'accounting period % is already CLOSED, and CLOSED is immutable (FR-902)',
            p_period_id;
    END IF;
    IF v_status <> 'CLOSING' THEN
        RAISE EXCEPTION
            'accounting period % is %: a period is closed from CLOSING, not from %',
            p_period_id, v_status, v_status;
    END IF;

    -- 04 § 3: unresolved exceptions block the close unless explicitly accepted with
    -- approval. Every category blocks, including the two that do not stop their
    -- contract: a pool that failed its back-test has moved measurement basis, and
    -- closing without anyone acknowledging that is the failure this prevents.
    SELECT count(*)
      INTO v_open_count
      FROM public.exception e
      JOIN amortisation_run r ON r.run_id = e.raised_by_run_id
     WHERE r.period_id = p_period_id
       AND e.status = 'OPEN';

    IF v_open_count > 0 THEN
        RAISE EXCEPTION
            'period % has % unresolved exception(s): resolve each or accept it with '
            'approval (04 s3, FR-905)', p_period_id, v_open_count;
    END IF;

    UPDATE accounting_period
       SET status            = 'CLOSED',
           closed_by         = p_closed_by,
           closed_at         = p_closed_at,
           version_cutoff_at = p_version_cutoff
     WHERE period_id = p_period_id;

    -- Mechanism 3: revoke write privileges on this period's partitions. This closes the
    -- direct-to-partition path only — a statement naming the parent has its privileges
    -- checked on the parent — so it is defence in depth and not the enforcement; see the
    -- note above ledger_reject_write_to_closed_period. Skipped when no application role
    -- is named, which is the case in a bare test cluster; the triggers hold either way.
    IF p_app_role IS NOT NULL
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p_app_role) THEN
        FOREACH v_partition IN ARRAY ARRAY[
            format('period_balance_p%s', p_period_id),
            format('journal_entry_p%s', p_period_id)]
        LOOP
            IF to_regclass(format('public.%I', v_partition)) IS NOT NULL THEN
                EXECUTE format(
                    'REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON %I FROM %I',
                    v_partition, p_app_role);
            END IF;
        END LOOP;
    END IF;
END;
$ledger_close_period$;

-- The remaining eleven periods of FY2027-28, created through the helper so that the
-- operational path — the one that will create FY2028-29 next April — is the path
-- exercised here, rather than a second hand-written one that can drift from it.
DO $seed_partitions$
DECLARE
    v_period_id INTEGER;
BEGIN
    FOREACH v_period_id IN ARRAY ARRAY[
        202705, 202706, 202707, 202708, 202709,
        202710, 202711, 202712, 202801, 202802, 202803]
    LOOP
        PERFORM ledger_create_period_partitions(v_period_id);
    END LOOP;
END;
$seed_partitions$;


-- =====================================================================================
-- SECTION 6 — Transition structures (04 § 6)
--
-- ACPIR is effective 1 April 2027, the first comparative disclosure lands in FY2027-28,
-- and the legacy book must be fully on the EIR by 31 March 2030 (ACPIR 21 and 50). These
-- four tables are what make that migration measurable while it is happening rather than
-- only at its deadline.
-- =====================================================================================

-- TRANSITION_FAIR_VALUE (04 § 6) — the ACPIR 19 day-1 fair valuation, per contract.
--
-- The paragraph 19 presumption lets carrying cost be taken as the best evidence of fair
-- value. It is a presumption, and applying it requires evidence — a file built during
-- FY27, not assembled at the transition date (roadmap Phase 4). The constraint below is
-- that phase's exit gate expressed in the schema: a row cannot claim the presumption
-- without naming the evidence reference that supports it.
CREATE TABLE transition_fair_value (
    transition_fair_value_id        UUID             ,
    contract_id                     UUID             NOT NULL,
    -- 1 April 2027 for the ACPIR transition. Not constrained to that literal date: a
    -- below-market origination measured at fair value on day 1 uses the same structure on
    -- its own date (FR-909), and hard-coding the transition date would force a second
    -- table for the same fact.
    transition_date                 DATE             NOT NULL,
    pre_transition_carrying_amount  NUMERIC(24,6)    NOT NULL,
    fair_value                      NUMERIC(24,6)    NOT NULL,
    -- The difference goes to opening retained earnings. Generated, because it is a
    -- subtraction of two columns on the same row and a stored copy is one more thing that
    -- can disagree with them.
    difference_to_retained_earnings NUMERIC(24,6)
        GENERATED ALWAYS AS (fair_value - pre_transition_carrying_amount) STORED,
    valuation_technique             TEXT             NOT NULL,
    discount_rate_used              NUMERIC(20,12),
    para_19_presumption_applied     BOOLEAN          NOT NULL,
    para_19_rebuttal_evidence_ref   TEXT,
    measured_by                     TEXT             NOT NULL,
    reviewed_by                     TEXT,
    measured_at                     TIMESTAMPTZ      NOT NULL,
    policy_version_id               TEXT,
    CONSTRAINT pk_transition_fair_value
        PRIMARY KEY (transition_fair_value_id),
    CONSTRAINT uq_transition_fair_value_contract_date
        UNIQUE (contract_id, transition_date),
    CONSTRAINT fk_transition_fair_value_policy_version
        FOREIGN KEY (policy_version_id) REFERENCES policy_version (policy_version_id),
    CONSTRAINT ck_transition_fair_value_technique
        CHECK (valuation_technique IN (
            'QUOTED_PRICE',
            'DISCOUNTED_CASH_FLOW',
            'CARRYING_COST_AS_BEST_EVIDENCE')),
    -- The Phase 4 exit gate: the presumption applied means the evidence exists.
    CONSTRAINT ck_transition_fair_value_para19_evidenced
        CHECK (NOT para_19_presumption_applied
               OR para_19_rebuttal_evidence_ref IS NOT NULL),
    -- And the technique cannot say carrying cost while the flag says the presumption was
    -- not used. One fact, two columns, kept consistent.
    CONSTRAINT ck_transition_fair_value_technique_matches_presumption
        CHECK ((valuation_technique = 'CARRYING_COST_AS_BEST_EVIDENCE')
               = para_19_presumption_applied),
    CONSTRAINT ck_transition_fair_value_dcf_has_rate
        CHECK (valuation_technique <> 'DISCOUNTED_CASH_FLOW'
               OR discount_rate_used IS NOT NULL)
);

CREATE INDEX ix_transition_fair_value_contract
    ON transition_fair_value (contract_id);

-- LEGACY_COHORT (04 § 6) — how the existing book is migrated onto the EIR.
--
-- Prioritise by survival, not by size. Reconstructing an EIR for a loan maturing in 2029
-- is wasted effort; the cohorts that matter are the ones still on the books beyond
-- 31 March 2030. survives_acpir_50_deadline is generated from the expected run-off date
-- so that the prioritisation question is answerable by a WHERE clause and cannot be
-- answered two different ways by two reports.
CREATE TABLE legacy_cohort (
    cohort_id                       UUID             ,
    cohort_name                     TEXT             NOT NULL,
    definition                      JSONB            NOT NULL,
    product_id                      UUID  ,
    defined_on                      DATE             NOT NULL,
    expected_runoff_date            DATE             NOT NULL,
    -- ACPIR 21 and 50 share a deadline of 31 March 2030. A literal date is immutable and
    -- therefore legal in a generated column; it is also the point — the deadline is
    -- statutory, not configurable.
    survives_acpir_50_deadline      BOOLEAN
        GENERATED ALWAYS AS (expected_runoff_date > DATE '2030-03-31') STORED,
    migration_priority              INTEGER          NOT NULL,
    method                          TEXT             NOT NULL,
    contract_count                  BIGINT,
    approved_by                     TEXT,
    approved_at                     TIMESTAMPTZ,
    CONSTRAINT pk_legacy_cohort
        PRIMARY KEY (cohort_id),
    CONSTRAINT uq_legacy_cohort_name
        UNIQUE (cohort_name),
    CONSTRAINT ck_legacy_cohort_method
        CHECK (method IN ('FULL_RECONSTRUCTION', 'DEEMED_EIR')),
    CONSTRAINT ck_legacy_cohort_priority
        CHECK (migration_priority >= 1),
    CONSTRAINT ck_legacy_cohort_runoff_after_definition
        CHECK (expected_runoff_date >= defined_on)
);

CREATE INDEX ix_legacy_cohort_priority
    ON legacy_cohort (survives_acpir_50_deadline, migration_priority);

-- DEEMED_EIR_DERIVATION (04 § 6, FR-909) — the documented basis for a deemed rate.
--
-- A deemed EIR is what the engine uses where full reconstruction of the original flows
-- was not feasible. That is a legitimate answer, and it is also the answer that hides an
-- unwillingness to look — so both the infeasibility reason and the derivation basis are
-- NOT NULL: the row has to say why reconstruction failed and how the rate was arrived at
-- instead. An approval is required because a deemed rate recognises income on an
-- assumption.
CREATE TABLE deemed_eir_derivation (
    derivation_id                   UUID             ,
    cohort_id                       UUID  ,
    contract_id                     UUID  ,
    deemed_rate_periodic            NUMERIC(20,12)   NOT NULL,
    deemed_rate_effective_annual    NUMERIC(20,12),
    deemed_rate_nominal_annual      NUMERIC(20,12),
    basis                           TEXT             NOT NULL,
    infeasibility_reason            TEXT             NOT NULL,
    documented_basis                TEXT             NOT NULL,
    evidence_ref                    TEXT,
    prepared_by                     TEXT             NOT NULL,
    approved_by                     TEXT,
    approved_at                     TIMESTAMPTZ,
    policy_version_id               TEXT,
    CONSTRAINT pk_deemed_eir_derivation
        PRIMARY KEY (derivation_id),
    CONSTRAINT fk_deemed_eir_derivation_cohort
        FOREIGN KEY (cohort_id) REFERENCES legacy_cohort (cohort_id),
    CONSTRAINT fk_deemed_eir_derivation_policy_version
        FOREIGN KEY (policy_version_id) REFERENCES policy_version (policy_version_id),
    -- A derivation applies to a cohort or to one contract. Neither means it applies to
    -- nothing.
    CONSTRAINT ck_deemed_eir_derivation_has_subject
        CHECK (num_nonnulls(cohort_id, contract_id) >= 1),
    CONSTRAINT ck_deemed_eir_derivation_basis
        CHECK (basis IN (
            'ORIGINATION_PRICING_GRID',
            'PORTFOLIO_AVERAGE_AT_ORIGINATION',
            'CONTRACTUAL_RATE_PLUS_FEE_LOADING',
            'WEIGHTED_AVERAGE_OF_RECONSTRUCTED_COHORT')),
    -- Four eyes again: the person who prepared a deemed rate is not the person who
    -- approves recognising income on it. Case-folded and trimmed for the reason given on
    -- ck_policy_version_four_eyes — a trailing space is not a second pair of eyes.
    CONSTRAINT ck_deemed_eir_derivation_maker_checker
        CHECK (approved_by IS NULL
               OR lower(btrim(approved_by)) <> lower(btrim(prepared_by))),
    CONSTRAINT ck_deemed_eir_derivation_prepared_by_normalised
        CHECK (prepared_by = btrim(prepared_by) AND prepared_by <> ''),
    CONSTRAINT ck_deemed_eir_derivation_approval_dated
        CHECK ((approved_by IS NULL) = (approved_at IS NULL))
);

-- ECL_DISCOUNT_BASIS (04 § 6) — which rate the ECL was discounted at, per contract per
-- period.
--
-- This table exists because ACPIR 21 and ACPIR 50 are two obligations with a common
-- deadline, not one: the loan must come under the EIR regime, and its ECL discounting
-- must migrate to the EIR. Tracking them in one field would hide a gap — a contract can
-- be on the EIR for interest recognition while its ECL is still discounted at the
-- contractual rate, and that gap is invisible to anything reading a single migration
-- flag.
CREATE TABLE ecl_discount_basis (
    ecl_discount_basis_id           UUID             ,
    contract_id                     UUID             NOT NULL,
    period_id                       INTEGER          NOT NULL,
    basis                           TEXT             NOT NULL,
    -- Which solve supplied the rate. Required when the basis is EIR, because "migrated to
    -- the EIR" with no EIR to point at is a claim rather than a fact.
    eir_computation_id              UUID  ,
    rate_used                       NUMERIC(20,12),
    migrated_on                     DATE,
    migration_evidence_ref          TEXT,
    CONSTRAINT pk_ecl_discount_basis
        PRIMARY KEY (ecl_discount_basis_id),
    CONSTRAINT uq_ecl_discount_basis_contract_period
        UNIQUE (contract_id, period_id),
    CONSTRAINT fk_ecl_discount_basis_period
        FOREIGN KEY (period_id) REFERENCES accounting_period (period_id),
    CONSTRAINT ck_ecl_discount_basis_basis
        CHECK (basis IN ('CONTRACTUAL_INTERIM', 'EIR')),
    CONSTRAINT ck_ecl_discount_basis_eir_has_computation
        CHECK (basis <> 'EIR'
               OR (eir_computation_id IS NOT NULL AND migrated_on IS NOT NULL))
);

CREATE INDEX ix_ecl_discount_basis_period_basis
    ON ecl_discount_basis (period_id, basis);


-- =====================================================================================
-- SECTION 7 — Invariant and control views
--
-- Invariants of 03 § 9 that cannot be row constraints, because they are statements about
-- sets of rows. Each is a view returning the offending rows and nothing at all when the
-- invariant holds, so the close checks them by selecting and the result is evidence
-- rather than a log line.
-- =====================================================================================

-- SL-2: Σ journal debits = Σ journal credits, per run and per contract. Not a row
-- constraint — it is a property of a set of postings — and not a pass/fail report either,
-- because "the difference is 0.000001" needs the number.
CREATE VIEW vw_journal_entry_unbalanced AS
SELECT je.run_id,
       je.contract_id,
       je.period_id,
       sum(CASE WHEN je.dr_cr = 'DR' THEN je.amount ELSE 0 END)          AS total_debits,
       sum(CASE WHEN je.dr_cr = 'CR' THEN je.amount ELSE 0 END)          AS total_credits,
       sum(CASE WHEN je.dr_cr = 'DR' THEN je.amount ELSE -je.amount END) AS difference
  FROM journal_entry je
 GROUP BY je.run_id, je.contract_id, je.period_id
HAVING sum(CASE WHEN je.dr_cr = 'DR' THEN je.amount ELSE -je.amount END) <> 0;

COMMENT ON VIEW vw_journal_entry_unbalanced IS
    'Invariant SL-2 (03 s9): sum of journal debits = sum of journal credits, per run and '
    'per contract. Empty when the invariant holds.';

-- ST-2 as a view rather than a constraint, and the reason is arithmetic worth stating.
-- ST-2 is net-basis interest + ECL unwind = gross-basis interest, and at working
-- precision it is exact. At presentation scale it is not: reference case 5 gives
-- 3,304.08 + 2,202.72 against a gross figure of 5,506.79, which does not add. A CHECK at
-- NUMERIC(24,6) would be right for unrounded values and would reject a correctly computed
-- row the moment any leg arrived rounded — 03 § 1.2 forbids rounding to currency scale
-- before a figure enters a computation, and this is where that rule shows up in the
-- schema. So the identity is checked here, where the residual is visible and can be read
-- against the working-precision rule, instead of silently failing an insert.
CREATE VIEW vw_stage3_st2_breach AS
SELECT pb.contract_id,
       pb.period_id,
       pb.book_id,
       pb.run_id,
       pb.eir_interest AS gross_basis_interest,
       pb.net_basis_interest,
       pb.shadow_unwind,
       pb.eir_interest - (pb.net_basis_interest + pb.shadow_unwind) AS residual
  FROM period_balance pb
 WHERE pb.stage = 3
   AND pb.net_basis_interest IS NOT NULL
   AND pb.shadow_unwind IS NOT NULL
   AND pb.eir_interest <> pb.net_basis_interest + pb.shadow_unwind;

COMMENT ON VIEW vw_stage3_st2_breach IS
    'Invariant ST-2 (03 s7.2): net-basis interest + ECL discount unwind = gross-basis '
    'interest on a Stage 3 row. A view and not a CHECK because the identity is exact at '
    'working precision only.';

-- The close gate of 04 § 3, as one query. Every category blocks the close; acceptance
-- requires the named approver the ACCEPTED_WITH_APPROVAL status carries.
CREATE VIEW vw_close_blocking_exception AS
SELECT r.period_id,
       e.exception_id,
       e.category,
       e.stops_the_contract,
       e.contract_id,
       e.pool_id,
       e.raised_at,
       e.detail
  FROM public.exception e
  JOIN amortisation_run r ON r.run_id = e.raised_by_run_id
 WHERE e.status = 'OPEN';

COMMENT ON VIEW vw_close_blocking_exception IS
    '04 s3: unresolved exceptions block the close unless explicitly accepted with '
    'approval. The gate ledger_close_accounting_period applies, as a query.';

-- The gate above runs on the CLOSING -> CLOSED transition, so it cannot reach an
-- exception raised against a period that has already closed — and those exist: a nightly
-- replay of a closed period (invariant DT-1) is a run carrying that period's period_id,
-- and anything it raises lands here.
--
-- Such an exception is not a close blocker, because the close it would have blocked has
-- happened and FR-902 makes it immutable. It is a restatement trigger, which is a
-- different and more serious thing, and the failure mode this view exists to prevent is
-- its sitting OPEN in a queue whose semantics say "blocks a close" while blocking
-- nothing. Read alongside vw_close_blocking_exception, never instead of it.
CREATE VIEW vw_exception_against_closed_period AS
SELECT r.period_id,
       ap.closed_at,
       ap.closed_by,
       e.exception_id,
       e.category,
       e.status,
       e.contract_id,
       e.pool_id,
       e.raised_at,
       r.is_replay,
       r.replay_of_run_id,
       e.detail
  FROM public.exception e
  JOIN amortisation_run r  ON r.run_id = e.raised_by_run_id
  JOIN accounting_period ap ON ap.period_id = r.period_id
 WHERE e.status = 'OPEN'
   AND ap.status = 'CLOSED';

COMMENT ON VIEW vw_exception_against_closed_period IS
    'OPEN exceptions whose period is already CLOSED - typically raised by a DT-1 replay. '
    'They gate no close (FR-902 makes CLOSED immutable) and require the restatement '
    'path; they must not be left to look like close blockers.';

-- ACPIR 50 migration tracking. The deadline is a literal because it is statutory.
CREATE VIEW vw_ecl_discount_basis_migration_gap AS
SELECT edb.period_id,
       edb.contract_id,
       edb.basis,
       ap.period_end_date,
       DATE '2030-03-31'                        AS acpir_50_deadline,
       DATE '2030-03-31' - ap.period_end_date   AS days_to_deadline
  FROM ecl_discount_basis edb
  JOIN accounting_period ap ON ap.period_id = edb.period_id
 WHERE edb.basis = 'CONTRACTUAL_INTERIM';

COMMENT ON VIEW vw_ecl_discount_basis_migration_gap IS
    'Contracts whose ECL is still discounted at the contractual rate, against the '
    'ACPIR 50 deadline of 31 March 2030 (04 s6).';

-- FR-409: the pool back-test is mandatory quarterly. A view and not a constraint because
-- staleness is a function of the date the question is asked — and 04 § 5 is explicit that
-- nothing in the calculation path reads a wall clock, so the clock is read here, in a
-- reporting view, and not by anything that computes a rate.
CREATE VIEW vw_pool_backtest_overdue AS
SELECT p.pool_id,
       p.definition_version_id,
       p.struck_on,
       p.last_backtest_on,
       p.backtest_variance,
       p.backtest_threshold,
       p.forced_to_contract_level
  FROM pool p
 WHERE p.last_backtest_on IS NULL
    OR p.last_backtest_on < CURRENT_DATE - INTERVAL '3 months';

-- Operational: rows that landed in the DEFAULT ledger partition. Should always be empty.
-- A row here means a period's partition was not created before the run wrote to it, and
-- it will block the ATTACH of that partition later.
CREATE VIEW vw_period_balance_in_default AS
SELECT pb.period_id,
       count(*) AS rows_in_default_partition
  FROM period_balance_p_default pb
 GROUP BY pb.period_id;


-- =====================================================================================
-- SECTION 8 — DEFERRED FOREIGN KEYS
--
-- Cross-file references to tables V1 owns. See the header for the argument; in short, a
-- hard FOREIGN KEY here would make V2 unrunnable and therefore unverifiable standalone,
-- and omitting them entirely would leave contract_id as a number that can point at
-- nothing. So each is declared as data and added if and only if its parent exists.
--
-- Under Flyway, V1 runs first and every constraint below is created. Standalone, each
-- emits a NOTICE and is skipped. A column-type disagreement with V1 raises a WARNING
-- naming the constraint rather than aborting the migration, because the remedy is a
-- one-line type change in one of the two files and whoever reconciles them needs to see
-- every mismatch, not the first.
--
-- The last two rows run the other way — they put a constraint on a V1 table pointing at
-- something this file owns. POOL and POLICY_VERSION are defined here, so V1 cannot
-- declare those foreign keys itself even though EIR_COMPUTATION is where they belong.
-- =====================================================================================

DO $deferred_foreign_keys$
DECLARE
    r         RECORD;
    v_added   INTEGER := 0;
    v_skipped INTEGER := 0;
    v_failed  INTEGER := 0;
BEGIN
    FOR r IN
        SELECT *
          FROM (VALUES
            -- child table            constraint name                          child column          parent table       parent column
            ('lifecycle_event',       'fk_lifecycle_event_contract',           'contract_id',        'contract',        'contract_id'),
            ('period_balance',        'fk_period_balance_contract',            'contract_id',        'contract',        'contract_id'),
            ('period_balance',        'fk_period_balance_product',             'product_id',         'product',         'product_id'),
            ('period_balance',        'fk_period_balance_eir_computation',     'eir_computation_id', 'eir_computation', 'computation_id'),
            ('journal_entry',         'fk_journal_entry_contract',             'contract_id',        'contract',        'contract_id'),
            ('stage_assignment',      'fk_stage_assignment_contract',          'contract_id',        'contract',        'contract_id'),
            ('suspense_entry',        'fk_suspense_entry_contract',            'contract_id',        'contract',        'contract_id'),
            ('pool',                  'fk_pool_product',                      'product_id',         'product',         'product_id'),
            ('hedge_relationship',    'fk_hedge_relationship_hedged_contract', 'hedged_contract_id', 'contract',        'contract_id'),
            ('exception',             'fk_exception_contract',                 'contract_id',        'contract',        'contract_id'),
            ('transition_fair_value', 'fk_transition_fair_value_contract',     'contract_id',        'contract',        'contract_id'),
            ('legacy_cohort',         'fk_legacy_cohort_product',              'product_id',         'product',         'product_id'),
            ('deemed_eir_derivation', 'fk_deemed_eir_derivation_contract',     'contract_id',        'contract',        'contract_id'),
            ('ecl_discount_basis',    'fk_ecl_discount_basis_contract',        'contract_id',        'contract',        'contract_id'),
            ('ecl_discount_basis',    'fk_ecl_discount_basis_eir_computation', 'eir_computation_id', 'eir_computation', 'computation_id'),
            -- Reverse direction: V1 tables referencing entities this file owns.
            ('eir_computation',       'fk_eir_computation_pool',               'pool_id',            'pool',            'pool_id'),
            ('eir_computation',       'fk_eir_computation_policy_version',     'policy_version_id',  'policy_version',  'policy_version_id')
          ) AS t(child_table, constraint_name, child_column, parent_table, parent_column)
    LOOP
        IF to_regclass(format('public.%I', r.child_table)) IS NULL
           OR to_regclass(format('public.%I', r.parent_table)) IS NULL THEN
            RAISE NOTICE
                'V2 deferred FK %: skipped, %.% -> %.% (a table is absent from this '
                'schema, so V2 was applied without V1)',
                r.constraint_name, r.child_table, r.child_column,
                r.parent_table, r.parent_column;
            v_skipped := v_skipped + 1;
            CONTINUE;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = r.constraint_name) THEN
            CONTINUE;
        END IF;

        BEGIN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I (%I)',
                r.child_table, r.constraint_name, r.child_column,
                r.parent_table, r.parent_column);
            v_added := v_added + 1;
        EXCEPTION
            WHEN others THEN
                RAISE WARNING
                    'V2 deferred FK %: NOT created, %.% -> %.% (%). Reconcile the column '
                    'name and type with V1 - V2 assumes V1 surrogate keys are BIGINT and '
                    'POLICY_VERSION keys are TEXT.',
                    r.constraint_name, r.child_table, r.child_column,
                    r.parent_table, r.parent_column, SQLERRM;
                v_failed := v_failed + 1;
        END;
    END LOOP;

    RAISE NOTICE 'V2 deferred foreign keys: % added, % skipped, % failed',
                 v_added, v_skipped, v_failed;
END;
$deferred_foreign_keys$;
