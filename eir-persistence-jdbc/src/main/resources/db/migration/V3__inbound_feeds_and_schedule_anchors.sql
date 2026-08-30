-- =====================================================================================
-- V3 — inbound feeds and schedule anchors
--
-- OWNED BY eir-persistence-jdbc, not by eir-persistence. V1 and V2 are the schema of
-- docs/04-data-model.md and are verified; this file adds nothing to them and changes
-- nothing in them. What it adds is the landing area for facts that belong to OTHER
-- systems, plus the three projector anchors 04 § 2.3 does not carry — and it exists
-- because without them two of the seven ports of eir-application cannot be implemented
-- at all and a third cannot be implemented correctly.
--
-- Why these tables are not in 04, and why that is not an oversight. 08's scope table is
-- explicit that "the engine posts to a general ledger; it is not one", and ADR-0004 makes
-- the core banking system the book of record for billing. So the GL's trial balance and
-- the CBS's billed interest are not the engine's data — but a JDBC adapter still has to
-- read them from somewhere, and the alternative to a landing table is deriving each from
-- the engine's own output. That alternative is precisely the defect CoreBankingFeed's own
-- javadoc names: "a field against itself", the trap that makes SL-1 and RC-1 unfailable.
-- A staging table read by the adapter keeps the two sides of both reconciliations
-- independently sourced, which is the only arrangement in which either invariant is a
-- control rather than a tautology.
--
-- ---------------------------------------------------------------------------------
-- Every table here carries the system-time pair, and that is the point of the module
-- ---------------------------------------------------------------------------------
--
-- recorded_at / superseded_at, exactly as CONTRACT_VERSION and LIFECYCLE_EVENT carry
-- them (04 § 5). A CBS restatement, a re-extracted trial balance, a corrected cash
-- application: each is a NEW ROW with a current recorded_at and the old row's
-- superseded_at closed, never an UPDATE. That is what lets AsAtBoundary.recordedAsAt
-- select the version set a closed period actually consumed, and it is what makes
-- invariant DT-1 checkable on the feed legs and not only on the terms.
--
-- A feed table with a single mutable row would answer the same thing at every boundary —
-- which is the eir-api in-memory limitation this whole module exists to remove, and it
-- would be a waste to reproduce it one layer down.
--
-- Each table therefore carries a GiST exclusion constraint over
-- tstzrange(recorded_at, superseded_at): at most one row visible at any one instant for
-- any one key. Without it two overlapping CBS figures for one contract-period both
-- insert happily, the adapter's query returns two rows, and RC-1 compares against
-- whichever the planner returned first. The engine would then be reconciling against a
-- number that changes with the plan.
--
-- Money is NUMERIC(24,6) and rates NUMERIC(20,12), the physical conventions of
-- 04 § 4, for the same reason they hold there: a working value that reaches this schema
-- at presentation scale has already lost digits, and no read can recover them.
--
-- Engine: PostgreSQL 16 or later. btree_gist is created by V1 and V2; requiring it again
-- here would be harmless but is unnecessary, and this file deliberately does not repeat
-- it so that a reader is not misled into thinking the extension is this module's.
-- =====================================================================================


