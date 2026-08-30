package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.Routes;
import java.util.Objects;

/**
 * FR-906's role model at the edge: who may run, who may close, and who may approve an acceptance
 *
 * <p><b>STUB.</b> Registers no route yet. This class exists so that the module list in
 * {@code ApiModules} is complete and compiling before any of these endpoints are built, which is
 * what lets them be built in parallel without every author editing one file.
 *
 * <p>A stub registering nothing is a legitimate state and the server neither notices nor cares: the
 * paths simply 404. That is better than a stub registering a route that answers "not implemented",
 * because a route that exists and refuses everything is indistinguishable from a route that is
 * broken, and an integrator cannot tell which.
 *
 * <p>Specification: {@code docs/06-api-spec.md} 07 § 4.
 */
public final class AccessControlModule implements ApiModule {{

    private final EirService service;

    public AccessControlModule(EirService service) {{
        this.service = Objects.requireNonNull(service, "service");
    }}

    /** The engine this module reads through. Protected from an unused-field warning while stubbed. */
    protected EirService service() {{
        return service;
    }}

    @Override
    public void register(Routes routes) {{
        Objects.requireNonNull(routes, "routes");
        // Nothing yet. See the class javadoc.
    }}

    @Override
    public String specSection() {{
        return "07 § 4";
    }}
}}
