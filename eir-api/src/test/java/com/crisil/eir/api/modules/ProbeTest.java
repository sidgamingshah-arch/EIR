package com.crisil.eir.api.modules;

import com.crisil.eir.api.EirServer;
import com.crisil.eir.api.EirService;
import com.crisil.eir.api.store.Seed;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProbeTest {

    private static String raw(int port, String target, String identity) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(target).append(" HTTP/1.1\r\nHost: localhost\r\n");
            if (identity != null) {
                request.append(AccessControlModule.IDENTITY_HEADER).append(": ")
                    .append(identity).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void probe() throws IOException {
        EirServer server = new EirServer(0, new EirService(Seed.book()));
        server.start();
        int port = server.port();
        try {
            String[][] probes = {
                {"malformed-pct", "/api/access/decisions?action=%ZZ", "reader.only"},
                {"enum-known", "/api/access/decisions?action=CLOSE_PERIOD", "reader.only"},
                {"enum-unknown", "/api/access/decisions?action=CLOSE_PERIOD", "guessed.name"},
                {"noaction-anon", "/api/access/decisions", null},
                {"prefix-match", "/api/access/whoami/extra/path", "reader.only"},
                {"dup-action", "/api/access/decisions?action=READ_FIGURES&action=CLOSE_PERIOD",
                    "reader.only"},
            };
            for (String[] p : probes) {
                String r = raw(port, p[1], p[2]);
                System.out.println("PROBE " + p[0] + " => "
                    + r.substring(0, Math.min(900, r.length())).replace("\r\n", " | "));
                System.out.println();
            }
        } finally {
            server.stop();
        }
    }
}
