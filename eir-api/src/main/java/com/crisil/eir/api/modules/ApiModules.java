package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import java.util.List;
import java.util.Objects;

/**
 * The module list: the one place the HTTP surface is enumerated.
 *
 * <p><b>Why a fixed list and not a service loader or a classpath scan.</b> A scan makes the surface
 * a function of what happens to be on the classpath, and then a module dropped by a packaging
 * mistake produces a server that starts cleanly and 404s an endpoint an auditor was told exists.
 * Declared, the omission is a compile error.
 *
 * <p>The modules, and the section of {@code docs/06-api-spec.md} each implements:
 *
 * <ul>
 *   <li>{@link TraceModule} — GET /api/contracts/{id}/trace — FR-808, the audit endpoint: resolves any published figure to its inputs</li>
 *   <li>{@link RunsAndPeriodsModule} — POST /api/runs, GET /api/runs/{id}, POST /api/runs/{id}/replay, GET /api/periods, POST /api/periods/{id}/close — the close returns 409 with the failing gates enumerated</li>
 *   <li>{@link PolicyVersionsModule} — policy-version lifecycle: draft, the MANDATORY impact preview, and approve — which must 409 without a stored preview (FR-210)</li>
 *   <li>{@link RuleSetsModule} — fee rule sets and routing tables: draft and approve, so the routing table changes without a code deploy (ADR-0006)</li>
 *   <li>{@link ExceptionsModule} — the exception work queue: list by status and category, resolve with a mandatory note, accept with approval under four eyes</li>
 *   <li>{@link MovementReportModule} — GET /api/reports/movement — FR-805, and the published columns must SUM</li>
 *   <li>{@link ReconciliationReportsModule} — the four reconciliation reports: sub-ledger to GL, contractual leg to CBS, the Stage 3 four-way, and the pre-/post-floor duality</li>
 *   <li>{@link ApproximationsModule} — GET /api/reports/approximations — FR-809, the incidence of every shortcut in force — and the Ind AS 107 disclosure extract</li>
 *   <li>{@link ContractsAndEventsModule} — contract onboarding and versions, and POST events where the driver tag is REQUIRED and treatment is never inferred from the observation that the rate moved (FR-507)</li>
 *   <li>{@link TransitionModule} — the five transition endpoints: the ACPIR 19 day-1 fair-value run, per-contract fair value with the paragraph 19 rebuttal reference, legacy cohorts, migration, and coverage against the ACPIR 21 and 50 deadlines tracked SEPARATELY</li>
 *   <li>{@link AccessControlModule} — FR-906's role model at the edge: who may run, who may close, and who may approve an acceptance</li>
 * </ul>
 *
 * <p>The console's own endpoints — the book, the run, posting, the close, replay, repair and
 * acceptance — are registered directly by {@code EirServer} rather than through a module. They
 * predate this seam and moving them would be a change with no behavioural point.
 */
public final class ApiModules {

    private ApiModules() {
    }

    /** Every module, in specification order. */
    public static List<ApiModule> all(EirService service) {
        Objects.requireNonNull(service, "service");
        return List.of(
            new TraceModule(service),
            new RunsAndPeriodsModule(service),
            new PolicyVersionsModule(service),
            new RuleSetsModule(service),
            new ExceptionsModule(service),
            new MovementReportModule(service),
            new ReconciliationReportsModule(service),
            new ApproximationsModule(service),
            new ContractsAndEventsModule(service),
            new TransitionModule(service),
            new AccessControlModule(service));
    }
}
