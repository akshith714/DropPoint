package com.droppoint.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers plain-language questions about the business. The model may only call
 * the fixed functions in AssistantQueries; it never sees or writes SQL.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT = """
            You are the assistant for DropPoint, a gas cylinder delivery business in Hyderabad, India.
            Answer the owner's questions using ONLY the data returned by the tools. Call a tool before
            answering any question about agents, deliveries, customers or today's status.
            Be direct and specific: name agents and customers, give the actual numbers, and keep the
            answer under 120 words. When you spot a likely cause, say so and give the evidence.
            When several customers or agents match, report the top 3 to 5 with their numbers,
            not just the single worst one, then say which needs attention first.
            If the data does not answer the question, say exactly that. Never invent numbers,
            and copy dates exactly as the tool gives them.
            Punctuality has two separate meanings: late CHECK-INS (an agent arriving late for work,
            shown as late_checkins and avg_checkin_hour) and late DELIVERIES (an order arriving after
            its ETA, shown as late_deliveries). When the owner just says "late", report both and say
            which is the bigger problem.
            When asked whether a named customer is at risk of being lost, check quietCustomers as
            well as customerHistory before answering - how long they have been quiet against their
            usual refill gap is the evidence that matters.
            For a quiet customer with no late or failed deliveries, do not say inactivity caused
            the churn. Say the likely reasons are moving away, switching supplier, or a piped-gas
            connection, and recommend a call to confirm.
            Write plain sentences and plain dashes for lists. No markdown, no asterisks, no headings.
            Money is in rupees. Times are India time.
            """;

    public record Answer(String answer, List<String> toolsUsed, long millis) { }

    private static final TypeReference<Map<String, Object>> MESSAGE_SHAPE = new TypeReference<>() { };

    private final AssistantQueries queries;
    // India time, or every date handed to the model lands a day early: a local
    // midnight date serialised as UTC becomes the previous evening.
    private final ObjectMapper json = JsonMapper.builder()
            .defaultTimeZone(TimeZone.getTimeZone("Asia/Kolkata"))
            .build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String model;

    public AssistantService(AssistantQueries queries,
                            @Value("${droppoint.groq.api-key:}") String apiKey,
                            @Value("${droppoint.groq.model:openai/gpt-oss-120b}") String model) {
        this.queries = queries;
        this.apiKey = apiKey;
        this.model = model;
    }

    public Answer ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("A question is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is not set");
        }
        long started = System.currentTimeMillis();
        List<String> used = new ArrayList<>();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", question));

        try {
            // Up to three rounds: the model may chain a couple of tool calls.
            for (int round = 0; round < 3; round++) {
                JsonNode message = callGroq(messages);
                JsonNode toolCalls = message.get("tool_calls");

                if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                    String text = message.path("content").asText("")
                            .replace('\u00A0', ' ')      // non-breaking space
                            .replace('\u202F', ' ')      // narrow no-break space
                            .replace('\u2014', '-')      // em dash
                            .replace('\u2013', '-')      // en dash
                            .replace('\u2019', '\'');    // curly apostrophe
                    return new Answer(text, used, System.currentTimeMillis() - started);
                }

                messages.add(json.convertValue(message, MESSAGE_SHAPE));

                for (JsonNode call : toolCalls) {
                    String name = call.path("function").path("name").asText();
                    JsonNode args = json.readTree(call.path("function").path("arguments").asText("{}"));
                    used.add(name);
                    Object result = run(name, args);

                    Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("role", "tool");
                    reply.put("tool_call_id", call.path("id").asText());
                    reply.put("name", name);
                    reply.put("content", json.writeValueAsString(result));
                    messages.add(reply);
                }
            }
            return new Answer("I could not finish that question - too many steps.", used,
                    System.currentTimeMillis() - started);

        } catch (IllegalStateException e) {
            // Already a message worth showing the owner, such as the rate-limit one.
            log.error("Assistant failed", e);
            throw e;
        } catch (Exception e) {
            log.error("Assistant failed", e);
            throw new IllegalStateException("The assistant is unavailable: " + e.getMessage());
        }
    }

    private Object run(String name, JsonNode args) {
        return switch (name) {
            case "agentPunctuality" -> queries.agentPunctuality(args.path("days").asInt(7));
            case "deliveryStats" -> queries.deliveryStats(args.path("days").asInt(7));
            case "lateBreakdown" -> queries.lateBreakdown(args.path("days").asInt(30));
            case "quietCustomers" -> queries.quietCustomers(args.path("minDaysQuiet").asInt(30));
            case "customerHistory" -> queries.customerHistory(args.path("name").asText(""));
            case "todaySnapshot" -> queries.todaySnapshot();
            default -> Map.of("error", "Unknown function " + name);
        };
    }

    private JsonNode callGroq(List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "tools", TOOLS,
                "tool_choice", "auto",
                "temperature", 0.2,
                "max_tokens", 900);

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(URL))
                        .timeout(Duration.ofSeconds(45))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 429) {
            // The free tier allows 8000 tokens a minute, which a burst of questions can exhaust.
            throw new IllegalStateException("Too many questions at once. Wait a few seconds and ask again.");
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Groq HTTP " + res.statusCode() + ": " + res.body());
        }
        return json.readTree(res.body()).path("choices").get(0).path("message");
    }

    /** The six functions the model is allowed to call. */
    private static final List<Map<String, Object>> TOOLS = List.of(
            tool("agentPunctuality",
                 "Per-agent delivery punctuality and check-in times over the last N days. Use for questions about which agent is late, slow or best.",
                 Map.of("days", intParam("How many days back, default 7"))),
            tool("deliveryStats",
                 "Headline totals over the last N days: delivered, failed, open, late and flagged deliveries.",
                 Map.of("days", intParam("How many days back, default 7"))),
            tool("lateBreakdown",
                 "Where late deliveries cluster, grouped by hour of day and by building type. Use for questions about WHY deliveries are late.",
                 Map.of("days", intParam("How many days back, default 30"))),
            tool("quietCustomers",
                 "Customers who have not ordered recently, with their usual refill gap, late deliveries and failures. Use for churn or lost-customer questions.",
                 Map.of("minDaysQuiet", intParam("Minimum days since last order, default 30"))),
            tool("customerHistory",
                 "One customer's details and last 15 orders, found by partial name. Use when the owner names a customer.",
                 Map.of("name", Map.of("type", "string", "description", "Part of the customer name"))),
            tool("todaySnapshot",
                 "Live status today: agents on duty, orders waiting, in progress, delivered, failed and flagged.",
                 Map.of()));

    private static Map<String, Object> intParam(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        return Map.of("type", "function", "function", Map.of(
                "name", name,
                "description", description,
                "parameters", Map.of("type", "object", "properties", properties)));
    }
}
