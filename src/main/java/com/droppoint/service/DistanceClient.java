package com.droppoint.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.droppoint.core.GeoPoint;

/**
 * Travel time between two points. Uses the Google Distance Matrix API when a key
 * is configured, and falls back to a straight-line estimate when it is not.
 */
@Component
public class DistanceClient {

    private static final Logger log = LoggerFactory.getLogger(DistanceClient.class);

    /** Roads are longer than straight lines; typical Indian city factor. */
    private static final double ROAD_FACTOR = 1.35;
    /** Average city speed including traffic and signals. */
    private static final double CITY_SPEED_KMPH = 22.0;

    public record Travel(double minutes, double km, String source) { }

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public DistanceClient(@Value("${droppoint.google.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    public Travel travelBetween(GeoPoint from, GeoPoint to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both points are required");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return callGoogle(from, to);
            } catch (Exception e) {
                log.warn("Google Distance Matrix failed ({}), using estimate", e.getMessage());
            }
        }
        return estimate(from, to);
    }

    private Travel estimate(GeoPoint from, GeoPoint to) {
        double km = from.distanceTo(to) / 1000.0 * ROAD_FACTOR;
        double minutes = km / CITY_SPEED_KMPH * 60;
        return new Travel(round1(minutes), round1(km), "ESTIMATE");
    }

    private Travel callGoogle(GeoPoint from, GeoPoint to) throws Exception {
        String url = "https://maps.googleapis.com/maps/api/distancematrix/json"
                + "?origins=" + from.latitude() + "," + from.longitude()
                + "&destinations=" + to.latitude() + "," + to.longitude()
                + "&mode=driving&departure_time=now&key=" + apiKey;

        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String body = res.body();
        if (res.statusCode() != 200 || !body.contains("\"status\" : \"OK\"")) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        double seconds = readNumber(body, "duration_in_traffic", "duration");
        double metres = readNumber(body, "distance", null);
        return new Travel(round1(seconds / 60.0), round1(metres / 1000.0), "GOOGLE");
    }

    /** Reads the "value" field that follows the given key in the JSON response. */
    private static double readNumber(String json, String key, String fallbackKey) {
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0 && fallbackKey != null) {
            at = json.indexOf("\"" + fallbackKey + "\"");
        }
        if (at < 0) {
            throw new IllegalStateException("Missing " + key + " in response");
        }
        int valueAt = json.indexOf("\"value\"", at);
        int colon = json.indexOf(':', valueAt);
        int end = colon + 1;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
            end++;
        }
        return Double.parseDouble(json.substring(colon + 1, end).trim());
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
