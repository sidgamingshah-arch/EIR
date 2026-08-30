package com.crisil.eir.api.modules.contracts;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.calc.routing.InstrumentSide;
import com.crisil.eir.calc.routing.QualitativeTrigger;
import com.crisil.eir.domain.CashFlow;
import com.crisil.eir.domain.FlowKind;
import com.crisil.eir.domain.FlowVector;
import com.crisil.eir.domain.Money;
import com.crisil.eir.domain.RateDriver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One event submission, validated at the API boundary — where the driver tag is refused, not
 * defaulted (FR-504, 06 § 3).
 *
 * <p><b>This class exists for one line of code, and it is the first check in {@link #parse}.</b>
 * {@code driver} is required. The engine routes on it plus the instrument's rate type and it never
 * infers treatment from the observation that the rate moved (FR-507). An EBLR reset and a
 * renegotiated fixed rate are <em>indistinguishable from the observation</em> — both are "the rate
 * changed" — and routed on the observation the renegotiation silently gets a B5.4.5 reset: no
 * catch-up, no substantiality test, no derecognition assessment. Reference cases 3 and 4 are the
 * same instrument in the same month under the two readings and they differ by a 627.42 charge.
 *
 * <p>There is no invariant that detects that after the fact — nothing downstream can tell a reset
 * that was correct from a reset that should have been a modification, because both produce a rate
 * and a balance that reconcile — which is why the discrimination has to be <em>structural</em>. So
 * an untagged event is rejected here, at the edge, with a 400. It is never defaulted to the most
 * common driver, never inferred from the rate movement, and never carried forward as "unknown" for
 * a later step to guess at.
 *
 * <p><b>Why the driver is checked before anything else.</b> The order of refusals is part of the
 * control. If {@code contractId} were validated first, an untagged event naming a contract that is
 * not on the book would come back as "no such contract" — a message the caller fixes by correcting
 * the id, after which they get a routed answer for an event they never tagged. The refusal an
 * integrator sees must be the refusal that matters.
 *
 * <p><b>Every accessor refuses rather than defaults</b>, in {@link FormBody}'s own words. The
 * revised flow vector is mandatory for the same reason
 * {@code com.crisil.eir.application.run.PeriodEvent} makes it mandatory: an event that changed no
 * future cash flow has nothing for the routing table to route, and a reset solved against an empty
 * vector would report a rate for a contract with no remaining flows.
 *
 * <p><b>The contract id arrives in the form, not in the path, and that is a seam limitation rather
 * than a design choice.</b> {@code Routes.post} hands a handler a {@code FormBody} and nothing else,
 * so a POST handler cannot read {@code {id}} out of {@code /api/contracts/{id}/events}. The path
 * segment is accepted and ignored; the id that is actually used is the form field, and the refusal
 * below says so rather than letting a caller believe the path was read. See
 * {@code ContractsAndEventsModule}'s class javadoc for what the seam would need.
 */
public final class EventSubmission {

    /** The mandatory driver tag (FR-504). */
    public static final String DRIVER = "driver";

    /** The event's own date, which selects the routing table version in force. */
    public static final String EVENT_DATE = "eventDate";

    /** The instrument. In the form because the seam gives a POST handler no path. */
    public static final String CONTRACT_ID = "contractId";

    private static final String REVISED_FLOWS = "revisedFlows";
    private static final String REVISED_INSTALMENT = "revisedInstalment";
    private static final String REVISED_PERIODS = "revisedPeriods";
    private static final String ORIGINAL_FLOWS = "originalFlows";
    private static final String ORIGINAL_INSTALMENT = "originalInstalment";
    private static final String ORIGINAL_PERIODS = "originalPeriods";
    private static final String SIDE = "side";
    private static final String TRIGGERS = "triggers";

    /** The value of {@code triggers} that means "assessed, and nothing fired". */
    public static final String NO_TRIGGERS = "NONE";

    private final String contractId;
    private final RateDriver driver;
    private final LocalDate eventDate;
    private final FlowVector revisedFlows;
    private final FlowVector suppliedOriginalFlows;
    private final InstrumentSide side;
    private final List<QualitativeTrigger> triggers;
    private final boolean qualitativeAssessmentDeclared;

    private EventSubmission(
        String contractId,
        RateDriver driver,
        LocalDate eventDate,
        FlowVector revisedFlows,
        FlowVector suppliedOriginalFlows,
        InstrumentSide side,
        List<QualitativeTrigger> triggers,
        boolean qualitativeAssessmentDeclared) {
        this.contractId = contractId;
        this.driver = driver;
        this.eventDate = eventDate;
        this.revisedFlows = revisedFlows;
        this.suppliedOriginalFlows = suppliedOriginalFlows;
        this.side = side;
        this.triggers = List.copyOf(triggers);
        this.qualitativeAssessmentDeclared = qualitativeAssessmentDeclared;
    }

    /**
     * Whether this POST is an event submission rather than an onboarding.
     *
     * <p>Told apart on {@code driver} or {@code eventDate}, neither of which an onboarding request
     * carries and one of which an event always does. <b>This is not the engine inferring
     * treatment.</b> It tells two resources apart because the route seam cannot — one POST context
     * serves the whole {@code /api/contracts} subtree — and the treatment of an event that lands
     * here is still routed strictly on its explicit driver tag. Critically, an event carrying
     * {@code eventDate} and <em>no</em> driver still arrives here, and is refused here, which is
     * the case that matters: the alternative discriminator ({@code driver} alone) would have sent
     * exactly the untagged event to the onboarding handler and buried FR-504's refusal under a
     * complaint about a missing principal.
     */
    public static boolean looksLikeEvent(FormBody body) {
        Objects.requireNonNull(body, "body");
        return body.has(DRIVER) || body.has(EVENT_DATE);
    }

    /**
     * Reads and validates one submission, refusing in the order the controls matter.
     *
     * @throws FormBody.BadRequest on any incomplete or unreadable submission — a 400, because a
     *     missing driver tag is a malformed event and not an engine refusal
     */
    public static EventSubmission parse(FormBody body) {
        Objects.requireNonNull(body, "body");

        // ---- FR-504. First, and never defaulted. See the class javadoc. --------------------
        if (!body.has(DRIVER)) {
            throw new FormBody.BadRequest(
                "'driver' is required on every event and this engine will not default it (FR-504)."
                    + " Treatment is routed on the driver tag plus the instrument's rate type, never"
                    + " on the observation that the rate moved (FR-507): a benchmark reset and a"
                    + " renegotiated fixed rate are identical from the movement and are a B5.4.5"
                    + " reset and a modification respectively. Routed on the observation the"
                    + " renegotiation gets a reset — no catch-up, no substantiality test, no"
                    + " derecognition assessment — and no downstream invariant can detect it,"
                    + " because both readings produce a rate and a balance that reconcile."
                    + " Tag the event with one of " + driverNames() + ".");
        }
        RateDriver driver = driver(body.text(DRIVER));

        LocalDate eventDate = date(body, EVENT_DATE);
        String contractId = contractId(body);

        FlowVector revised = flows(body, eventDate, REVISED_FLOWS, REVISED_INSTALMENT,
            REVISED_PERIODS, "revised");
        if (revised == null) {
            throw new FormBody.BadRequest(
                "an event needs the revised flows it produced: send '" + REVISED_FLOWS
                    + "=YYYY-MM-DD:amount;YYYY-MM-DD:amount' or the level shorthand '"
                    + REVISED_INSTALMENT + "=amount&" + REVISED_PERIODS + "=n'."
                    + " An event that changes no future cash flow has nothing for the routing table"
                    + " to route, and a reset solved against an empty vector would report a rate for"
                    + " a contract with no remaining flows. Nothing is defaulted here: the engine"
                    + " does not invent the schedule the parties agreed.");
        }
        FlowVector suppliedOriginal = flows(body, eventDate, ORIGINAL_FLOWS, ORIGINAL_INSTALMENT,
            ORIGINAL_PERIODS, "original remaining");

        InstrumentSide side = body.has(SIDE) ? side(body.text(SIDE)) : null;
        boolean declared = body.has(TRIGGERS);
        List<QualitativeTrigger> triggers = declared ? triggers(body.text(TRIGGERS)) : List.of();

        return new EventSubmission(contractId, driver, eventDate, revised, suppliedOriginal, side,
            triggers, declared);
    }

    // ---- what the routing needs ------------------------------------------------------------

    public String contractId() {
        return contractId;
    }

    public RateDriver driver() {
        return driver;
    }

    public LocalDate eventDate() {
        return eventDate;
    }

    public FlowVector revisedFlows() {
        return revisedFlows;
    }

    /** The remaining original leg the caller supplied, or null to derive it from the schedule. */
    public FlowVector suppliedOriginalFlows() {
        return suppliedOriginalFlows;
    }

    /** Whether the caller stated which leg of the balance sheet this is. */
    public boolean hasSide() {
        return side != null;
    }

    /**
     * Asset or liability, which decides whether the 10% test <em>decides</em> or only
     * <em>evidences</em> substantiality.
     *
     * <p>Required rather than defaulted where the routing reaches a modification test, and the
     * refusal is deliberate: IFRS 9 B3.3.6 sets a bright line for liabilities and sets
     * <em>none</em> for assets, so a defaulted side would decide a judgement question by
     * implementation detail on figures that differ by the whole balance. {@code InstrumentSide}'s
     * own javadoc calls the asymmetry the most consequential single gap in the standard for a bank
     * with a restructuring book, which is why it is a required parameter of
     * {@code ModificationTest.evaluate} and required here.
     */
    public InstrumentSide side() {
        if (side == null) {
            throw new FormBody.BadRequest(
                "this event routes to a substantiality assessment, so '" + SIDE + "' is required and"
                    + " is not defaulted: send " + SIDE + "=ASSET or " + SIDE + "=LIABILITY."
                    + " IFRS 9 B3.3.6 makes the 10% test authoritative for a financial liability and"
                    + " sets no equivalent bright line for a financial asset, where the number is"
                    + " evidence and a person concludes. Defaulting the side would settle that"
                    + " question by implementation detail.");
        }
        return side;
    }

    /** The qualitative triggers the assessor recorded; empty where the assessment found none. */
    public List<QualitativeTrigger> triggers() {
        return triggers;
    }

    /**
     * Whether a qualitative assessment was stated to have been performed at all.
     *
     * <p>An absent {@code triggers} field is <em>not</em> the same as {@code triggers=NONE}, and
     * collapsing the two is how a modification comes to be "assessed" on the ratio alone.
     * {@code ModificationTest.evaluate}'s convenience overload says the same thing about itself:
     * empty means the assessment was performed and found nothing, not that it was skipped. So the
     * caller must say which, and a modification test with no statement is refused rather than
     * quietly reported as clean.
     */
    public void requireQualitativeAssessment() {
        if (!qualitativeAssessmentDeclared) {
            throw new FormBody.BadRequest(
                "this event routes to a substantiality assessment, so '" + TRIGGERS + "' is required:"
                    + " send a comma-separated list of " + triggerNames() + ", or " + TRIGGERS + "="
                    + NO_TRIGGERS + " to record that the qualitative assessment was performed and"
                    + " nothing fired. An absent field is not the same statement as an empty list —"
                    + " a modification assessed on the 10% ratio alone is not assessed, and the"
                    + " response would otherwise report a clean qualitative result nobody produced.");
        }
    }

    // ---- parsing ---------------------------------------------------------------------------

    private static String contractId(FormBody body) {
        if (!body.has(CONTRACT_ID)) {
            throw new FormBody.BadRequest(
                "'contractId' is required in the form body. The route seam hands a POST handler the"
                    + " parsed body and nothing else, so the '{id}' segment of"
                    + " /api/contracts/{id}/events cannot be read here; the path segment is accepted"
                    + " and ignored, and the id that is used is this field. Reading the id from a"
                    + " path this layer cannot see would mean guessing which contract the event"
                    + " belongs to, and an event applied to the wrong instrument restates the wrong"
                    + " balance.");
        }
        return body.text(CONTRACT_ID);
    }

    private static RateDriver driver(String raw) {
        try {
            return RateDriver.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new FormBody.BadRequest(
                "'driver' must be one of " + driverNames() + ", got '" + raw + "'."
                    + " An unrecognised tag is refused rather than mapped to the nearest match:"
                    + " CREDIT_RISK_MARKET and CREDIT_RATCHET_PREDETERMINED differ by one word and"
                    + " route to a reset and a catch-up respectively.");
        }
    }

    private static InstrumentSide side(String raw) {
        try {
            return InstrumentSide.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new FormBody.BadRequest(
                "'side' must be ASSET or LIABILITY, got '" + raw + "'");
        }
    }

    private static List<QualitativeTrigger> triggers(String raw) {
        if (NO_TRIGGERS.equalsIgnoreCase(raw.strip())) {
            return List.of();
        }
        EnumSet<QualitativeTrigger> fired = EnumSet.noneOf(QualitativeTrigger.class);
        for (String token : raw.split(",")) {
            String name = token.strip();
            if (name.isEmpty()) {
                continue;
            }
            try {
                fired.add(QualitativeTrigger.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new FormBody.BadRequest(
                    "'triggers' must be " + NO_TRIGGERS + " or a comma-separated list of "
                        + triggerNames() + ", got '" + name + "'");
            }
        }
        if (fired.isEmpty()) {
            // 'NONE' is the only way to reach an empty list, and the check is the whole point of
            // this method. A value of separators alone — 'triggers=,' — parsed to no tokens and
            // came back as an empty list with the assessment recorded as performed, which is
            // exactly the state this class's javadoc says must be inexpressible: the response then
            // reports a clean qualitative result nobody produced. Only the literal NONE asserts
            // that the assessment ran and found nothing.
            throw new FormBody.BadRequest(
                "'triggers' was sent as '" + raw + "', which names no trigger and is not the literal "
                    + NO_TRIGGERS + ". An empty list is a positive statement — the qualitative"
                    + " assessment was performed and nothing fired — and only " + NO_TRIGGERS
                    + " makes it. A value that parses to nothing would record an assessment nobody"
                    + " performed.");
        }
        return List.copyOf(fired);
    }

    private static LocalDate date(FormBody body, String key) {
        String raw = body.text(key);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            throw new FormBody.BadRequest(
                "'" + key + "' must be an ISO-8601 date such as 2028-05-31, got '" + raw + "'");
        }
    }

    /**
     * One leg of the event, from either encoding, anchored on the event date.
     *
     * <p>Two encodings because the flat form body carries no arrays and both shapes are needed. The
     * explicit list states every flow, which is what a restructuring actually looks like and what
     * lets a fee settled <em>on</em> the event date enter the 10% test undiscounted (B3.3.6 is net
     * of fees paid and received). The level shorthand lays {@code n} equal flows one period apart
     * starting one period after the event, which is the ordinary rescheduled annuity.
     *
     * @return the vector, or null where the caller supplied neither encoding
     */
    private static FlowVector flows(
        FormBody body, LocalDate anchor, String listKey, String levelKey, String countKey,
        String what) {

        boolean hasList = body.has(listKey);
        boolean hasLevel = body.has(levelKey);
        if (hasList && hasLevel) {
            throw new FormBody.BadRequest(
                "send either '" + listKey + "' or '" + levelKey + "' for the " + what
                    + " leg, not both; two encodings of one vector cannot be reconciled here and"
                    + " silently preferring one would publish a present value the caller did not ask"
                    + " for");
        }
        if (hasList) {
            return fromList(body.text(listKey), anchor, listKey);
        }
        if (hasLevel) {
            return level(body.decimal(levelKey), body.integer(countKey), anchor, countKey);
        }
        if (body.has(countKey)) {
            throw new FormBody.BadRequest(
                "'" + countKey + "' was sent without '" + levelKey + "', so the " + what
                    + " leg has a length and no amount");
        }
        return null;
    }

    /** {@code YYYY-MM-DD:amount;YYYY-MM-DD:amount}, in any order; sorted and indexed here. */
    private static FlowVector fromList(String raw, LocalDate anchor, String key) {
        List<CashFlow> parsed = new ArrayList<>();
        for (String token : raw.split("[;,]")) {
            String pair = token.strip();
            if (pair.isEmpty()) {
                continue;
            }
            int split = pair.lastIndexOf(':');
            if (split < 0) {
                throw new FormBody.BadRequest(
                    "'" + key + "' entries are 'YYYY-MM-DD:amount' separated by ';', got '"
                        + pair + "'");
            }
            LocalDate when = flowDate(pair.substring(0, split).strip(), key);
            BigDecimal amount = amount(pair.substring(split + 1).strip(), key);
            if (when.isBefore(anchor)) {
                throw new FormBody.BadRequest(
                    "'" + key + "' carries a flow dated " + when + " before the event date " + anchor
                        + "; a flow already settled is not part of the revised expectation and"
                        + " discounting it would restate the balance for cash the book has already"
                        + " taken");
            }
            parsed.add(CashFlow.of(when, 0, Money.of(amount, Money.INR), FlowKind.COMBINED_EMI));
        }
        if (parsed.isEmpty()) {
            throw new FormBody.BadRequest("'" + key + "' was sent and parsed to no flows");
        }
        return indexed(parsed, anchor, Money.INR);
    }

    private static FlowVector level(
        BigDecimal instalment, int periods, LocalDate anchor, String countKey) {
        if (periods < 1) {
            throw new FormBody.BadRequest(
                "'" + countKey + "' must be at least 1, got " + periods
                    + "; a revised leg of no flows is an event that changed nothing");
        }
        List<CashFlow> laid = new ArrayList<>(periods);
        for (int ordinal = 1; ordinal <= periods; ordinal++) {
            laid.add(CashFlow.of(anchor.plusMonths(ordinal), ordinal,
                Money.of(instalment, Money.INR), FlowKind.COMBINED_EMI));
        }
        return FlowVector.of(anchor, Money.INR, laid);
    }

    /**
     * Assigns the period ordinals the time convention reads.
     *
     * <p>Zero for a flow dated on the anchor and 1..n in date order for the rest.
     * {@code TimeConvention.PeriodicIndex} takes tau straight from the ordinal, so an anchor-dated
     * flow at ordinal 0 discounts at a factor of one — which is what a fee settled on the
     * modification date must do — while the discounted leg counts whole periods from the event.
     */
    private static FlowVector indexed(List<CashFlow> parsed, LocalDate anchor, Currency currency) {
        parsed.sort(Comparator.comparing(CashFlow::date));
        List<CashFlow> indexed = new ArrayList<>(parsed.size());
        int ordinal = 0;
        for (CashFlow flow : parsed) {
            int index = flow.date().equals(anchor) ? 0 : ++ordinal;
            indexed.add(CashFlow.of(flow.date(), index, flow.amount(), flow.kind()));
        }
        return FlowVector.of(anchor, currency, indexed);
    }

    private static LocalDate flowDate(String raw, String key) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            throw new FormBody.BadRequest(
                "'" + key + "' carries '" + raw + "' where an ISO-8601 date was expected");
        }
    }

    private static BigDecimal amount(String raw, String key) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException notANumber) {
            throw new FormBody.BadRequest(
                "'" + key + "' carries '" + raw + "' where a decimal amount was expected");
        }
    }

    private static String driverNames() {
        List<String> names = new ArrayList<>(RateDriver.values().length);
        for (RateDriver value : RateDriver.values()) {
            names.add(value.name());
        }
        return String.join(", ", names);
    }

    private static String triggerNames() {
        List<String> names = new ArrayList<>(QualitativeTrigger.values().length);
        for (QualitativeTrigger value : QualitativeTrigger.values()) {
            names.add(value.name());
        }
        return String.join(", ", names);
    }
}
