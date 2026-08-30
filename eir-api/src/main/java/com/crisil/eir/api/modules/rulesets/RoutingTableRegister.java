package com.crisil.eir.api.modules.rulesets;

import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.FourEyes;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import com.crisil.eir.policy.routing.RoutingTableFormat;
import com.crisil.eir.policy.routing.RoutingTableFormatException;
import com.crisil.eir.policy.routing.RoutingTableRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The routing-table lifecycle behind {@code POST /api/routing-tables/proposals} and
 * {@code .../approvals} — draft, four-eyes approval, and the appended series a subsequent routing
 * reads (06 § 5, ADR-0006).
 *
 * <p><b>What this type is for.</b> ADR-0006's exit-gate sentence is "the routing table can be
 * changed without a code deploy". Everything needed to make that sentence <em>true</em> already
 * exists below this module: {@link RoutingTableFormat} is the text artefact,
 * {@link RoutingTableRegistry} is the approved series with its latest-wins selection, and
 * {@link com.crisil.eir.calc.routing.DefaultEventRouter} routes through whichever version the
 * series hands it. What did not exist was a way to <em>observe</em> the sentence from outside the
 * JVM: the only path into the registry was Java code, so "no code deploy" was a structural claim
 * nobody outside could exercise. This class is that path and nothing more. It reimplements no
 * parsing, no version selection and no routing — a second copy of any of the three would be a
 * second reading of the rule that decides whether a month carries a catch-up charge or nothing.
 *
 * <h2>Two kinds of fault, and why the difference is the point</h2>
 *
 * <p>{@link RoutingTableFormat} throws {@link RoutingTableFormatException} for text it cannot read as
 * one approved table — a missing {@code =}, an unknown key, a duplicate row, a driver with no row —
 * and those messages carry a line number wherever one line is at fault. {@link RoutingTable}'s own
 * constructor throws a plain {@link IllegalArgumentException} for a table that reads perfectly and
 * must not be held: a driver routed to {@link com.crisil.eir.domain.Mechanism#DERECOGNITION}.
 * {@link #submit} keeps the two apart.
 *
 * <p>Reporting the second as a parse fault would be actively misleading. Derecognition is the
 * <em>conclusion</em> of the substantiality assessment, reached per modification through
 * {@code MODIFICATION_TEST} out of the 10% test and the qualitative triggers. A table naming it as a
 * driver's standing treatment derecognises every event on that driver with no assessment at all, and
 * that is what the operator has to be told — not that something is wrong with their syntax.
 * {@link com.crisil.eir.domain.Mechanism#isRoutable()} holds the rule, and note its converse:
 * {@link com.crisil.eir.domain.Mechanism#NONE} <b>is</b> routable and is accepted here, because a
 * driver a bank has elected as immaterial is a position it is entitled to take and to have approved.
 *
 * <h2>The two approval refusals</h2>
 *
 * <p><b>Self-approval</b>, through {@link FourEyes} — the one comparison every four-eyes control in
 * this codebase now shares, stripped and case-folded to {@code Locale.ROOT}, so
 * {@code "Policy.Author"} cannot approve {@code "policy.author"}. {@link RoutingTableVersion}
 * refuses the same condition at construction and {@link RoutingTableFormat} refuses it again with a
 * line number, so a submitted artefact cannot already name its maker as its checker. This gate is
 * still needed because the identity that actually signs arrives at the approve call, separately from
 * the file, and nothing in the file constrains it.
 *
 * <p><b>An approval by somebody the draft was not routed to</b>, refused rather than resolved in
 * either direction, for the reason {@code TransitionRefusal.CHECKER_CONFLICT} states about policy
 * versions. {@link RoutingTableVersion} is immutable and its {@code checker} field is what every
 * audit sentence about the table reads, so accepting this approval would publish a table approved in
 * the named checker's name by somebody else.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>FR-210's impact-preview limb.</b> 06 § 5 requires a stored portfolio impact preview before a
 * version goes effective, and this class does not check for one. That gate is
 * {@code policy.preview.ActivationGate} over {@code ImpactPreviewRegister}, and it belongs to the
 * policy-version surface, where the preview is computed and stored. Restating a weaker version of it
 * here would be worse than omitting it, for the reason {@code MakerCheckerGate} gives about the same
 * seam: a clean result from a gate that never looked at a preview reads as evidence a preview exists.
 *
 * <p><b>Persistence.</b> Drafts live in this object for the life of the process, like
 * {@code EirService}'s last run and for the same reason — "submit, then a second person approves" is
 * two requests. A production deployment holds them in {@code POLICY_VERSION}.
 *
 * <p>Not thread-safe, deliberately: {@code EirServer} runs one executor thread and says why.
 */
public final class RoutingTableRegister {

    /** Why an approval was refused. A vocabulary, so a caller need not match on English. */
    public enum ApprovalRefusal {

        /** No draft carries that version id. */
        UNKNOWN_DRAFT,

        /** The identity signing is the identity that made the draft. */
        SELF_APPROVAL,

        /** The draft names a different checker; both readings of that need a human. */
        CHECKER_CONFLICT,

        /**
         * The table does not take effect after every version already approved.
         *
         * <p>{@link RoutingTableRegistry#with} refuses it: inserting a reading behind an existing
         * version would re-route events in an already-closed period on the next recompute, which is
         * a restatement decision and not an adoption.
         */
        NOT_AN_APPEND
    }

    /** What kind of thing was wrong with a submitted artefact. */
    public enum FaultKind {

        /** Nothing was wrong; the draft was accepted. */
        NONE,

        /** The text could not be read as a routing table. The detail carries a line number. */
        FORMAT,

        /**
         * The text read cleanly and states a table this engine must not hold.
         *
         * <p>Today that means exactly one thing: a driver routed to {@code DERECOGNITION}, refused
         * by {@link RoutingTable}'s constructor on {@code Mechanism.isRoutable()}. The other
         * condition that constructor refuses — a mapping that does not cover every driver — never
         * reaches it through {@link RoutingTableFormat}, which pre-empts it with a refusal of its
         * own naming the missing drivers, deliberately, because the constructor's message speaks
         * about a {@code Map} and the person reading it is looking at a text file. So a partial
         * table arrives here as {@link #FORMAT}. Stated rather than left implicit, because the
         * mapping between the two guards and the two labels is not obvious from either.
         */
        ACCOUNTING,

        /** An approved version already claims that id, so two mappings would share one identity. */
        DUPLICATE_ID
    }

    /**
     * The outcome of one submission.
     *
     * @param table           the parsed table, or null on any fault
     * @param faultKind       {@link FaultKind#NONE} where the draft was accepted
     * @param detail          what happened, in one sentence; never blank
     * @param supersededDraft whether an earlier unapproved draft of the same id was replaced
     */
    public record Submission(
        RoutingTable table, FaultKind faultKind, String detail, boolean supersededDraft) {

        public Submission {
            Objects.requireNonNull(faultKind, "faultKind");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("a submission states what happened");
            }
            if ((faultKind == FaultKind.NONE) != (table != null)) {
                // One record, one meaning. A submission carrying both a table and a fault would
                // read as accepted to whatever renders the mapping and as refused to whatever
                // renders the reason, and which one an integrator believed would depend on the
                // field they happened to consult.
                throw new IllegalArgumentException(
                    "a submission is an accepted table or a fault and not both or neither; got "
                        + faultKind + " with table=" + table);
            }
        }

        /** Whether the draft was accepted and is now awaiting a checker. */
        public boolean isAccepted() {
            return faultKind == FaultKind.NONE;
        }

        /** The submitted version's id, or null where nothing parsed. */
        public String versionId() {
            return table == null ? null : table.version().id();
        }
    }

    /**
     * The outcome of one approval.
     *
     * @param approved the table now in the series, or null on a refusal
     * @param refusal  why not, or null where approved
     * @param detail   what happened, in one sentence; never blank
     */
    public record Approval(RoutingTable approved, ApprovalRefusal refusal, String detail) {

        public Approval {
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("an approval states what happened");
            }
            if ((approved != null) == (refusal != null)) {
                throw new IllegalArgumentException(
                    "an approval is a table or a refusal and not both or neither; got approved="
                        + approved + ", refusal=" + refusal);
            }
        }

        public boolean isApproved() {
            return approved != null;
        }
    }

    /**
     * The approved series. Replaced wholesale on every approval, never mutated:
     * {@link RoutingTableRegistry#with} returns a new registry so that a series handed to a close run
     * cannot acquire a new reading half way through the run.
     */
    private RoutingTableRegistry approved;

    /** Submitted, parsed, not yet signed. Insertion-ordered, so a listing reads the same twice. */
    private final Map<String, RoutingTable> drafts = new LinkedHashMap<>();

    /**
     * Starts from the engine's compiled-in baseline, {@code RT-BASELINE-2026.1}.
     *
     * <p>The baseline is the thing this module exists to demonstrate superseding, so the series has
     * to begin with it: the observable claim is that a <em>subsequent</em> routing moves off the
     * compiled-in reading, and a register that started empty could not show that. The baseline's
     * maker and checker are engine-baseline identifiers rather than people, which
     * {@link RoutingTable} says plainly, and which is why a bank's own first version is an append
     * rather than an edit.
     */
    public RoutingTableRegister() {
        this.approved = RoutingTableRegistry.of(RoutingTable.currentDefault());
    }

    /** The approved series, ascending by effective date. */
    public List<RoutingTable> approvedTables() {
        return approved.tables();
    }

    /** Drafts awaiting a checker, in submission order. */
    public List<RoutingTable> pendingDrafts() {
        return List.copyOf(drafts.values());
    }

    /** One audit sentence per approved version, with its changeover dates. */
    public String describeSeries() {
        return approved.describe();
    }

    /**
     * Parses one submitted artefact and holds it as a draft.
     *
     * <p>Nothing reaches the approved series here, and that is the whole shape of the control: a
     * table becomes routable only when a second identity signs it in {@link #approve}.
     *
     * @param text       the artefact, in the format {@link RoutingTableFormat} reads
     * @param sourceName what to name in a fault message — a filename, or the endpoint
     */
    public Submission submit(String text, String sourceName) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceName, "sourceName");

        RoutingTable parsed;
        try {
            parsed = RoutingTableFormat.parse(text, sourceName);
        } catch (RoutingTableFormatException unreadable) {
            // The file could not be read. The message already carries source, line number and the
            // offending line, shaped like a compiler diagnostic, so it is passed through rather than
            // re-worded: re-wording would drop the line number, which is the part the person
            // editing the file needs first.
            return new Submission(null, FaultKind.FORMAT, unreadable.getMessage(), false);
        } catch (IllegalArgumentException notATableThisEngineMayHold) {
            // Read cleanly, and refused by RoutingTable's own constructor: in practice the
            // DERECOGNITION case, since the format pre-empts the totality check with its own
            // line-aware refusal. Caught second because RoutingTableFormatException extends
            // IllegalArgumentException, so the order of these two clauses is load-bearing —
            // reversed, every format fault would be reported as an accounting refusal, with the
            // line number still in the text while the label denied there was one.
            return new Submission(
                null, FaultKind.ACCOUNTING, notATableThisEngineMayHold.getMessage(), false);
        }

        String versionId = parsed.version().id();
        if (approved.findVersion(versionId).isPresent()) {
            // Refused here as well as by the registry, so the operator learns it at submission
            // rather than after a checker has signed. Two mappings under one id make every event
            // routed by either indistinguishable on replay, which is the failure DT-1 detects after
            // the fact and cannot repair.
            return new Submission(null, FaultKind.DUPLICATE_ID,
                "routing table version '" + versionId + "' is already approved, effective "
                    + approved.version(versionId).version().effectiveFrom()
                    + "; a change of reading is a NEW version id, because every routed event stores"
                    + " the id that routed it and two mappings sharing one id make a closed period"
                    + " irreproducible", false);
        }
        // Replacing an unapproved draft of the same id is permitted, and reported. A draft is
        // precisely the artefact that may still be edited, and refusing a corrected resubmission
        // would force an id bump for a typo — while an id bump is the one thing that has to mean "a
        // new reading". Reported because silently overwriting a pending draft would leave a checker
        // approving text they never saw.
        boolean superseded = drafts.put(versionId, parsed) != null;
        return new Submission(parsed, FaultKind.NONE,
            "routing table version '" + versionId + "' parsed and held as a draft, effective "
                + parsed.version().effectiveFrom() + ", made by " + parsed.version().maker()
                + ", routed to checker " + parsed.version().checker()
                + ". It routes nothing until that checker approves it", superseded);
    }

    /**
     * A checker signs a draft, which appends it to the approved series.
     *
     * <p>Once this returns approved, a routing on a date the new version governs answers under it —
     * no restart, no redeploy. That sentence is ADR-0006's exit gate, and this method together with
     * {@link #routeOn} is the whole of its observable form.
     *
     * @param versionId the draft's version id
     * @param checker   the identity signing, which is deliberately not read from the artefact
     */
    public Approval approve(String versionId, String checker) {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(checker, "checker");
        String key = versionId.strip();

        RoutingTable draft = drafts.get(key);
        if (draft == null) {
            return new Approval(null, ApprovalRefusal.UNKNOWN_DRAFT,
                "no draft routing table carries version id '" + key + "'. Pending: "
                    + new ArrayList<>(drafts.keySet()) + "; already approved: "
                    + approved.versionIds());
        }
        RoutingTableVersion version = draft.version();

        // Self-approval first, and the order is part of the control. A checker equal to the maker
        // would also fail the checker-conflict test below, because the artefact cannot name its
        // maker as its checker — RoutingTableFormat and RoutingTableVersion both refuse that — so
        // without this clause first, the most serious control breach available here would be filed
        // under a heading about routing paperwork.
        if (FourEyes.isSelfApproval(version.maker(), checker)) {
            return new Approval(null, ApprovalRefusal.SELF_APPROVAL,
                "routing table version '" + key + "' was made by '" + version.maker()
                    + "' and '" + checker + "' is the same identity. A table approved by its own"
                    + " maker is not approved: four-eyes is not ceremony here, because the mapping"
                    + " alone decides whether a month carries a catch-up charge or nothing"
                    + " (reference cases 3 and 4 differ by 627.42 on one instrument in one month)");
        }
        if (!FourEyes.sameIdentity(version.checker(), checker)) {
            return new Approval(null, ApprovalRefusal.CHECKER_CONFLICT,
                "routing table version '" + key + "' names checker '" + version.checker()
                    + "' and the approval is by '" + checker + "'. Refused rather than resolved"
                    + " either way: the version record is immutable and its checker field is what"
                    + " every audit sentence about this table reads, so recording this approval"
                    + " would publish a table approved in '" + version.checker() + "''s name by"
                    + " somebody else. Reissue the artefact naming the checker who is signing");
        }

        RoutingTableRegistry appended;
        try {
            appended = approved.with(draft);
        } catch (IllegalArgumentException notAnAppend) {
            // A policy condition about the submitted table, not a defect, so it comes back as a
            // value carrying the registry's own message — which explains why amending history is a
            // restatement decision rather than an adoption.
            return new Approval(null, ApprovalRefusal.NOT_AN_APPEND, notAnAppend.getMessage());
        }
        this.approved = appended;
        drafts.remove(key);
        return new Approval(draft, null,
            "routing table version '" + key + "' approved by '" + checker + "' and appended to the"
                + " series; it governs routings from " + version.effectiveFrom()
                + " onward. Events already routed keep the version id they recorded, so every"
                + " closed period continues to replay under the reading it closed under");
    }

    /**
     * Routes a driver under the version in force on {@code eventDate} — the live path.
     *
     * <p>The same call {@code ContractPipeline} makes per event:
     * {@code routing.route(driver, rateType, eventDate)}. So what this answers is what a run would
     * route rather than a parallel imitation of it, which is what lets an approved table be shown
     * changing a routing with no deploy.
     *
     * @throws com.crisil.eir.policy.routing.RoutingTableUnavailableException where the date precedes
     *     every approved table — never falling back to the nearest reading, because the fallback
     *     would stamp an event with a version id that version never governed
     */
    public RoutingDecision routeOn(RateDriver driver, RateType rateType, LocalDate eventDate) {
        return approved.route(driver, rateType, eventDate);
    }

    /**
     * Re-routes under a named version — the replay path, which consults no date.
     *
     * <p>Offered beside {@link #routeOn} because the two are different questions, and conflating
     * them is the defect ADR-0006 names: a replay that re-selected by date would apply today's
     * reading to yesterday's events, and DT-1 would either fail or pass while being wrong.
     */
    public RoutingDecision replay(RateDriver driver, RateType rateType, String recordedVersionId) {
        return approved.replay(driver, rateType, recordedVersionId);
    }

    /** An approved or pending table by version id, for re-emission. */
    public Optional<RoutingTable> find(String versionId) {
        Objects.requireNonNull(versionId, "versionId");
        String key = versionId.strip();
        Optional<RoutingTable> inSeries = approved.findVersion(key);
        return inSeries.isPresent() ? inSeries : Optional.ofNullable(drafts.get(key));
    }
}
