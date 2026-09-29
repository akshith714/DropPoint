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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers plain-language questions about the business, and owns the fixed set of
 * read-only queries that answer them. The model picks which query to call by
 * name; it never sees the SQL and never writes any.
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
            "Late" means a late CHECK-IN: an agent arriving for work at 9 am or after, shown as
            late_checkins and avg_checkin_hour. Delivery problems show up as FAILED deliveries,
            with a failure_reason; failureBreakdown shows where and why they cluster.
            When asked whether a named customer is at risk of being lost, check quietCustomers as
            well as customerHistory before answering - how long they have been quiet against their
            usual refill gap is the evidence that matters.
            For a quiet customer with no failed deliveries, do not say inactivity caused
            the churn. Say the likely reasons are moving away, switching supplier, or a piped-gas
            connection, and recommend a call to confirm.
            Write plain sentences and plain dashes for lists. No markdown, no asterisks, no headings.
            Money is in rupees. Times are India time.
            """;

    public record Answer(String answer, List<String> toolsUsed, long millis) { }

    private static final TypeReference<Map<String, Object>> MESSAGE_SHAPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    // India time, or every date handed to the model lands a day early: a local
    // midnight date serialised as UTC becomes the previous evening.
    private final ObjectMapper json = JsonMapper.builder()
            .defaultTimeZone(TimeZone.getTimeZone("Asia/Kolkata"))
            .build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String model;

    public AssistantService(JdbcTemplate jdbc,
                            @Value("${droppoint.groq.api-key:}") String apiKey,
                            @Value("${droppoint.groq.model:openai/gpt-oss-120b}") String model) {
        this.jdbc = jdbc;
        this.apiKey = apiKey;
        this.model = model;
    }

    public Answer ask(String question) {
        return ask(question, "MANAGER", null);
    }

    public Answer ask(String question, String role, Long agentId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("A question is required");
        }
        
        String userRole = (role != null && !role.isBlank()) ? role.toUpperCase() : "MANAGER";
        if ("AGENT".equals(userRole)) {
            String qLower = question.toLowerCase();
            if (qLower.contains("other agent") || qLower.contains("all agents") || qLower.contains("salary") 
                || qLower.contains("profit") || qLower.contains("user list") || qLower.contains("performance of")) {
                return new Answer("Access Restricted: Delivery Agents can only inquire about their own assigned deliveries, customer addresses, or daily targets today.", List.of(), 0);
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY is not set");
        }
        long started = System.currentTimeMillis();
        List<String> used = new ArrayList<>();

        String systemPromptToUse = SYSTEM_PROMPT;
        if ("AGENT".equals(userRole)) {
            systemPromptToUse += "\nPRIVACY RULE FOR AGENT ROLE: You are speaking to a Delivery Agent. Only provide info about their own assigned stops and delivery addresses today. Do not reveal overall agency financials or other agents' data.";
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPromptToUse));
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
                            .replace('\u2011', '-')      // non-breaking hyphen
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
            case "agentPunctuality" -> agentPunctuality(args.path("days").asInt(7));
            case "deliveryStats" -> deliveryStats(args.path("days").asInt(7));
            case "failureBreakdown" -> failureBreakdown(args.path("days").asInt(30));
            case "quietCustomers" -> quietCustomers(args.path("minDaysQuiet").asInt(30));
            case "customerHistory" -> customerHistory(args.path("name").asText(""));
            case "todaySnapshot" -> todaySnapshot();
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
                 "Per-agent deliveries, failures and check-in times over the last N days. Use for questions about which agent is late, slow or best.",
                 Map.of("days", intParam("How many days back, default 7"))),
            tool("deliveryStats",
                 "Headline totals over the last N days: delivered, failed and still open orders, and cylinders delivered.",
                 Map.of("days", intParam("How many days back, default 7"))),
            tool("failureBreakdown",
                 "Where failed deliveries cluster, grouped by reason, by area and by building type. Use for questions about WHY deliveries fail.",
                 Map.of("days", intParam("How many days back, default 30"))),
            tool("quietCustomers",
                 "Customers who have not ordered recently, with their usual refill gap and failed deliveries. Use for churn or lost-customer questions.",
                 Map.of("minDaysQuiet", intParam("Minimum days since last order, default 30"))),
            tool("customerHistory",
                 "One customer's details and last 15 orders, found by partial name. Use when the owner names a customer.",
                 Map.of("name", Map.of("type", "string", "description", "Part of the customer name"))),
            tool("todaySnapshot",
                 "Live status today: agents on duty, orders waiting, in progress, delivered and failed.",
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

    // ---- the only data the assistant can reach ----

    /** Per-agent deliveries, failures and check-in times over the last N days. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> agentPunctuality(int days) {
        return jdbc.queryForList("""
                SELECT u.full_name AS agent,
                       count(o.id) FILTER (WHERE o.status = 'DELIVERED') AS deliveries,
                       count(o.id) FILTER (WHERE o.status = 'FAILED') AS failed_deliveries,
                       count(o.id) FILTER (WHERE o.status = 'ASSIGNED') AS still_assigned,
                       (SELECT round(CAST(avg(EXTRACT(HOUR FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata')
                                        + EXTRACT(MINUTE FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata') / 60.0) AS numeric), 2)
                          FROM attendance a
                         WHERE a.agent_id = u.id
                           AND a.work_date >= CURRENT_DATE - CAST(? AS INTEGER)) AS avg_checkin_hour,
                       (SELECT count(*) FROM attendance a
                         WHERE a.agent_id = u.id
                           AND a.work_date >= CURRENT_DATE - CAST(? AS INTEGER)
                           AND EXTRACT(HOUR FROM a.check_in_at AT TIME ZONE 'Asia/Kolkata') >= 9) AS late_checkins
                  FROM app_user u
                  LEFT JOIN orders o ON o.assigned_agent_id = u.id
                       AND o.assigned_at >= now() - make_interval(days => ?)
                 WHERE u.role = 'AGENT' AND u.active
                 GROUP BY u.id, u.full_name
                 ORDER BY late_checkins DESC, failed_deliveries DESC
                """, days, days, days);
    }

    /** Headline delivery numbers over the last N days. */
    @Transactional(readOnly = true)
    public Map<String, Object> deliveryStats(int days) {
        return jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered,
                       count(*) FILTER (WHERE o.status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE o.status IN ('BOOKED','ASSIGNED')) AS still_open,
                       COALESCE(sum(o.cylinder_count) FILTER (WHERE o.status = 'DELIVERED'), 0) AS cylinders_delivered
                  FROM orders o
                 WHERE o.booked_at >= now() - make_interval(days => ?)
                """, days);
    }

    /** Where failed deliveries cluster: by reason, by area and by building type. */
    @Transactional(readOnly = true)
    public Map<String, Object> failureBreakdown(int days) {
        List<Map<String, Object>> byReason = jdbc.queryForList("""
                SELECT COALESCE(o.failure_reason, 'UNKNOWN') AS reason, count(*) AS failures
                  FROM orders o
                 WHERE o.status = 'FAILED' AND o.delivered_at >= now() - make_interval(days => ?)
                 GROUP BY 1 ORDER BY 2 DESC
                """, days);

        List<Map<String, Object>> byArea = jdbc.queryForList("""
                SELECT a.name AS area,
                       count(*) AS attempted,
                       count(*) FILTER (WHERE o.status = 'FAILED') AS failed
                  FROM orders o JOIN area a ON a.id = o.area_id
                 WHERE o.status IN ('DELIVERED','FAILED')
                   AND o.delivered_at >= now() - make_interval(days => ?)
                 GROUP BY a.name ORDER BY failed DESC
                """, days);

        List<Map<String, Object>> byBuilding = jdbc.queryForList("""
                SELECT c.building_type, c.customer_type,
                       count(*) AS attempted,
                       count(*) FILTER (WHERE o.status = 'FAILED') AS failed
                  FROM orders o JOIN customer c ON c.id = o.customer_id
                 WHERE o.status IN ('DELIVERED','FAILED')
                   AND o.delivered_at >= now() - make_interval(days => ?)
                 GROUP BY 1, 2 ORDER BY failed DESC
                """, days);

        return Map.of("by_reason", byReason, "by_area", byArea, "by_building_type", byBuilding);
    }

    /** Customers who have gone quiet compared with their own usual refill gap. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> quietCustomers(int minDaysQuiet) {
        return jdbc.queryForList("""
                WITH gaps AS (
                    SELECT o.customer_id,
                           CAST(o.booked_at AS DATE) - lag(CAST(o.booked_at AS DATE))
                                OVER (PARTITION BY o.customer_id ORDER BY o.booked_at) AS gap_days
                      FROM orders o
                     WHERE o.status IN ('DELIVERED','FAILED')
                ),
                profile AS (
                    SELECT customer_id, round(avg(gap_days)) AS usual_gap_days
                      FROM gaps WHERE gap_days IS NOT NULL
                     GROUP BY customer_id
                )
                SELECT c.id AS customer_id, c.name, c.customer_type, c.phone, c.active,
                       CAST(max(o.booked_at) AS DATE) AS last_order_date,
                       CURRENT_DATE - CAST(max(o.booked_at) AS DATE) AS days_quiet,
                       pr.usual_gap_days,
                       count(o.id) AS total_orders,
                       count(*) FILTER (WHERE o.status = 'FAILED') AS failed_deliveries
                  FROM customer c
                  JOIN orders o ON o.customer_id = c.id
                  LEFT JOIN profile pr ON pr.customer_id = c.id
                 GROUP BY c.id, c.name, c.customer_type, c.phone, c.active, pr.usual_gap_days
                HAVING CURRENT_DATE - CAST(max(o.booked_at) AS DATE) >= ?
                 ORDER BY days_quiet DESC
                 LIMIT 25
                """, minDaysQuiet);
    }

    /** Everything about one customer, matched loosely by name. */
    @Transactional(readOnly = true)
    public Map<String, Object> customerHistory(String nameFragment) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT id, name, customer_type, phone, address_line, building_type,
                       floor_no, has_lift, active
                  FROM customer WHERE name ILIKE ? ORDER BY id LIMIT 1
                """, "%" + nameFragment.trim() + "%");

        if (found.isEmpty()) {
            return Map.of("found", false, "searched_for", nameFragment);
        }
        Long id = ((Number) found.get(0).get("id")).longValue();

        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT o.order_no, CAST(o.booked_at AS DATE) AS booked_on, o.status,
                       o.cylinder_count, o.failure_reason, u.full_name AS agent
                  FROM orders o
                  LEFT JOIN app_user u ON u.id = o.assigned_agent_id
                 WHERE o.customer_id = ?
                 ORDER BY o.booked_at DESC LIMIT 15
                """, id);

        return Map.of("found", true, "customer", found.get(0), "recent_orders", orders);
    }

    /** What is happening right now. */
    @Transactional(readOnly = true)
    public Map<String, Object> todaySnapshot() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM attendance
                         WHERE work_date = CURRENT_DATE AND check_out_at IS NULL) AS agents_on_duty,
                       (SELECT count(*) FROM attendance WHERE work_date = CURRENT_DATE) AS agents_checked_in_today,
                       (SELECT count(*) FROM orders WHERE status = 'BOOKED') AS orders_waiting,
                       (SELECT count(*) FROM orders WHERE status = 'ASSIGNED') AS orders_in_progress,
                       (SELECT count(*) FROM orders
                         WHERE status = 'DELIVERED' AND CAST(delivered_at AS DATE) = CURRENT_DATE) AS delivered_today,
                       (SELECT count(*) FROM orders
                         WHERE status = 'FAILED' AND CAST(delivered_at AS DATE) = CURRENT_DATE) AS failed_today
                """);
    }

    /** Localities the branch delivers to, for the intake page. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> areaList() {
        return jdbc.queryForList("""
                SELECT id, name FROM area WHERE active ORDER BY name
                """);
    }
}