-- =====================================================================================
-- CONTRACT_VERSION_SCHEDULE_ANCHOR — the three projector inputs 04 § 2.3 does not carry
--
-- ContractTerms in eir-calc needs disbursementDate, firstDueDate and termPeriods.
-- CONTRACT_VERSION carries none of the three: it has valid_from (a business-time axis,
-- not a cash-flow date), contractual_maturity_date, and eir_expected_life_months.
--
-- **Why they are stored and not derived.** The obvious derivation is
-- disbursement_date := contract.initial_recognition_date. It is wrong, and 04 § 2.1 says
-- so on the column itself: for a commitment, initial_recognition_date is the day the bank
-- became party to the irrevocable commitment (ACPIR 23) and "not the date of first
-- drawdown". A projector anchored on the commitment date would discount every flow of
-- every commitment from the wrong t=0 — a silent, uniform, unreconcilable error in the
-- EIR of an entire product class. Deriving first_due_date as "disbursement plus one
-- period" is wrong in the same way for any contract with a stub first period.
--
-- So the adapter refuses to guess. A contract version with no anchor row produces
-- Optional.empty() from ContractStateSource.openingState and from
-- OnboardingSource.onboardingRequest, which FR-905 quarantines per contract — a named
-- data condition on one contract, rather than a plausible-looking rate on all of them.
--
-- Not bitemporal, deliberately: the row describes a CONTRACT_VERSION, and
-- CONTRACT_VERSION is already the bitemporal record. A corrected disbursement date is a
-- correction of the terms, which is a new contract_version and therefore a new anchor
-- row. Making this table temporal too would create a second, independent history of one
-- fact — and the two would eventually disagree about which version a date belongs to.
-- =====================================================================================
CREATE TABLE contract_version_schedule_anchor (
    contract_version_id             UUID            NOT NULL,

    -- t=0 for the projection and for every discount exponent. For a commitment this is
    -- the first drawdown, NOT the ACPIR 23 commitment date.
    disbursement_date               DATE            NOT NULL,

    -- The first scheduled repayment. Held rather than derived because a stub first
    -- period is ordinary and "disbursement plus one period" silently normalises it away.
    first_due_date                  DATE            NOT NULL,

    -- Instalments in the schedule the projector builds. Held rather than derived from
    -- eir_expected_life_months because the conversion needs a compounding frequency and
    -- an assumption about part periods, and an assumption that changes a term by one
    -- period changes the EIR.
    term_periods                    INTEGER         NOT NULL,

    -- ContractTerms.stepFactor: the instalment ladder multiplier for a STEP_SCHEDULE
    -- product. NULL for every other shape. Held here because CONTRACT_VERSION has no
    -- column for it, and because the SIGN of the deviation from 1 is what distinguishes
    -- ScheduleShape.STEP_UP from STEP_DOWN — a product tagged STEP_SCHEDULE with no
    -- factor cannot be routed to either projector.
    step_factor                     NUMERIC(20,12),

    -- ContractTerms.balloonAmount / residualValue, for the BALLOON shape. Money scale.
    balloon_amount                  NUMERIC(24,6),
    residual_value                  NUMERIC(24,6),

    CONSTRAINT pk_contract_version_schedule_anchor
        PRIMARY KEY (contract_version_id),
    CONSTRAINT fk_contract_version_schedule_anchor_version
        FOREIGN KEY (contract_version_id)
        REFERENCES contract_version (contract_version_id),
    -- ContractTerms refuses a firstDueDate that is not after the disbursement date, with
    -- the same reasoning. Refusing it here as well means the adapter fails on the row
    -- that is wrong rather than on the contract that happened to be loaded first, and the
    -- message names the contract version.
    CONSTRAINT ck_contract_version_schedule_anchor_first_due
        CHECK (first_due_date > disbursement_date),
    CONSTRAINT ck_contract_version_schedule_anchor_term
        CHECK (term_periods >= 1),
    -- A ladder multiplier of zero would zero every instalment after the first, and a
    -- negative one would invert the sign of the schedule. ContractTerms refuses both.
    CONSTRAINT ck_contract_version_schedule_anchor_step_factor
        CHECK (step_factor IS NULL OR step_factor > 0),
    CONSTRAINT ck_contract_version_schedule_anchor_terminal_amounts
        CHECK ((balloon_amount IS NULL OR balloon_amount >= 0)
               AND (residual_value IS NULL OR residual_value >= 0))
);

COMMENT ON TABLE contract_version_schedule_anchor IS
    'eir-persistence-jdbc — the projector anchors 04 s2.3 does not carry. Stored, never '
    'derived from initial_recognition_date: for a commitment that date is the ACPIR 23 '
    'commitment date and not the first drawdown, so the derivation is wrong for an '
    'entire product class and wrong silently.';


