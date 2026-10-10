package dev.ahprofit.api;

import java.util.Map;

public record ApiResponse(int statusCode, Map<String, String> headers, String body) {
    public ApiResponse {
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        body = body == null ? "" : body;
    }

    public String header(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
