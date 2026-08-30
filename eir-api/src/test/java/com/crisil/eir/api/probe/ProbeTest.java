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

class ProbeTest {

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
        return body.length() < 800 ? body : body.substring(0, 800);
    }

    @Test
    void runIdCollision() throws IOException {
        R first = post("/api/runs", "periodId=202805&bookId=MAIN&runId=RUN-202805-02");
        System.out.println("A1 status=" + first.status() + " " + head(first.body()));
        R second = post("/api/runs", "periodId=202805&bookId=MAIN");
        System.out.println("A2 status=" + second.status() + " " + head(second.body()));
    }

    @Test
    void closeWithEarlyClosedAt() throws IOException {
        post("/api/runs", "periodId=202805&bookId=MAIN");
        R r = post("/api/periods/202805/close", "closedAt=2028-06-01T00:00:00Z");
        System.out.println("B status=" + r.status() + " " + head(r.body()));
        R p = get("/api/periods/202805");
        System.out.println("B period " + head(p.body()));
    }

    @Test
    void closeHappyPathTwice() throws IOException {
        post("/api/runs", "periodId=202805&bookId=MAIN");
        post("/api/repair", "");
        post("/api/runs", "periodId=202805&bookId=MAIN");
        post("/api/post", "");
        R r = post("/api/periods/202805/close", "");
        System.out.println("C1 status=" + r.status() + " " + head(r.body()));
        R again = post("/api/periods/202805/close", "");
        System.out.println("C2 status=" + again.status() + " " + head(again.body()));
        R p = get("/api/periods/202805");
        System.out.println("C3 " + head(p.body()));
        R run = post("/api/runs", "periodId=202805&bookId=MAIN");
        System.out.println("C4 status=" + run.status() + " " + head(run.body()));
    }

    @Test
    void oddPaths() throws IOException {
        System.out.println("D1 " + get("/api/periods/last-month/close"));
        System.out.println("D2 " + get("/api/periods/202805/nonsense"));
        System.out.println("D3 " + get("/api/runsomething"));
        System.out.println("D4 " + get("/api/runs"));
        System.out.println("D5 " + head(get("/api/periods").body()));
    }

    @Test
    void replayNonLatest() throws IOException {
        post("/api/runs", "periodId=202805&bookId=MAIN");
        post("/api/runs", "periodId=202805&bookId=MAIN");
        R r = post("/api/runs/RUN-202805-01/replay", "");
        System.out.println("E1 status=" + r.status() + " " + head(r.body()));
        R ok = post("/api/runs/RUN-202805-02/replay", "");
        System.out.println("E2 status=" + ok.status() + " " + head(ok.body()));
        post("/api/run", "runId=SNEAKY");
        R sneaky = post("/api/runs/RUN-202805-02/replay", "");
        System.out.println("E3 status=" + sneaky.status() + " " + head(sneaky.body()));
    }
}
