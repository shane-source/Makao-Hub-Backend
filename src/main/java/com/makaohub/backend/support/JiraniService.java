package com.makaohub.backend.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks the Grok model (xAI, OpenAI-style chat API) to answer a help question as Jirani. The key stays on the server.
 * Only the user's role is shared with the model, never their name, contact details or payments.
 */
@Service
public class JiraniService {

    private static final Logger LOG = LoggerFactory.getLogger(JiraniService.class);

    private static final int MAX_TURNS = 12;
    private static final int MAX_CHARS = 1000;
    private static final int PER_MINUTE = 8;
    private static final int PER_DAY = 60;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Answer(List<Choice> choices) {
    }

    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public JiraniService(
            @Value("${makao.jirani.api-key:}") String apiKey,
            @Value("${makao.jirani.model:grok-4.7}") String model,
            @Value("${makao.jirani.base-url:https://api.x.ai}") String baseUrl
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public String reply(String userId, String role, List<SupportController.Turn> turns) {
        if (apiKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Jirani isn't available yet. Please contact support.");
        }
        List<Map<String, String>> messages = clean(turns);
        if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).get("role"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask Jirani a question to get started.");
        }
        limit(userId);

        List<Map<String, String>> conversation = new ArrayList<>();
        conversation.add(Map.of("role", "system",
                "content", JiraniPrompt.TEXT + "\n\nThe person you are helping is signed in as a " + role + "."));
        conversation.addAll(messages);
        Map<String, Object> body = Map.of(
                "model", model,
                "max_completion_tokens", 1200,
                "messages", conversation
        );

        try {
            Answer answer = client.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(Answer.class);

            String text = "";
            if (answer != null && answer.choices() != null && !answer.choices().isEmpty()
                    && answer.choices().get(0).message() != null
                    && answer.choices().get(0).message().content() != null) {
                text = answer.choices().get(0).message().content().trim();
            }
            if (text.isEmpty()) throw new IllegalStateException("empty reply");
            return text.length() > 2000 ? text.substring(0, 2000) : text;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // The provider's message can contain request details, so only the type is logged.
            LOG.warn("JIRANI_FAILED type={}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Jirani is busy right now. Please try again, or contact support.");
        }
    }

    /** Keeps the last few turns, trims each one, and makes sure roles alternate, as the model requires. */
    private static List<Map<String, String>> clean(List<SupportController.Turn> turns) {
        List<Map<String, String>> out = new ArrayList<>();
        if (turns == null) return out;
        int from = Math.max(0, turns.size() - MAX_TURNS);
        String last = "";
        for (SupportController.Turn turn : turns.subList(from, turns.size())) {
            if (turn == null || turn.content() == null) continue;
            String role = "assistant".equals(turn.role()) ? "assistant" : "user";
            String text = turn.content().strip();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
            if (role.equals(last)) {
                // Two in a row from the same side: keep the newest.
                out.set(out.size() - 1, Map.of("role", role, "content", text));
            } else {
                out.add(Map.of("role", role, "content", text));
            }
            last = role;
        }
        // The conversation has to start with the person.
        while (!out.isEmpty() && "assistant".equals(out.get(0).get("role"))) out.remove(0);
        return out;
    }

    private void limit(String userId) {
        Deque<Instant> times = recent.computeIfAbsent(userId, key -> new ArrayDeque<>());
        Instant now = Instant.now();
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(Duration.ofDays(1)))) times.pollFirst();
            long lastMinute = times.stream().filter(t -> t.isAfter(now.minusSeconds(60))).count();
            if (lastMinute >= PER_MINUTE) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "You're sending messages quickly. Please wait a moment.");
            }
            if (times.size() >= PER_DAY) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "You've reached today's limit for Jirani. Please contact support or try again tomorrow.");
            }
            times.addLast(now);
        }
    }
}