-- =====================================================================================
-- CBS_BILLED_INTEREST — the other side of RC-1 (FR-804, C-14)
--
-- What the core banking system says it billed the borrower, per contract per period, as
-- the CBS reported it and stamped with when the engine was told. ADR-0004 makes the CBS
-- the book of record for billing; this table is where its extract lands.
--
-- feed_reference is NOT NULL and non-blank because CbsBilledInterest refuses a blank one:
-- "an unattributable figure cannot be re-fetched when the break is investigated". The
-- constraint is here as well as in the record so that the refusal happens at load, on
-- the row, rather than at run time on a contract chosen by iteration order.
-- =====================================================================================
CREATE TABLE cbs_billed_interest (
    feed_line_id                    UUID            NOT NULL,
    contract_id                     UUID            NOT NULL,
    period_id                       INTEGER         NOT NULL,

    -- Money scale. The CBS bills at currency scale, and the column is wider on purpose:
    -- a feed that starts reporting a finer figure must not be truncated by the landing
    -- table, because the truncation is invisible and RC-1's tolerance would absorb it.
    billed_interest                 NUMERIC(24,6)   NOT NULL,

    feed_reference                  TEXT            NOT NULL,

    -- System time. A restated extract is a new row, not an update.
    recorded_at                     TIMESTAMPTZ     NOT NULL,
    superseded_at                   TIMESTAMPTZ,

    CONSTRAINT pk_cbs_billed_interest
        PRIMARY KEY (feed_line_id),
    CONSTRAINT fk_cbs_billed_interest_contract
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT ck_cbs_billed_interest_period_id
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    CONSTRAINT ck_cbs_billed_interest_feed_reference
        CHECK (btrim(feed_reference) <> ''),
    CONSTRAINT ck_cbs_billed_interest_system_time
        CHECK (superseded_at IS NULL OR superseded_at > recorded_at),
    -- One visible figure per contract-period at any instant. See the header note: two
    -- overlapping rows make RC-1 compare against whichever row the planner returns
    -- first, and that is a reconciliation whose answer depends on a query plan.
    CONSTRAINT ex_cbs_billed_interest_one_visible
        EXCLUDE USING gist (
            contract_id WITH =,
            period_id WITH =,
            tstzrange(recorded_at, superseded_at) WITH &&
        )
);

COMMENT ON TABLE cbs_billed_interest IS
    'eir-persistence-jdbc — the CBS extract behind CoreBankingFeed (ADR-0004, FR-804). '
    'Bitemporal on the system axis so a replay reads the extract the original run '
    'consumed, not a later restatement of it.';

CREATE INDEX ix_cbs_billed_interest_period_recorded
    ON cbs_billed_interest (period_id, recorded_at);


-- =====================================================================================
-- GL_CONTROL_ACCOUNT_BALANCE — the other side of SL-1 (FR-803)
--
-- The general ledger's own posted balance on a control account, as observed. 08's scope
-- table: "the engine posts to a general ledger; it is not one". So this is a read of
-- somebody else's system, landed here with the reference the figure can be re-fetched by.
--
-- period_id is part of the key rather than a comment. A trial balance is taken as at a
-- reporting date; a balance with no period attached would be compared against whichever
-- period's sub-ledger the run happened to be computing, which is the class of break that
-- looks like an arithmetic error and is a join error.
-- =====================================================================================
CREATE TABLE gl_control_account_balance (
    gl_observation_id               UUID            NOT NULL,
    account_code                    TEXT            NOT NULL,
    book_id                         TEXT            NOT NULL,
    period_id                       INTEGER         NOT NULL,

    -- Signed: a liability-side control account carries a credit balance and the GL
    -- reports it as such. Unsigned with a side column would be the JOURNAL_ENTRY
    -- treatment, right there and wrong here — a trial balance line is one number.
    balance                         NUMERIC(24,6)   NOT NULL,

    -- ISO 4217. Carried because a control account balance is a Money and the GL is not this
    -- engine's table: there is no contract to join to for the currency, and defaulting to the
    -- reporting currency would put a foreign-currency control account on the wrong side of SL-1
    -- with no evidence that anything was assumed.
    currency                        CHAR(3)         NOT NULL,

    source_ref                      TEXT            NOT NULL,

    recorded_at                     TIMESTAMPTZ     NOT NULL,
    superseded_at                   TIMESTAMPTZ,

    CONSTRAINT pk_gl_control_account_balance
        PRIMARY KEY (gl_observation_id),
    CONSTRAINT ck_gl_control_account_balance_period_id
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    -- GlControlAccountBalance refuses a blank account code, book or source reference:
    -- "a GL figure with no source is not evidence". Refused at load for the same reason.
    CONSTRAINT ck_gl_control_account_balance_text_present
        CHECK (btrim(account_code) <> ''
               AND btrim(book_id) <> ''
               AND btrim(source_ref) <> ''),
    -- Same shape as contract.currency_ck and journal_entry.currency_ck in V1 and V2.
    CONSTRAINT ck_gl_control_account_balance_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_gl_control_account_balance_system_time
        CHECK (superseded_at IS NULL OR superseded_at > recorded_at),
    CONSTRAINT ex_gl_control_account_balance_one_visible
        EXCLUDE USING gist (
            account_code WITH =,
            book_id WITH =,
            period_id WITH =,
            tstzrange(recorded_at, superseded_at) WITH &&
        )
);

