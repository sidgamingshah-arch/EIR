package com.crisil.eir.api.probe;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Seed;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Probe2Test {

    private EirServer server;
    private String base;

    @BeforeEach
    void up() throws IOException {
        server = new EirServer(0, new EirService(Seed.book()));
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void down() {
        server.stop();
    }

    private record R(int status, String body) {
    }

    private R post(String path, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = c.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private R get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        c.setRequestMethod("GET");
        return read(c);
    }

    private static R read(HttpURLConnection c) throws IOException {
        int s = c.getResponseCode();
        try (var in = s < 400 ? c.getInputStream() : c.getErrorStream()) {
            return new R(s, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String head(String body) {
        return body.length() < 600 ? body : body.substring(0, 600);
    }

    @Test
    void malformedClosedAtLeavesPeriodClosing() throws IOException {
        post("/api/runs", "periodId=202805&bookId=MAIN");
        R bad = post("/api/periods/202805/close", "closedAt=not-an-instant");
        System.out.println("F1 status=" + bad.status() + " " + head(bad.body()));
        System.out.println("F2 " + head(get("/api/periods/202805").body()));
        R run = post("/api/runs", "periodId=202805&bookId=MAIN");
        System.out.println("F3 run-after-stuck status=" + run.status()
            + " " + head(run.body()).substring(0, Math.min(200, head(run.body()).length())));
    }

    @Test
    void closeWithNoRun() throws IOException {
        R r = post("/api/periods/202805/close", "");
        System.out.println("G1 status=" + r.status() + " " + head(r.body()));
        System.out.println("G2 " + head(get("/api/periods/202805").body()));
    }

    @Test
    void weirdRunIds() throws IOException {
        R a = post("/api/runs", "periodId=202805&bookId=MAIN&runId=A/B");
        System.out.println("H1 status=" + a.status()
            + " " + head(a.body()).substring(0, Math.min(200, head(a.body()).length())));
        System.out.println("H2 " + head(get("/api/runs/A/B").body()));
        System.out.println("H3 " + head(get("/api/runs/A%2FB").body()));
        System.out.println("H4 " + head(get("/api/periods/202805").body()));
    }

    @Test
    void trailingSlash() throws IOException {
        post("/api/runs", "periodId=202805&bookId=MAIN");
        System.out.println("I1 " + head(get("/api/runs/RUN-202805-01/").body()));
        System.out.println("I2 " + head(get("/api/periods/").body()));
    }

    @Test
    void quotedRunIdInPath() throws IOException {
        R a = post("/api/runs", "periodId=202805&bookId=MAIN&runId="
            + java.net.URLEncoder.encode("R\"x\":1", StandardCharsets.UTF_8));
        System.out.println("J1 status=" + a.status()
            + " " + head(a.body()).substring(0, Math.min(300, head(a.body()).length())));
    }
}
