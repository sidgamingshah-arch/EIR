# ADR-0008 — No manual rate or balance override, anywhere, ever

**Status:** Accepted

## Context

Every accounting engine implementation is eventually asked for a manual override: a screen where a
controller can correct a rate or a balance that is visibly wrong, without waiting for an input fix or
a policy change. The request is reasonable on its face — the figure *is* wrong, the close *is*
tomorrow.

ACPIR separately requires classification, upgradation and provisioning to be **fully system-driven
with no manual intervention**, with periodic auditor validation of both controls and methodology.

## Decision

**No endpoint, screen or job permits overriding a computed rate, carrying amount or interest figure.**
There is no override table, no adjustment field, no privileged path.

Where a computed figure is wrong, exactly one of these is wrong: an **input** (contract terms,
schedule, fee posting, staging, allowance) or a **policy** (rule set, routing table, assumption,
tier). Fix that, and recompute.

## Rationale

**One override destroys the audit position for the whole book.** The engine's value rests on
determinism: given the same inputs and policy version, the output is reproducible. An override is by
construction not reproducible from inputs. And because nothing in a reported figure distinguishes a
computed value from an adjusted one, the *possibility* of override means no figure can be asserted as
computed — the property is lost portfolio-wide, not just on the overridden contract.

**Fixing the cause fixes every affected contract.** An override fixes one. If a fee code is
misclassified, correcting the rule set corrects every contract carrying that code, in this period and
on every future replay. The override corrects one contract this period and leaves the defect live.

**It removes the pressure valve that hides upstream defects.** With no override, a bad CBS feed
becomes an exception queue full of visible items and an escalation to the source system owner. With
an override, it becomes a quiet monthly workaround that nobody upstream ever hears about.

**ACPIR requires it.** The automation mandate is not satisfied by a system-driven computation with a
manual correction path bolted on.

## Consequences

- **The exception queue must be genuinely good.** It is the only remediation path, so it needs clear
  categorisation, the failing payload attached, the specific failing field named, and a workable
  resolution flow ([04 §3](../04-data-model.md#3-exception-queue)).
- **Input correction must be fast.** A same-day fee reclassification with an effective date must be a
  supported, routine operation, not an engineering task.
- **Exception acceptance exists as the pressure valve** — a contract can be accepted-with-approval to
  permit a close, which records that the figure is known-imperfect and who accepted it. Crucially
  this does **not** change the number; it records a judgement about it.
- **Restatement handles the genuine after-the-fact case.** A closed-period error becomes a
  restatement artefact in the current period, with cause and approver
  ([07 §4.4](../07-nfr-controls-audit.md#44-restatement)).
- This decision will be challenged, probably during the first close. The answer is this ADR.

## Alternatives rejected

**Override with mandatory reason and approval.** The usual compromise. Rejected: an approved override
is still not reproducible from inputs, and the property that matters is portfolio-wide reproducibility,
not per-override accountability. It also becomes routine — overrides always do.

**Override permitted only in the open period.** Narrower, same defect. The open period is the one
being published.

**Adjustment postings to a manual journal.** Legitimate for genuine accounting adjustments, and
already available via the GL — outside this engine. What is refused is overriding the *EIR
computation*, which must always be derivable from its inputs.
