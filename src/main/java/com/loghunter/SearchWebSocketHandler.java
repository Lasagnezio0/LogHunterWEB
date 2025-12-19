package com.loghunter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class SearchWebSocketHandler extends TextWebSocketHandler {

    private static final String CMD_RUST = "BackendRust/target/release/loghunter_rust";
    private static final Semaphore CONCURRENT_SEARCHES = new Semaphore(5); // Limite ricerche simultanee
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!CONCURRENT_SEARCHES.tryAcquire()) {
            try {
                session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"Server carico, attendi.\"}"));
                session.close();
            } catch (Exception ignored) {}
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            JsonNode params = mapper.readTree(message.getPayload());
            String keyword = params.path("keyword").asText("");
            
            // Regex base per evitare injection nel comando
            if (!keyword.matches("^[a-zA-Z0-9_\\-. @\\[\\]:()]+$")) {
                session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"Keyword non valida.\"}"));
                return;
            }

            List<String> cmd = new ArrayList<>(List.of(
                CMD_RUST,
                "--start", params.path("startDate").asText(),
                "--end", params.path("endDate").asText(),
                "--url", params.path("logUrl").asText(),
                "--word", keyword
            ));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!session.isOpen()) {
                        p.destroy();
                        break;
                    }
                    if (line.trim().startsWith("{")) session.sendMessage(new TextMessage(line));
                }
            }
            
            p.waitFor();

            if (session.isOpen()) {
                double sec = (System.currentTimeMillis() - startTime) / 1000.0;
                session.sendMessage(new TextMessage(String.format("{\"type\":\"end\", \"elapsed_seconds\": %.3f}", sec)));
            }

        } catch (Exception e) {
            try { if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"Backend error\"}")); } catch (Exception ignored) {}
        } finally {
            CONCURRENT_SEARCHES.release();
        }
    }
}