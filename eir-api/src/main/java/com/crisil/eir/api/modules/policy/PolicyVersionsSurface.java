package com.crisil.eir.api.modules.policy;

import com.crisil.eir.api.http.FormBody;
import com.crisil.eir.api.http.Json;
import com.crisil.eir.api.store.Book;
import com.crisil.eir.api.store.Seed;
import com.crisil.eir.domain.InvariantResult;
import com.crisil.eir.domain.Precision;
import com.crisil.eir.domain.Rate;
import com.crisil.eir.policy.PolicyKind;
import com.crisil.eir.policy.PolicyVersion;
import com.crisil.eir.policy.PolicyVersionStatus;
import com.crisil.eir.policy.approval.ApprovalRecord;
import com.crisil.eir.policy.approval.MakerCheckerGate;
import com.crisil.eir.policy.approval.TransitionResult;
import com.crisil.eir.policy.preview.ActivationDecision;
import com.crisil.eir.policy.preview.ActivationGate;
import com.crisil.eir.policy.preview.DraftFingerprint;
import com.crisil.eir.policy.preview.ImpactPreview;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 06 § 5's policy-version lifecycle: list, draft, the <b>mandatory</b> impact preview, and the
 * checker approval that returns {@code 409} without one (FR-210).
 *
 * <h2>The gate is the deliverable</h2>
 *
 * <p>06 § 5, verbatim: "Approval without an impact preview returns 409. For a CPR curve change the
 * preview is a catch-up across every affected contract simultaneously, so the number can be
 * material — approving blind is exactly what the gate prevents." Everything else on this surface
 * exists so that refusal has something to refuse. The refusal itself is
 * {@link ActivationGate}'s, computed in {@code eir-policy} and reported here — this class decides
 * no policy question of its own, which is what {@code ApiModule} requires of a handler.
 *
 * <p><b>Two gates, both reported, one status code.</b> FR-210 has a maker–checker limb and an
 * impact-preview limb, and they refuse for different reasons. Both are evaluated on every approval
 * attempt and both come back in the body, because "the whole list comes back" — an approval that
 * is both unpreviewed and a self-approval should tell its caller both, not the first one the code
 * happened to check. Only the impact-preview limb sets the status code, and that is deliberate:
 * 06 § 5 attaches {@code 409} to exactly one condition, so on this surface a {@code 409} means
 * exactly one thing and a caller can act on the code without parsing the body. Every other refusal
 * — a self-approval, a duplicate draft id, a version already approved — comes back on a
 * {@code 200} as a value, which is this engine's standing posture (see {@code EirServer}).
 *
 * <h2>Why this is an HttpHandler and not two Routes registrations</h2>
 *
 * <p>{@code Routes} offers a GET taking the exchange and a POST taking only a parsed form body,
 * and neither can express this section. Three things are missing: a status code other than
 * 200/400/500, since {@code EirServer.answer} hard-codes {@code 200} for every answer a handler
 * returns; a path parameter on a POST, since a POST handler never sees the request path, so
 * {@code /{id}/approve} and {@code /{id}/impact-preview} are indistinguishable from each other and
 * from each other's ids; and both verbs on one path, since each registration creates a JDK context
 * and a second context at the same path is rejected — {@code GET /api/policy-versions} and
 * {@code POST /api/policy-versions} are both in the specification table. So this class owns one
 * JDK context for the whole {@code /api/policy-versions} subtree and does its own method and path
 * dispatch. It mirrors {@code EirServer}'s own mapping exactly — 400 for a malformed request, 500
 * for a defect with the exception's own message, {@code application/json} and {@code no-store} —
 * so that the two halves of the surface behave the same way. See {@code PolicyVersionsModule} for
 * how it is attached and what the route seam would need in order to make this class unnecessary.
 *
 * <h2>Why this surface runs on the book's clock</h2>
 *
 * <p>{@code eir-api} may read a clock — the determinism scan covers {@code eir-domain} and
 * {@code eir-calc} — but a wall clock would break this surface rather than serve it. The
 * demonstration book is positioned at {@link Seed#PERIOD_END}, 31 May 2028, and
 * {@link ImpactPreview#incoherences()} refuses a preview "generated before the book position it
 * claims to have measured had begun in any time zone". A preview stamped with today's real instant
 * against a book dated in 2028 is therefore self-contradictory, and the activation gate would
 * refuse it as {@code INCOHERENT_PREVIEW} — a control firing on a fixture artefact rather than on
 * anything about the policy. So both the generation instant and the activation instant are the
 * book's own recorded as-at, which is what {@code EirService} already uses for every figure it
 * publishes.
 *
 * <p><b>The consequence, stated rather than hidden:</b> with generation and activation on the same
 * instant, the gate's 90-day staleness horizon
 * ({@link ActivationGate#DEFAULT_PREVIEW_HORIZON}) cannot fire on this book, and neither can
 * {@code PREVIEW_POSTDATES_ACTIVATION}. Both are exercised where they belong, in
 * {@code eir-policy}'s own tests over a controlled clock. What this surface proves is the refusal
 * that a real operator meets: approval attempted with nothing on file.
 */
public final class PolicyVersionsSurface implements HttpHandler {

    /** The subtree this surface owns. 06 § 5's {@code /policy-versions} under this API's base. */
    public static final String BASE = "/api/policy-versions";

    /** Named on every response, so a drifting implementation is visible rather than renamed. */
    public static final String SPEC_SECTION = "06 § 5";

    /** The business date the book is positioned at — the "as of" of every preview. */
    public static final LocalDate BOOK_BUSINESS_DATE = Seed.PERIOD_END;

    /**
     * The instant the book's figures were known: the first instant after its business date, UTC.
     *
     * <p>Derived from {@link Seed#PERIOD_END} rather than written out, so that moving the seeded
     * book cannot leave this constant behind pointing at a position the book no longer holds —
     * which would make every preview incoherent for a reason no reader could find.
     */
    public static final Instant BOOK_AS_AT =
        BOOK_BUSINESS_DATE.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    private static final String JSON = "application/json; charset=utf-8";

    private final PolicyVersionsStore store;
    private final PortfolioImpactPreview previews;
    private final ActivationGate gate;

    public PolicyVersionsSurface(
        PolicyVersionsStore store, PortfolioImpactPreview previews, ActivationGate gate) {
        this.store = Objects.requireNonNull(store, "store");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * The surface over one book: its policy versions seeded from the book's registry, its previews
     * measured against the book's holdings, and the gate at its documented default horizon.
     */
    public static PolicyVersionsSurface over(Book book) {
        Objects.requireNonNull(book, "book");
        return new PolicyVersionsSurface(
            PolicyVersionsStore.seededFrom(book.policies()),
            new PortfolioImpactPreview(
                PortfolioImpactPreview.Position.measure(book.holdings(), BOOK_BUSINESS_DATE),
                BOOK_AS_AT),
            ActivationGate.withDefaultHorizon());
    }

    /** One answer: the status code and the body. */
    public record Answer(int status, Json.Obj body) {
        public Answer {
            Objects.requireNonNull(body, "body");
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Answer answer;
        try {
            String method = exchange.getRequestMethod();
            // The body is only read for a POST. Reading it on a GET would block on clients that
            // send no body and no content-length, and a GET on this surface takes no input.
            FormBody body = FormBody.parse("POST".equals(method) ? readBody(exchange) : "");
            answer = route(method, exchange.getRequestURI().getPath(), body);
        } catch (FormBody.BadRequest malformed) {
            answer = new Answer(400, Json.object()
                .str("error", "bad request")
                .str("detail", malformed.getMessage()));
        } catch (RuntimeException defect) {
            // Reported with its own message, exactly as EirServer does, because a defect swallowed
            // at the edge becomes a data-quality ticket against a policy version that is fine.
            answer = new Answer(500, Json.object()
                .str("error", defect.getClass().getSimpleName())
                .str("detail", defect.getMessage() == null ? "(no message)" : defect.getMessage()));
        }
        respond(exchange, answer);
    }

    /**
     * Method and path dispatch for the whole subtree.
     *
     * <p>Package-visible and separated from {@link #handle} so the routing table can be read
     * without a socket in the way; the tests drive it over a real socket anyway, because a status
     * code chosen correctly and then written after the body is a defect only a socket sees.
     */
    Answer route(String method, String path, FormBody body) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(body, "body");

        if (!path.equals(BASE) && !path.startsWith(BASE + "/")) {
            return notFound(path);
        }
        String remainder = path.substring(BASE.length());
        if (remainder.isEmpty() || "/".equals(remainder)) {
            return switch (method) {
                case "GET" -> list();
                case "POST" -> draft(body);
                default -> methodNotAllowed(method, path, "GET, POST");
            };
        }

        String[] segments = remainder.substring(1).split("/", -1);
        if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            return notFound(path);
        }
        // Taken as-is, and NOT run through URLDecoder. HttpExchange hands over
        // getRequestURI().getPath(), which the JDK has already percent-decoded, so decoding again
        // would be a second pass: an id drafted as "POL+FEE-2029.1" would arrive here as
        // "POL+FEE-2029.1" and be turned into "POL FEE-2029.1" — the store would miss it, and that
        // version could never be previewed or approved, on a 404 saying it does not exist.
        String id = segments[0];
        String action = segments[1];
        if (!"impact-preview".equals(action) && !"approve".equals(action)) {
            return notFound(path);
        }
        if (!"POST".equals(method)) {
            return methodNotAllowed(method, path, "POST");
        }
        return "impact-preview".equals(action) ? impactPreview(id) : approve(id, body);
    }

    // ---- GET /api/policy-versions ----------------------------------------------------------

    /**
     * Every version held, with its status and what stands between it and approval.
     *
     * <p>The gate's decision is on each row rather than left for a caller to infer from a preview
     * count. A count of zero and a count of one are not the two states that matter: a version with
     * a stored preview of a superseded draft has a count of one and is refused, and that is the
     * refusal 06 § 5 is written for. Publishing the decision means the list cannot disagree with
     * what the approval endpoint will do.
     */
    private Answer list() {
        List<Json.Obj> rows = new ArrayList<>();
        for (PolicyVersion version : store.all()) {
            DraftFingerprint draft = store.currentDraftOf(version.id());
            ActivationDecision decision =
                gate.decideFromRegister(version, draft, store.previews(), BOOK_AS_AT);
            rows.add(Json.object()
                .str("id", version.id())
                .str("kind", version.kind().name())
                .str("description", version.description())
                .str("effectiveFrom", version.effectiveFrom().toString())
                .str("status", version.status().name())
                .str("maker", version.maker())
                .str("checker", version.checker())
                .str("approvedOn",
                    version.approvedOn() == null ? null : version.approvedOn().toString())
                .bool("retrospective", version.isRetrospective())
                .str("draftFingerprint", draft.hex())
                .count("impactPreviewsStored", store.previewsStoredFor(version.id()))
                // Reported for every row including the versions already in force, where it reads
                // as a refusal on something that plainly went effective. That is not a defect in
                // the column, it is the column's point: no preview is on file for the seeded
                // versions, so an approval of one attempted today would be refused, and
                // awaitingApproval is what tells a reader whether the question is still live.
                .bool("awaitingApproval", !version.status().isApproved())
                .obj("impactPreviewGate", gateJson(decision))
                .strings("legalNextStatuses", names(version.status().legalSuccessors()))
                .str("audit", version.describe()));
        }
        return new Answer(200, Json.object()
            .str("specSection", SPEC_SECTION)
            .str("portfolioAsOf", BOOK_BUSINESS_DATE.toString())
            .count("count", rows.size())
            .array("versions", rows));
    }

    // ---- POST /api/policy-versions ---------------------------------------------------------

    /**
     * Drafts a new version. {@code status: DRAFT}, per 06 § 5's table.
     *
     * <p>{@code content} is optional and is free text describing what the draft changes. It is not
     * decoration: it goes into the draft's content fingerprint, so a draft that is edited stops
     * matching the preview taken before the edit. Without it every draft with the same
     * id/kind/description/date/maker fingerprints identically, and the staleness limb of the gate
     * would have nothing to detect.
     *
     * <p>A duplicate id is refused as a <em>value</em> on a 200 rather than as a second
     * {@code 409}. Reserving the conflict code for the impact-preview gate is what lets a caller
     * treat {@code 409} on this surface as one condition with one remedy.
     */
    private Answer draft(FormBody body) {
        String id = body.text("id");
        PolicyKind kind = kindOf(body.text("kind"));
        String description = body.text("description");
        LocalDate effectiveFrom = dateOf(body.text("effectiveFrom"));
        String maker = body.text("maker");
        String content = body.textOr("content", "");

        PolicyVersion drafted = drafted(id, kind, description, effectiveFrom, maker);
        // Test-and-write in one operation. Asking store.holds(id) and then writing would, under two
        // threads, let both find the id free and both write, and the loser would get an exception
        // where the answer it needs is the ordinary refusal below.
        if (!store.draftIfAbsent(drafted, content)) {
            PolicyVersion held = store.find(id).orElseThrow();
            return new Answer(200, Json.object()
                .str("specSection", SPEC_SECTION)
                .bool("drafted", false)
                .str("id", id)
                .str("refusal", "VERSION_ID_ALREADY_HELD")
                .str("detail", "policy version " + id + " is already held (" + held.describe()
                    + "). A version id is what a published figure cites, so drafting over one"
                    + " would silently re-point figures already reported; issue a new id"));
        }
        DraftFingerprint fingerprint = store.currentDraftOf(id);
        ActivationDecision decision =
            gate.decideFromRegister(drafted, fingerprint, store.previews(), BOOK_AS_AT);
        return new Answer(200, Json.object()
            .str("specSection", SPEC_SECTION)
            .bool("drafted", true)
            .str("id", id)
            .str("kind", kind.name())
            .str("status", PolicyVersionStatus.DRAFT.name())
            .str("effectiveFrom", effectiveFrom.toString())
            .str("maker", maker)
            .str("draftFingerprint", fingerprint.hex())
            .bool("impactPreviewRequired", kind.movesRecognisedIncome())
            .count("impactPreviewsStored", store.previewsStoredFor(id))
            .obj("impactPreviewGate", gateJson(decision))
            .str("nextStep", "POST " + BASE + "/" + id + "/impact-preview")
            .str("audit", drafted.describe()));
    }

    // ---- POST /api/policy-versions/{id}/impact-preview --------------------------------------

    /** Runs and stores the mandatory preview, or says why it will not certify one. */
    private Answer impactPreview(String id) {
        Optional<PolicyVersion> held = store.find(id);
        if (held.isEmpty()) {
            return unknownVersion(id);
        }
        PolicyVersion version = held.get();
        DraftFingerprint draft = store.currentDraftOf(id);
        PortfolioImpactPreview.Outcome outcome = previews.previewOf(version, draft);

        if (!outcome.produced()) {
            ActivationDecision decision =
                gate.decideFromRegister(version, draft, store.previews(), BOOK_AS_AT);
            return new Answer(200, Json.object()
                .str("specSection", SPEC_SECTION)
                .bool("previewed", false)
                .str("policyVersionId", id)
                .str("refusal", outcome.refusal().name())
                .str("detail", outcome.detail())
                .count("impactPreviewsStored", store.previewsStoredFor(id))
                .obj("impactPreviewGate", gateJson(decision))
                .obj("population", populationJson()));
        }

        ImpactPreview preview = outcome.preview();
        store.store(preview);
        ActivationDecision decision =
            gate.decideFromRegister(version, draft, store.previews(), BOOK_AS_AT);
        return new Answer(200, Json.object()
            .str("specSection", SPEC_SECTION)
            .bool("previewed", true)
            .str("policyVersionId", id)
            .str("draftFingerprint", preview.draftFingerprint().hex())
            .str("generatedAt", preview.generatedAt().toString())
            .str("portfolioAsOf", preview.portfolioAsOf().toString())
            .count("contractsAffected", Math.toIntExact(preview.contractsAffected()))
            // Every figure as a JSON string: a gross carrying amount through a double is how
            // 533914.11 becomes 533914.10999999997 in a control report (see Json).
            .figure("grossCarryingAmountDelta",
                preview.grossCarryingAmountDelta().atPresentationScale().amount())
            .figure("recognisedInterestDelta",
                preview.recognisedInterestDelta().atPresentationScale().amount())
            .figure("largestSingleContractMovement",
                preview.largestSingleContractMovement().atPresentationScale().amount())
            .figure("weightedAverageEirBefore", preview.weightedAverageEirBefore().periodic())
            .figure("weightedAverageEirAfter", preview.weightedAverageEirAfter().periodic())
            // Rounded to the rate scale rather than emitted raw. weightedAverageEirShiftBps() is a
            // subtraction under Precision.WORKING, so a nil shift arrives as 0 at scale 24 and
            // renders as twenty-four zeroes — a figure whose scale claims a precision the
            // subtraction did not have, on the one field a reader scans for "did anything move".
            .figure("weightedAverageEirShiftBps",
                Precision.round(preview.weightedAverageEirShiftBps(), Precision.RATE_SCALE))
            .bool("noMovement", preview.isNoMovement())
            .bool("coherent", preview.isCoherent())
            .strings("incoherences", preview.incoherences())
            .bool("concealsOffsettingMovement", preview.concealsOffsettingMovement())
            .count("impactPreviewsStored", store.previewsStoredFor(id))
            .obj("population", populationJson())
            .obj("impactPreviewGate", gateJson(decision))
            .str("audit", preview.describe())
            .str("detail", outcome.detail()));
    }

    // ---- POST /api/policy-versions/{id}/approve ---------------------------------------------

    /**
     * The checker's approval, and the {@code 409} this whole unit exists for.
     *
     * <p>Order of operations, and why it is this order. The impact-preview gate is asked first, but
     * the maker–checker gate is asked <em>regardless of its answer</em>, so that a caller who is
     * both unpreviewed and self-approving learns both facts from one call. Nothing is written
     * unless both permit: a refusal that half-applied would leave a version whose status says a
     * checker signed it and whose evidence says nobody did.
     *
     * <p><b>{@code PolicyVersion.advancedTo(APPROVED)} is not used and must not be.</b> That method
     * refuses {@code APPROVED} outright, on purpose — approval <em>adds</em> facts, the checker and
     * the date, and a status change alone has nothing to add them from. The approval therefore goes
     * through {@link MakerCheckerGate#approve} with an {@link ApprovalRecord}, which is the only
     * path that both records the signature and compares identities.
     *
     * <p><b>The submission step.</b> 06 § 5 exposes no "submit for approval" endpoint, so a version
     * drafted here sits in {@code DRAFT} and the life cycle has no {@code DRAFT → APPROVED} edge.
     * The maker's submission is therefore performed here, as its own gated transition, and reported
     * as its own record on the response — not folded into the approval. Folding them would produce
     * one audit line for two acts by two people, which is the whole thing a maker–checker control
     * is for.
     */
    private Answer approve(String id, FormBody body) {
        Optional<PolicyVersion> held = store.find(id);
        if (held.isEmpty()) {
            return unknownVersion(id);
        }
        PolicyVersion version = held.get();
        String checker = body.text("checker");
        String note = body.textOr("note", "");
        DraftFingerprint draft = store.currentDraftOf(id);

        ActivationDecision preview =
            gate.decideFromRegister(version, draft, store.previews(), BOOK_AS_AT);
        ApprovalRecord approval = approvalRecord(checker, note);

        // The maker's submission, where the version is still a draft. Asked through the gate
        // rather than assumed, so that a version in a status with no route to PENDING_APPROVAL —
        // an already-approved one, a superseded one — is refused by name rather than by an
        // exception from the next step.
        TransitionResult submission = version.status() == PolicyVersionStatus.DRAFT
            ? MakerCheckerGate.submitForApproval(version)
            : null;
        PolicyVersion pending = submission == null
            ? version
            : submission.isAllowed() ? submission.after() : version;
        TransitionResult signed = MakerCheckerGate.approve(pending, approval);

        boolean submissionStands = submission == null || submission.isAllowed();
        boolean bothGatesPermit = preview.permitted() && submissionStands && signed.isAllowed();
        // Written back only if the held version is still in the status the gates were asked about.
        // Both gates were consulted outside the store's lock — they have to be, since neither gate
        // belongs to the store — so a concurrent approval could have signed this version in the
        // meantime, and applying this answer on top would replace a signature already relied on.
        boolean stillUnchanged = !bothGatesPermit
            || store.replaceIfAt(signed.after(), version.status());
        boolean approved = bothGatesPermit && stillUnchanged;

        // 409 iff the impact-preview limb refused. 06 § 5 attaches the code to that condition and
        // to no other, so the code stays a reliable signal: run the preview.
        int status = preview.permitted() ? 200 : 409;
        InvariantResult pgOne = preview.asInvariantResult();
        Json.Obj out = Json.object()
            .str("specSection", SPEC_SECTION)
            .bool("approved", approved)
            .str("policyVersionId", id)
            .str("status", (approved ? signed.after() : version).status().name())
            .str("checkerOffered", approval.checker())
            .obj("impactPreviewGate", gateJson(preview))
            .obj("invariant", invariantJson(pgOne))
            .obj("submission", submission == null ? null : transitionJson(submission))
            .obj("makerChecker", transitionJson(signed))
            .count("impactPreviewsStored", store.previewsStoredFor(id));
        if (approved) {
            PolicyVersion after = signed.after();
            out.str("checker", after.checker())
                .str("approvedOn", after.approvedOn().toString())
                .bool("retrospective", after.isRetrospective())
                .str("detail", "approved: " + preview.detail())
                .str("audit", after.describe());
        } else if (!preview.permitted()) {
            // The remedy is named — but so is the maker-checker limb's own refusal, when there is
            // one, and not left buried in the sub-object. Otherwise POST .../approve on a version
            // that is already EFFECTIVE answers "run the impact preview first", which for a
            // retrospective version this endpoint will never certify is advice that cannot be
            // followed: the caller loops on it while the operative fact — the version is already in
            // force, so there is no approval left to give — sits one nesting level down.
            String alsoBlocked = submissionStands
                ? signed.isAllowed() ? "" : " Also refused by the maker-checker gate: "
                    + signed.describe()
                : " Also refused by the maker-checker gate: " + submission.describe();
            out.str("detail", "409 CONFLICT: approval refused because FR-210 requires a stored"
                    + " portfolio-level impact preview generated before the version can go"
                    + " effective, and " + preview.refusal() + " — run POST " + BASE + "/" + id
                    + "/impact-preview first. " + preview.detail() + alsoBlocked)
                .bool("refusalLooksLikeDiligence", preview.refusalLooksLikeDiligence());
        } else if (!stillUnchanged) {
            out.str("detail", "both gates permitted this approval, but policy version " + id
                + " moved out of " + version.status() + " while they were being asked, so the"
                + " answer was computed about a version that no longer exists; nothing was"
                + " written. Re-read the version and approve it again")
                .str("refusal", "CONCURRENTLY_MODIFIED");
        } else {
            out.str("detail", "the impact-preview gate permitted approval and the maker-checker"
                + " gate refused it: " + (submissionStands ? signed.describe()
                    : submission.describe()));
        }
        return new Answer(status, out);
    }

    // ---- rendering -------------------------------------------------------------------------

    private Json.Obj populationJson() {
        PortfolioImpactPreview.Position position = previews.position();
        Rate periodic = position.weightedAveragePeriodicEir();
        return Json.object()
            .count("contractsMeasured", position.contractsMeasured())
            .count("openingStatesOnFile", position.openingStatesOnFile())
            .bool("aggregatable", position.aggregatable())
            .str("notAggregatableBecause", position.unmeasurable())
            .figure("totalGrossCarryingAmount",
                position.totalGrossCarryingAmount().atPresentationScale().amount())
            // Effective annual, always. See Position: a weighted mean of per-period rates is only
            // a figure where every weight shares one compounding frequency.
            .figure("weightedAverageEirEffectiveAnnual", position.weightedAverageEir().periodic())
            // Published only where the population shares one frequency — null, not an
            // approximation, where it would be a mean over incompatible bases.
            .figure("weightedAveragePeriodicEir", periodic == null ? null : periodic.periodic())
            .strings("compoundingPeriodsPerYear", numbered(position.compoundingBases()))
            .str("asOf", position.asOf().toString())
            .str("limitation", "the population is the book's own holdings; contracts whose"
                + " opening state the master does not carry are counted and excluded from the"
                + " sums (FR-905), and this module reads the book it was constructed over rather"
                + " than one that moves under it");
    }

    private static Json.Obj gateJson(ActivationDecision decision) {
        return Json.object()
            .bool("permitted", decision.permitted())
            .str("refusal", decision.refusal() == null ? null : decision.refusal().name())
            .bool("looksLikeDiligence", decision.refusalLooksLikeDiligence())
            .str("detail", decision.detail());
    }

    private static Json.Obj transitionJson(TransitionResult result) {
        return Json.object()
            .bool("allowed", result.isAllowed())
            .str("target", result.target().name())
            .str("refusal", result.refusal() == null ? null : result.refusal().name())
            .str("detail", result.detail());
    }

    private static Json.Obj invariantJson(InvariantResult result) {
        return Json.object()
            .str("id", result.id().name().replace('_', '-'))
            .bool("satisfied", result.satisfied())
            .figure("deviation", result.deviation())
            .str("statement", result.id().statement())
            .str("detail", result.detail());
    }

    /** Compounding frequencies as strings, so a count is never mistaken for a figure. */
    private static List<String> numbered(List<Integer> values) {
        List<String> rendered = new ArrayList<>(values.size());
        for (Integer value : values) {
            rendered.add(Integer.toString(value));
        }
        return rendered;
    }

    private static List<String> names(java.util.Collection<PolicyVersionStatus> statuses) {
        List<String> named = new ArrayList<>(statuses.size());
        for (PolicyVersionStatus status : statuses) {
            named.add(status.name());
        }
        return named;
    }

    // ---- refusals that are not engine answers ----------------------------------------------

    /**
     * A version id this server does not hold: {@code 404}, not a refusal value.
     *
     * <p>The distinction matters. A refusal is the engine's answer <em>about</em> something; an
     * unknown id means there is nothing to answer about, and reporting it as a 200 would let a
     * caller with a typo read a body saying "not approved" and conclude their version had been
     * assessed and rejected.
     */
    private static Answer unknownVersion(String id) {
        return new Answer(404, Json.object()
            .str("error", "no such policy version")
            .str("policyVersionId", id)
            .str("detail", "no policy version " + id + " is held; draft it with POST " + BASE));
    }

    private static Answer notFound(String path) {
        return new Answer(404, Json.object()
            .str("error", "no route " + path)
            .strings("routes", List.of(
                "GET " + BASE,
                "POST " + BASE,
                "POST " + BASE + "/{id}/impact-preview",
                "POST " + BASE + "/{id}/approve")));
    }

    private static Answer methodNotAllowed(String method, String path, String allowed) {
        return new Answer(405, Json.object()
            .str("error", allowed + " only")
            .str("detail", method + " " + path + " is not a route; " + path + " accepts "
                + allowed));
    }

    // ---- parsing ---------------------------------------------------------------------------

    private static PolicyKind kindOf(String raw) {
        try {
            return PolicyKind.valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException notAKind) {
            throw new FormBody.BadRequest("'kind' must be one of "
                + Arrays.toString(PolicyKind.values()) + ", got '" + raw + "'. A policy version"
                + " governs one named kind of rule; there is no default and inventing one would"
                + " put a fee taxonomy change into force as a behavioural curve");
        }
    }

    private static LocalDate dateOf(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            throw new FormBody.BadRequest(
                "'effectiveFrom' must be an ISO-8601 date such as 2029-04-01, got '" + raw + "'");
        }
    }

    /**
     * The draft, or a 400 naming what is wrong with it.
     *
     * <p>{@code PolicyVersion}'s constructor is the authority on what a version may be — it
     * refuses a blank id, an unexplained version, an absent maker, and a maker who is their own
     * checker. Its complaint is about the request, not about the engine, so it is mapped to a 400
     * rather than allowed to surface as a 500 defect.
     */
    private static PolicyVersion drafted(
        String id, PolicyKind kind, String description, LocalDate effectiveFrom, String maker) {
        try {
            return new PolicyVersion(id, kind, description, effectiveFrom, maker, null, null,
                PolicyVersionStatus.DRAFT);
        } catch (IllegalArgumentException refused) {
            throw new FormBody.BadRequest(refused.getMessage());
        }
    }

    /**
     * The checker's sign-off, dated on the book's business date.
     *
     * <p>The date is not taken from the request. A caller-supplied approval date is the one input
     * that can invert this control without looking like it: 07 § 4.2 requires the preview to
     * precede approval, the gate enforces it by comparing the preview's instant against the
     * approval date, and a caller free to back-date the approval could make any preview look
     * late — or, on this book, make every preview look late by using a wall-clock date against a
     * book positioned in 2028.
     */
    private static ApprovalRecord approvalRecord(String checker, String note) {
        try {
            return new ApprovalRecord(checker, BOOK_BUSINESS_DATE, note);
        } catch (IllegalArgumentException refused) {
            throw new FormBody.BadRequest(refused.getMessage());
        }
    }

    // ---- socket ----------------------------------------------------------------------------

    private static String readBody(HttpExchange exchange) {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new FormBody.BadRequest(
                "the request body could not be read: " + unreadable.getMessage());
        }
    }

    private static void respond(HttpExchange exchange, Answer answer) throws IOException {
        byte[] bytes = answer.body().toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", JSON);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
