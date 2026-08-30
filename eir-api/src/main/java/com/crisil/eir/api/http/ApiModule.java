package com.crisil.eir.api.http;

/**
 * One slice of the HTTP surface, registered as a unit.
 *
 * <p><b>The contract is narrow on purpose.</b> A module receives {@link Routes} and nothing else;
 * whatever engine collaborators it needs are constructor arguments supplied where it is assembled.
 * So a module is testable without a server, and the server knows nothing about what any module
 * does.
 *
 * <p><b>What a module must not do.</b> Decide anything the engine decides. A handler's job is to
 * read a request, call one entry point, and render what came back — including every refusal. The
 * moment a handler starts choosing which refusals to report or mapping several onto one message, the
 * distinction this whole engine is built on is gone at the edge.
 *
 * <p>Registered in {@code ApiModules}, which is the one place the list lives.
 */
public interface ApiModule {

    /**
     * Registers this module's endpoints.
     *
     * <p>Called once, during server construction. A module that registers nothing is a legitimate
     * state — it is what a stub looks like before its unit is built — and the server neither
     * notices nor cares.
     */
    void register(Routes routes);

    /**
     * The section of {@code docs/06-api-spec.md} this module implements, for the route listing.
     *
     * <p>Named rather than derived, so that a module which drifts from its specification section is
     * visibly wrong instead of quietly renamed.
     */
    String specSection();
}