COMMENT ON TABLE gl_control_account_balance IS
    'eir-persistence-jdbc — the trial balance behind GeneralLedgerSource (FR-803). '
    'Independently sourced from the sub-ledger on purpose: deriving the GL side from the '
    'sub-ledger makes invariant SL-1 unfailable.';

CREATE INDEX ix_gl_control_account_balance_period_recorded
    ON gl_control_account_balance (period_id, recorded_at);


-- =====================================================================================
-- CASH_BOOK_APPLICATION — what cash was received and to which leg it was applied
--
-- ContractPeriod carries cashAppliedToPrincipal and cashAppliedToInterest as two
-- separate inputs, and its javadoc gives the reason: "taking both from one source would
-- make the journal's cash block a field compared against itself ... and would remove the
-- only thing that can detect a receipt applied to the wrong leg". PERIOD_BALANCE has
-- cash_received, one unsplit total, and it is the engine's own output besides. So the
-- split arrives here, from the cash book.
--
-- The two columns are magnitudes and non-negative. A reversal is a superseding row with
-- the corrected split, never a negative applied amount: a negative interest application
-- and a positive principal application net to the same cash_received while describing a
-- completely different ledger, and only one of the two reconciles to the cash book.
-- SUSPENSE_ENTRY in V2 refuses signed movements on exactly this reasoning.
-- =====================================================================================
CREATE TABLE cash_book_application (
    application_id                  UUID            NOT NULL,
    contract_id                     UUID            NOT NULL,
    period_id                       INTEGER         NOT NULL,
    book_id                         TEXT            NOT NULL,

    applied_to_principal            NUMERIC(24,6)   NOT NULL,
    applied_to_interest             NUMERIC(24,6)   NOT NULL,

    source_ref                      TEXT            NOT NULL,

    recorded_at                     TIMESTAMPTZ     NOT NULL,
    superseded_at                   TIMESTAMPTZ,

    CONSTRAINT pk_cash_book_application
        PRIMARY KEY (application_id),
    CONSTRAINT fk_cash_book_application_contract
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT ck_cash_book_application_period_id
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    CONSTRAINT ck_cash_book_application_magnitudes
        CHECK (applied_to_principal >= 0 AND applied_to_interest >= 0),
    CONSTRAINT ck_cash_book_application_text_present
        CHECK (btrim(book_id) <> '' AND btrim(source_ref) <> ''),
    CONSTRAINT ck_cash_book_application_system_time
        CHECK (superseded_at IS NULL OR superseded_at > recorded_at),
    CONSTRAINT ex_cash_book_application_one_visible
        EXCLUDE USING gist (
            contract_id WITH =,
            period_id WITH =,
            book_id WITH =,
            tstzrange(recorded_at, superseded_at) WITH &&
        )
);

COMMENT ON TABLE cash_book_application IS
    'eir-persistence-jdbc — the cash book''s leg split behind ContractPeriodSource. Two '
    'columns, both magnitudes: a signed pair nets to the same total while describing a '
    'different ledger, and only one of the two ties to the cash book.';


-- =====================================================================================
-- CONTRACT_ONBOARDING_ATTRIBUTE — the 03 § 10 attributes 04 § 2.1 does not carry
--
-- OnboardingRequest needs the counterparty segment the materiality gate keys on, the
-- asserted § 10 features, and the exposure at origination for the Tier 1 wholesale
-- Board-threshold limb. CONTRACT carries the OUTCOME — materiality_tier and tier_basis —
-- and not the inputs, which is right for an immutable identity and leaves the gate with
-- nothing to run on.
--
-- exposure_at_origination is nullable, and that is TierAssignmentInput's own reasoning
-- quoted: "a zero exposure would read as below the Board threshold and escape Tier 1".
-- NOT NULL DEFAULT 0 here would be the exact defect that record refuses.
-- =====================================================================================
CREATE TABLE contract_onboarding_attribute (
    attribute_id                    UUID            NOT NULL,
    contract_id                     UUID            NOT NULL,

    -- eir-policy TierAssignmentSegment, complete.
    counterparty_segment            TEXT            NOT NULL,

    -- eir-policy TierAssignmentFeature names, as asserted by the feed. Not
    -- CHECK-constrained to the enum: the feature set grows with 03 § 10 and an unknown
    -- name is refused by the adapter with the contract named, which is a better failure
    -- than a migration.
    tier_features                   TEXT[],

    exposure_at_origination         NUMERIC(24,6),

    recorded_at                     TIMESTAMPTZ     NOT NULL,
    superseded_at                   TIMESTAMPTZ,

    CONSTRAINT pk_contract_onboarding_attribute
        PRIMARY KEY (attribute_id),
    CONSTRAINT fk_contract_onboarding_attribute_contract
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT ck_contract_onboarding_attribute_segment
        CHECK (counterparty_segment IN ('WHOLESALE', 'RETAIL', 'MSME')),
    -- An exposure at origination is a size, not a movement.
    CONSTRAINT ck_contract_onboarding_attribute_exposure
        CHECK (exposure_at_origination IS NULL OR exposure_at_origination >= 0),
    CONSTRAINT ck_contract_onboarding_attribute_system_time
        CHECK (superseded_at IS NULL OR superseded_at > recorded_at),
    CONSTRAINT ex_contract_onboarding_attribute_one_visible
        EXCLUDE USING gist (
            contract_id WITH =,
            tstzrange(recorded_at, superseded_at) WITH &&
        )
);

