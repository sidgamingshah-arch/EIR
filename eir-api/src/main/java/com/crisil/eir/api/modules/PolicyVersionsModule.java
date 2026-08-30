package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.Routes;
import com.crisil.eir.api.modules.policy.PolicyVersionsSurface;
import com.crisil.eir.api.store.Seed;
import java.util.Objects;

/**
 * policy-version lifecycle: draft, the MANDATORY impact preview, and approve — which must 409
 * without a stored preview (FR-210).
 *
 * <p>The endpoints themselves are {@link PolicyVersionsSurface}. This class is the wiring, and it
 * is now four lines, because {@link Routes#route} does what 06 § 5 needs.
 *
 * <h2>One route for the subtree, and why that is the whole of it</h2>
 *
 * <p>06 § 5 asks for three things that {@link Routes#get} and {@link Routes#post} cannot express:
 * a {@code 409} on {@code POST .../{id}/approve}, a path parameter on a POST, and {@code GET} and
 * {@code POST} on the same {@code /policy-versions} path. {@code route} settles all three at once.
 * It registers a <em>subtree</em>, so this module receives every request under
 * {@link PolicyVersionsSurface#BASE} regardless of verb — which is what makes both verbs on one path
 * possible and what lets the surface read {@code {id}} out of the remaining segments — and the
 * handler returns a {@link Routes.Answer} carrying the status it wants.
 *
 * <p><b>An earlier version of this class recovered the JDK {@code HttpServer} by walking the fields
 * of the {@code Routes} instance and registered a context on it directly.</b> That is gone, with
 * both of the construction-time guards it needed: one for a seam hiding no server at all, and one
 * for a seam that wrote to a different server than the walk had found — because reachability is not
 * identity, and binding this surface to the wrong server would have left {@code POST
 * .../{id}/approve} answering 404, which is indistinguishable from an approval gate that is present
 * and permissive. None of that reasoning has to be maintained now. The status code and the path are
 * the seam's business again, and this module asks for them through the interface.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 06 § 5.
 */
public final class PolicyVersionsModule implements ApiModule {

    private final EirService service;

    public PolicyVersionsModule(EirService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * The engine this module was handed.
     *
     * <p><b>Unused by the policy-version surface, and that is a limitation worth naming rather than
     * hiding.</b> The impact preview needs the live portfolio, and the accessor this module would
     * read it through — {@code EirService.lastRunFor(periodId)} — hands out the population of the
     * last <em>completed run</em>. A policy version can be drafted, previewed and approved on a
     * server where no run has happened yet, so the preview cannot depend on one having. It therefore
     * measures a book positioned identically to the server's ({@code Seed.book()}) and says so on
     * its own response; a contract onboarded through {@code POST /api/onboard} during this process
     * is consequently not in the population a preview reports. See the module's report for the one
     * field that would close it.
     */
    protected EirService service() {
        return service;
    }

    @Override
    public void register(Routes routes) {
        Objects.requireNonNull(routes, "routes");
        PolicyVersionsSurface surface = PolicyVersionsSurface.over(Seed.book());
        routes.route(PolicyVersionsSurface.BASE, surface::handle);
    }

    @Override
    public String specSection() {
        return "06 § 5";
    }
}
