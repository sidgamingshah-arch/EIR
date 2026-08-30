package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirService;
import com.crisil.eir.api.http.ApiModule;
import com.crisil.eir.api.http.Routes;
import java.util.Objects;

/**
 * contract onboarding and versions, and POST events where the driver tag is REQUIRED and treatment is never inferred from the observation that the rate moved (FR-507)
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
 * <p>Specification: {@code docs/06-api-spec.md} 06 §§ 2, 3.
 */
public final class ContractsAndEventsModule implements ApiModule {{

    private final EirService service;

    public ContractsAndEventsModule(EirService service) {{
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
        return "06 §§ 2, 3";
    }}
}}