COMMENT ON TABLE contract_onboarding_attribute IS
    'eir-persistence-jdbc — the 03 s10 tier-gate inputs. CONTRACT carries the outcome '
    '(materiality_tier, tier_basis) and not the inputs; the gate cannot re-run on an '
    'outcome. exposure_at_origination is nullable on purpose: a zero would read as below '
    'the Board threshold and escape Tier 1.';


-- =====================================================================================
-- SUSPENSE_MOVEMENT — recoveries and write-offs of suspended interest, as reported
--
-- SUSPENSE_ENTRY in V2 is the sub-ledger: opening balance, movements, closing balance,
-- with the continuity identity enforced. It is the engine's own record. This table is the
-- INPUT that produces two of those movements — the recovery and the write-off — and it is
-- separate for the reason invariant S3-1's fourth leg exists at all.
--
-- S3-1 reconciles cash applied to interest against suspended interest recovered. If both
-- numbers came from one place the leg would be a restatement of one figure, which is the
-- trap ContractPeriod's javadoc names and this programme has now found several times. So
-- the cash book reports what it applied (CASH_BOOK_APPLICATION above) and the collections
-- system reports what it released from suspense (here), and the two are compared.
--
-- Magnitudes, not signed movements, for the reason V2's SUSPENSE_ENTRY gives on its own
-- movement columns: "a recovery posted as a negative and a suspension posted as a
-- negative recovery [give the] same closing balance, different ledger, and only one of
-- them reconciles to the cash book".
-- =====================================================================================
CREATE TABLE suspense_movement (
    suspense_movement_id            UUID            NOT NULL,
    contract_id                     UUID            NOT NULL,
    period_id                       INTEGER         NOT NULL,
    book_id                         TEXT            NOT NULL,

    recovered                       NUMERIC(24,6)   NOT NULL,
    written_off                     NUMERIC(24,6)   NOT NULL,

    source_ref                      TEXT            NOT NULL,

    recorded_at                     TIMESTAMPTZ     NOT NULL,
    superseded_at                   TIMESTAMPTZ,

    CONSTRAINT pk_suspense_movement
        PRIMARY KEY (suspense_movement_id),
    CONSTRAINT fk_suspense_movement_contract
        FOREIGN KEY (contract_id) REFERENCES contract (contract_id),
    CONSTRAINT ck_suspense_movement_period_id
        CHECK (period_id BETWEEN 190001 AND 999912
               AND period_id % 100 BETWEEN 1 AND 12),
    CONSTRAINT ck_suspense_movement_magnitudes
        CHECK (recovered >= 0 AND written_off >= 0),
    CONSTRAINT ck_suspense_movement_text_present
        CHECK (btrim(book_id) <> '' AND btrim(source_ref) <> ''),
    CONSTRAINT ck_suspense_movement_system_time
        CHECK (superseded_at IS NULL OR superseded_at > recorded_at),
    CONSTRAINT ex_suspense_movement_one_visible
        EXCLUDE USING gist (
            contract_id WITH =,
            period_id WITH =,
            book_id WITH =,
            tstzrange(recorded_at, superseded_at) WITH &&
        )
);

COMMENT ON TABLE suspense_movement IS
    'eir-persistence-jdbc — the collections side of invariant S3-1 leg 4. Separate from '
    'CASH_BOOK_APPLICATION on purpose: one source for both numbers makes the leg a '
    'restatement of one figure rather than a reconciliation of two.';
