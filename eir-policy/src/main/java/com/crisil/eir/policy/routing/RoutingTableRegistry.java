package com.crisil.eir.policy.routing;

import com.crisil.eir.calc.routing.DefaultEventRouter;
import com.crisil.eir.calc.routing.EventRouter;
import com.crisil.eir.calc.routing.RoutingDecision;
import com.crisil.eir.calc.routing.RoutingTable;
import com.crisil.eir.calc.routing.RoutingTableVersion;
import com.crisil.eir.domain.RateDriver;
import com.crisil.eir.domain.RateType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every approved {@link RoutingTable} the bank has ever had, and which one governs a
 * given date (FR-504, ADR-0006).
 *
 * <p>{@link RoutingTable} is one reading of the reset-versus-catch-up rule, approved
 * under one {@link RoutingTableVersion}. It knows nothing of any other reading, and
 * {@link RoutingTableVersion#isEffectiveOn} answers only "does this version govern
 * that date" — never "which version does". That question needs the whole series, and
 * this is where the series lives. It is the piece that makes the Phase 2 exit gate
 * true: <em>the routing table can be changed without a code deploy</em>. A change of
 * reading is an approved table appended here through {@link #with}, not an edit to a
 * {@code switch}.
 *
 * <h2>Why the series, and not just the current table</h2>
 *
 * <p>ACPIR is silent on subsequent changes in estimated cash flows, so the B5.4.5 /
 * B5.4.6 mechanics are adopted by election, and the IASB tentatively decided in April
 * 2026 to amend B5.4.5 with an Exposure Draft expected H2 2026 (ADR-0006). When that
 * wording lands, some drivers move — an ESG margin ratchet plausibly stops being a
 * B5.4.6 catch-up and becomes consideration for credit risk, hence a reset. The
 * difference is not presentational: reference cases 3 and 4 are the same instrument in
 * the same month, and one produces a 627.42 charge while the other produces nothing.
 *
 * <p>So both readings must be simultaneously resolvable, forever. New events route
 * through the version in force on their date ({@link #route}); a closed period replays
 * through the version its events recorded ({@link #replay}). Those are deliberately
 * two different methods over one series, because they are two different questions and
 * conflating them is the defect: a replay that re-selected by date would apply today's
 * reading to yesterday's events, and invariant DT-1 would either fail or — the worse
 * outcome — pass while being wrong.
 *
 * <h2>The resolution rule: latest wins</h2>
 *
 * <p>Among the tables whose {@code version().effectiveFrom()} is on or before the date
 * asked about, the one in force is the one with the <strong>greatest</strong> effective
 * date. Nothing else breaks a tie, and nothing needs to: a tie can only arise between two
 * tables sharing an effective date, and that set is rejected at construction rather than
 * resolved here.
 *
 * <p><strong>No end date is needed, and none is added.</strong>
 * {@link RoutingTableVersion} carries an effective-from and no expiry — a version claims
 * every date after its own start — so read individually every version overlaps every later
 * one, and {@link RoutingTableVersion#isEffectiveOn} can only answer "does this version
 * claim that date", never "which version governs it". Latest-wins closes that gap from
 * <em>above</em>, over the series, which is standard temporal-table resolution: a
 * successor's effective date is implicitly its predecessor's last day, so an
 * {@code effectiveTo} would be a second, derivable statement of the same fact and the two
 * could disagree. Adding one would also mean editing a mature eir-calc type that other
 * work depends on, to record something the series already knows. So "overlap" here means
 * exactly one thing — two tables sharing an identical effective date — and that is what is
 * rejected.
 *
 * <h2>What is rejected, and where</h2>
 *
 * <p><strong>Overlap, at construction.</strong> Two tables effective on the same date
 * make "the table in force" ambiguous, and an ambiguous routing resolved by insertion
 * order or map iteration is worse than no routing: it is reproducible only by accident.
 * Two tables sharing a version <em>id</em> is the same defect one level down — every
 * event routed by either becomes indistinguishable on replay — so that is rejected too,
 * mirroring {@link RoutingTable#reroute}'s refusal to reuse an id.
 *
 * <p><strong>A gap, at lookup.</strong> Latest-wins has one consequence worth stating
 * plainly: interior gaps are unrepresentable, because each version governs until the next
 * supersedes it and consecutive tables therefore cannot fail to meet. The one gap the
 * model can express is at the front — a date before the earliest approved table — and
 * that raises {@link RoutingTableUnavailableException} rather than borrowing the nearest
 * reading or falling back to {@link RoutingTable#currentDefault()}. Substituting the
 * engine's compiled-in baseline would reintroduce precisely the hard-coded mapping
 * ADR-0006 exists to remove, and stamp the event with a version the bank never approved
 * for that date. It is the single most important refusal in this class. {@link #coverageOver} asks the same question ahead of a period close, as a
 * reportable {@link RoutingCoverage} rather than a throw, because a close run wants
 * every uncovered contract listed at once rather than the first one as a stack trace.
 *
 * <p><strong>Approval is only partly guaranteed, and this class does not close the
 * gap.</strong> {@link RoutingTableVersion} refuses to represent a version whose maker
 * and checker are the same identity, or either of them blank, and it requires an
 * approval date — so a self-approved table cannot reach this class. That is where the
 * guarantee stops. The two identities are free strings with no status and no impact
 * preview attached, so a version naming a checker who never signed anything is
 * representable, and FR-210's requirement that a portfolio impact preview exist before a
 * version goes effective is enforced nowhere on this path. The registry deliberately
 * adds no check of its own — it would be a second, weaker gate beside the maker-checker
 * workflow that owns the question, and {@code PolicyVersion}'s
 * {@code APPROVED}/{@code EFFECTIVE} statuses are where that gate belongs. <b>A caller
 * loading tables from configuration therefore owns the approval check.</b> Stated here
 * because "the version record exists" reads like evidence of approval and is not.
 *
 * <h2>What stays in code</h2>
 *
 * <p>Routing itself is not reimplemented here. {@link #route} and {@link #replay}
 * delegate to {@link DefaultEventRouter}, which keeps exactly one rule out of the
 * table: where {@link RateType#FIXED} meets a driver for which
 * {@link RateDriver#isMarketMovement()} holds, the event routes to
 * {@code MODIFICATION_TEST} whatever the row says. That override belongs in code and
 * must survive every version in this registry, because its premise is the instrument's
 * own terms rather than any reading of B5.4.5 — a fixed-rate instrument has no term
 * that reprices off a benchmark, so the combination can only have arisen from
 * renegotiation. Treatment keys off the instrument's rate type and the event's driver
 * tag, never off the observation that the rate moved (FR-507). Putting that in data
 * would let a future table version quietly route a renegotiated fixed-rate loan to a
 * reset, and the entire modification question would disappear from the ledger without
 * anyone declining it.
 *
 * <p>Immutable, with no clock. {@link #with} returns a new registry rather than
 * mutating this one, so a table series handed to a close run cannot change under it;
 * and every selection takes its date as an argument, because a routing has to be
 * answerable as of a stated date years after the fact.
 */
public final class RoutingTableRegistry {

    /**
     * Stateless and pure, so one shared instance serves every routing (see
     * {@link EventRouter}). Held as the interface, not the implementation, so that the
     * one code-side rule lives in exactly one place.
     */
    private static final EventRouter ROUTER = DefaultEventRouter.INSTANCE;

    /**
     * Ascending by effective date, and strictly so — equal dates are rejected at
     * construction. Selection therefore walks backwards and takes the first hit.
     */
    private final List<RoutingTable> tables;

    /**
     * Version id to table, for replay. A lookup index only — its iteration order is
     * unspecified and nothing reads it in order; {@link #versionIds} answers from
     * {@link #tables} where order is meant.
     */
    private final Map<String, RoutingTable> byVersionId;

    private RoutingTableRegistry(List<RoutingTable> supplied) {
        if (supplied.isEmpty()) {
            // Not an empty-but-usable registry. Routing is mandatory for every
            // cash-flow-change event (FR-504), so a registry that can answer nothing is a
            // deployment that cannot process events — better refused at wiring time than
            // discovered on the first event of a close run.
            throw new IllegalArgumentException(
                "a routing table registry with no tables cannot route anything; routing every "
                    + "cash-flow-change event through an approved table is FR-504, not an option");
        }
        List<RoutingTable> sorted = new ArrayList<>(supplied.size());
        for (RoutingTable table : supplied) {
            sorted.add(Objects.requireNonNull(table, "table"));
        }
        sorted.sort(Comparator.comparing((RoutingTable table) -> table.version().effectiveFrom()));

        Map<String, RoutingTable> index = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            RoutingTable table = sorted.get(i);
            RoutingTableVersion version = table.version();

            // Same effective date: "the table in force on that date" has two answers, and
            // whichever one a lookup returned would depend on sort stability rather than on
            // an approval. Superseding a version means giving the successor its own effective
            // date, which is also what makes the changeover auditable.
            if (i > 0) {
                RoutingTableVersion previous = sorted.get(i - 1).version();
                if (previous.effectiveFrom().equals(version.effectiveFrom())) {
                    throw new IllegalArgumentException(
                        "routing table versions '" + previous.id() + "' and '" + version.id()
                            + "' are both effective " + version.effectiveFrom()
                            + "; two tables in force on one date make the routing ambiguous, and an "
                            + "ambiguous routing resolved by insertion order is reproducible only by "
                            + "accident. A superseding version needs its own effective date");
                }
            }

            // Same id, different mapping: every event routed by either is indistinguishable
            // on replay. RoutingTable.reroute refuses this for the same reason; the registry
            // closes the path around it, where two independently-built tables are collected.
            RoutingTable clash = index.putIfAbsent(version.id(), table);
            if (clash != null) {
                throw new IllegalArgumentException(
                    "routing table version id '" + version.id() + "' appears twice (effective "
                        + clash.version().effectiveFrom() + " and " + version.effectiveFrom()
                        + "); two mappings claiming one identity make every event routed by either "
                        + "indistinguishable on replay");
            }
        }
        this.tables = List.copyOf(sorted);
        this.byVersionId = Map.copyOf(index);
    }

    /** The series, in any order; it is sorted here and validated for overlap. */
    public static RoutingTableRegistry of(List<RoutingTable> tables) {
        return new RoutingTableRegistry(Objects.requireNonNull(tables, "tables"));
    }

    /** As {@link #of(List)}. */
    public static RoutingTableRegistry of(RoutingTable... tables) {
        return new RoutingTableRegistry(List.of(Objects.requireNonNull(tables, "tables")));
    }

    /**
     * This series plus one newly approved table.
     *
     * <p>This is the exit-gate operation in one call: the H2 2026 Exposure Draft lands,
     * the accounting policy owner approves a table version encoding the new reading from
     * a stated date, it is appended, and events on and after that date route under it
     * while everything already closed continues to replay under the version it recorded.
     * No code changes.
     *
     * <p>Returns a new registry rather than mutating this one. A registry handed to a
     * close run must not acquire a new reading half way through the run — that would make
     * two contracts in one period route differently for no recorded reason.
     *
     * <p><strong>Strictly an append: the new table must take effect after every table
     * already held.</strong> Inserting one behind an existing version is the defect this
     * refusal names. Nothing in the type system stops it — the inserted version is not
     * {@link RoutingTableVersion#isRetrospective}, because that compares a version's own
     * approval and effective dates and knows nothing about what it was slid behind — and
     * the consequences are severe: an event dated in an already-closed month, re-routed by
     * date on an event-sourced recompute, would silently change mechanism, and reference
     * cases 3 and 4 differ by a 627.42 charge. Replay by recorded version id survives it,
     * but nothing else does. Amending history is a restatement and needs its own decision;
     * a registry assembled from configuration in arbitrary order uses {@link #of(List)},
     * which defines a series rather than amending one.
     *
     * @throws IllegalArgumentException where {@code table} does not take effect after
     *     every table already held
     */
    public RoutingTableRegistry with(RoutingTable table) {
        Objects.requireNonNull(table, "table");
        LocalDate latest = tables.get(tables.size() - 1).version().effectiveFrom();
        LocalDate incoming = table.version().effectiveFrom();
        if (!incoming.isAfter(latest)) {
            throw new IllegalArgumentException(
                "routing table version '" + table.version().id() + "' takes effect " + incoming
                    + ", on or before the latest version already held ('"
                    + tables.get(tables.size() - 1).version().id() + "', effective " + latest
                    + "). Adopting a new reading is an append; inserting one behind an existing "
                    + "version would re-route events in an already-closed period on the next "
                    + "recompute, which is a restatement decision and not an adoption");
        }
        List<RoutingTable> combined = new ArrayList<>(tables);
        combined.add(table);
        return new RoutingTableRegistry(combined);
    }

    /** The series, ascending by effective date. */
    public List<RoutingTable> tables() {
        return tables;
    }

    /** How many approved readings this registry holds. */
    public int size() {
        return tables.size();
    }

    /**
     * The first date any table in this registry governs.
     *
     * <p>Everything before it is the one gap the model can express; see
     * {@link #inForceOn}.
     */
    public LocalDate earliestEffectiveDate() {
        return tables.get(0).version().effectiveFrom();
    }

    /**
     * The table governing {@code asOf}, or empty where the date precedes every approved
     * table.
     *
     * <p>The non-throwing form, for a caller that has somewhere to put the answer "no
     * table" — an exception-queue entry, a coverage report — rather than needing the
     * routing now.
     */
    public Optional<RoutingTable> findInForceOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        // Latest wins. Walking backwards over an ascending series returns the greatest
        // effective date not after asOf, which is the whole of the resolution rule; ties are
        // impossible because equal effective dates were rejected at construction, so the
        // first hit is the only answer and no secondary tie-break exists to be relied on.
        //
        // Empty rather than a fallback. Returning RoutingTable.currentDefault() here would
        // reintroduce the compiled-in mapping ADR-0006 exists to remove: the routing would
        // come from the engine's own baseline while the event recorded a version the bank
        // never approved for that date.
        for (int i = tables.size() - 1; i >= 0; i--) {
            RoutingTable table = tables.get(i);
            if (table.isEffectiveOn(asOf)) {
                return Optional.of(table);
            }
        }
        return Optional.empty();
    }

    /**
     * The table governing {@code asOf}.
     *
     * @throws RoutingTableUnavailableException where the date precedes every approved
     *     table — never falling back to the nearest reading, because the fallback would
     *     stamp an event with a version id that version never governed
     */
    public RoutingTable inForceOn(LocalDate asOf) {
        return findInForceOn(asOf).orElseThrow(() ->
            RoutingTableUnavailableException.noneInForce(
                asOf, earliestEffectiveDate(), versionIds()));
    }

    /** The table carrying {@code versionId}, or empty where this registry has no such version. */
    public Optional<RoutingTable> findVersion(String versionId) {
        Objects.requireNonNull(versionId, "versionId");
        return Optional.ofNullable(byVersionId.get(versionId.strip()));
    }

    /**
     * The table carrying {@code versionId} — the replay lookup.
     *
     * @throws RoutingTableUnavailableException where the version is absent, rather than
     *     re-selecting by date and applying today's reading to a closed period
     */
    public RoutingTable version(String versionId) {
        Objects.requireNonNull(versionId, "versionId");
        // Stripped once, and the stripped form is what the diagnostic quotes: a message
        // naming a value that differs from the key actually searched sends the reader
        // looking for the wrong thing.
        String key = versionId.strip();
        return findVersion(key).orElseThrow(() ->
            RoutingTableUnavailableException.unknownVersion(key, versionIds()));
    }

    /** The held version ids, ascending by effective date. */
    public List<String> versionIds() {
        List<String> ids = new ArrayList<>(tables.size());
        for (RoutingTable table : tables) {
            ids.add(table.version().id());
        }
        return List.copyOf(ids);
    }

    /**
     * Routes a new event: select by date, then route.
     *
     * <p>The two arguments that decide the mechanism are the event's driver tag and the
     * instrument's rate type (FR-507). {@code eventDate} decides only <em>which approved
     * reading</em> applies — it is not a routing input, and in particular the fact that a
     * rate moved on that date is not one either.
     *
     * @param driver    the event's mandatory driver tag; a defaulted driver is a silently
     *                  wrong routing (FR-504)
     * @param rateType  the instrument's rate type, read from the instrument
     * @param eventDate the date the event occurred, which selects the version in force
     * @throws RoutingTableUnavailableException where no approved table governs that date
     */
    public RoutingDecision route(RateDriver driver, RateType rateType, LocalDate eventDate) {
        return ROUTER.route(driver, rateType, inForceOn(eventDate));
    }

    /**
     * Re-routes a stored event under the version it recorded.
     *
     * <p>Deliberately takes the version id and <strong>no date</strong>. The date is what
     * a replay must not consult: a period closed under the old reading has to replay under
     * the old reading however many versions have been approved since, and that only holds
     * if the event's own
     * {@link RoutingDecision#routingTableVersionId()} — not the calendar — chooses the
     * table. This is the mechanism behind invariant DT-1 for routed events.
     *
     * @param recordedVersionId the id persisted on the event by the routing being replayed
     * @throws RoutingTableUnavailableException where this registry does not hold that
     *     version
     */
    public RoutingDecision replay(RateDriver driver, RateType rateType, String recordedVersionId) {
        return ROUTER.route(driver, rateType, version(recordedVersionId));
    }

    /**
     * Whether every date in {@code [from, to]} resolves to an approved table, and which
     * versions govern it — the pre-close routing coverage control.
     *
     * <p>Reported rather than thrown, because the caller is a close run assessing a whole
     * period: it wants the gap stated once, with its size, alongside its other results,
     * not the first uncovered event as a stack trace. See {@link RoutingCoverage} for why
     * the answer is not an {@link com.crisil.eir.domain.InvariantResult} already — the
     * identifier the claim needs does not exist yet, and borrowing one that means
     * something else would put two different claims under a single invariant.
     *
     * <p>Every version governing part of the window is named, not just the one governing
     * its first day. A window can span a changeover, and "one reading governed the whole
     * period" is exactly the sentence an auditor must not be handed when two did: the
     * changeover is the materially significant fact, since the two readings differ by a
     * 627.42 charge on reference cases 3 and 4.
     *
     * @throws IllegalArgumentException where {@code to} precedes {@code from}; an inverted
     *     window is a defect in the caller, not a fact about the configuration
     */
    public RoutingCoverage coverageOver(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException(
                "coverage window " + from + ".." + to + " ends before it begins");
        }
        LocalDate earliest = earliestEffectiveDate();

        // The one expressible gap is at the front: a version has an effective-from and no
        // expiry, so consecutive tables cannot fail to meet and no interior gap exists.
        LocalDate firstUncovered = null;
        LocalDate lastUncovered = null;
        long uncoveredDays = 0;
        if (from.isBefore(earliest)) {
            firstUncovered = from;
            // Clipped to the window's own end, so a window entirely before the series
            // reports its own length rather than the distance to the series.
            lastUncovered = to.isBefore(earliest) ? to : earliest.minusDays(1);
            // Inclusive of both ends: a single uncovered day is one day, not zero.
            uncoveredDays = ChronoUnit.DAYS.between(firstUncovered, lastUncovered) + 1;
        }

        return new RoutingCoverage(
            from, to, uncoveredDays, firstUncovered, lastUncovered, versionsGoverning(from, to));
    }

    /**
     * The ids of every version governing at least one date in {@code [from, to]}, in force
     * order.
     *
     * <p>A version intersects the window when it takes effect on or before the window ends
     * and is not superseded before the window begins. The successor's effective date is the
     * first date the predecessor no longer governs, hence the strict comparison on it.
     */
    private List<String> versionsGoverning(LocalDate from, LocalDate to) {
        List<String> governing = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            LocalDate effective = tables.get(i).version().effectiveFrom();
            if (effective.isAfter(to)) {
                break;
            }
            boolean supersededBeforeWindow = i + 1 < tables.size()
                && !tables.get(i + 1).version().effectiveFrom().isAfter(from);
            if (!supersededBeforeWindow) {
                governing.add(tables.get(i).version().id());
            }
        }
        return governing;
    }

    /**
     * One audit sentence per held version, in force order.
     *
     * <p>The changeover dates are the point: "which reading was in force when" is the
     * first question asked of a routing, and it should be answerable without reconstructing
     * it from eight separate version records.
     */
    public String describe() {
        StringBuilder text = new StringBuilder("routing table registry, ")
            .append(size())
            .append(" approved version(s):");
        for (int i = 0; i < tables.size(); i++) {
            RoutingTableVersion version = tables.get(i).version();
            LocalDate supersededOn = i + 1 < tables.size()
                ? tables.get(i + 1).version().effectiveFrom()
                : null;
            text.append("\n  ")
                .append(version.id())
                .append(" in force ")
                .append(version.effectiveFrom())
                // Inclusive last day: a version governs up to the day before its successor
                // takes effect, and an exclusive end date reads as an off-by-one to anyone
                // reconciling a changeover-date event.
                .append(supersededOn == null ? " onwards" : " until " + supersededOn.minusDays(1))
                .append(", approved by ")
                .append(version.checker())
                .append(" on ")
                .append(version.approvedOn())
                .append(version.isRetrospective() ? " (RETROSPECTIVE)" : "");
        }
        return text.toString();
    }
}
