package com.crisil.eir.api.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * An {@link HttpExchange} that carries a method and a raw path and nothing else, for testing a
 * {@link Routes.PathHandler} without a socket.
 *
 * <p><b>Why this exists, and what it is deliberately bad at.</b> A subtree handler registered with
 * {@link Routes#route} dispatches on the path it was reached by — that is the whole point of the
 * primitive, and the reason {@link Routes#post} could not serve
 * {@code /api/exceptions/&#123;id&#125;/&#123;action&#125;}. A registration-recording {@code Routes}
 * can hand a handler back to a test, but the handler then needs an exchange to read the path out
 * of. Constructing a real one means a real server, which several tests here already do and should
 * keep doing.
 *
 * <p>So this stand-in is for the dispatch arithmetic only: which suffix shapes a module claims,
 * which it declines, and what it does with the id it decodes. Every method that would carry a
 * request or a response throws {@link UnsupportedOperationException} rather than returning an empty
 * value — a handler that reaches for a header or writes a body must fail loudly here and be tested
 * against the running server instead, because a stand-in that quietly answers "no headers" is how
 * a test comes to pass against behaviour no client will ever see.
 */
public final class PathOnlyExchange extends HttpExchange {

    private final String method;
    private final URI uri;

    private PathOnlyExchange(String method, String rawPath) {
        this.method = method;
        this.uri = URI.create(rawPath);
    }

    /** A POST at {@code rawPath}. The path is taken raw: {@code %2F} stays encoded. */
    public static PathOnlyExchange post(String rawPath) {
        return new PathOnlyExchange("POST", rawPath);
    }

    /** A GET at {@code rawPath}, for asserting that a POST-only subtree declines it. */
    public static PathOnlyExchange get(String rawPath) {
        return new PathOnlyExchange("GET", rawPath);
    }

    /** A request under any other verb, for the same reason. */
    public static PathOnlyExchange method(String method, String rawPath) {
        return new PathOnlyExchange(method, rawPath);
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public Headers getRequestHeaders() {
        throw unsupported("getRequestHeaders");
    }

    @Override
    public Headers getResponseHeaders() {
        throw unsupported("getResponseHeaders");
    }

    @Override
    public HttpContext getHttpContext() {
        throw unsupported("getHttpContext");
    }

    @Override
    public void close() {
        // Nothing was opened. Silent rather than throwing, because a try-with-resources around a
        // handler call would otherwise fail on the way out and mask the assertion.
    }

    @Override
    public InputStream getRequestBody() {
        throw unsupported("getRequestBody");
    }

    @Override
    public OutputStream getResponseBody() {
        throw unsupported("getResponseBody");
    }

    @Override
    public void sendResponseHeaders(int code, long length) {
        throw unsupported("sendResponseHeaders");
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        throw unsupported("getRemoteAddress");
    }

    @Override
    public int getResponseCode() {
        throw unsupported("getResponseCode");
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        throw unsupported("getLocalAddress");
    }

    @Override
    public String getProtocol() {
        throw unsupported("getProtocol");
    }

    @Override
    public Object getAttribute(String name) {
        throw unsupported("getAttribute");
    }

    @Override
    public void setAttribute(String name, Object value) {
        throw unsupported("setAttribute");
    }

    @Override
    public void setStreams(InputStream input, OutputStream output) {
        throw unsupported("setStreams");
    }

    @Override
    public HttpPrincipal getPrincipal() {
        throw unsupported("getPrincipal");
    }

    private static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(
            "PathOnlyExchange carries a method and a raw path only; " + what + " means this handler"
                + " needs a real request and belongs in a test against the running server");
    }
}
