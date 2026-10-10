package dev.ahprofit.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Production transport pinned to DonutSMP's documented HTTPS API host. */
public final class JdkApiTransport implements ApiTransport {
    private static final URI BASE_URI = URI.create("https://api.donutsmp.net");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_2)
            .build();

    @Override
    public ApiResponse get(String path, String jsonBody, String bearerToken) throws IOException, InterruptedException {
        if (path == null || !path.startsWith("/v1/auction/")) {
            throw new IllegalArgumentException("Only documented auction endpoints are enabled.");
        }
        URI uri = BASE_URI.resolve(path);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"api.donutsmp.net".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Refusing to send API credentials to a non-DonutSMP host.");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("User-Agent", "AhProfit/1.0.0")
                .header("Authorization", "Bearer " + bearerToken);
        if (jsonBody == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .method("GET", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream bodyStream = response.body()) {
            byte[] bytes = bodyStream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("DonutSMP response exceeded the 4 MiB safety limit.");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, String.join(",", values));
                }
            });
            return new ApiResponse(response.statusCode(), headers, new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
