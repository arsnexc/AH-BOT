package dev.ahprofit.api;

import java.io.IOException;

/** Transport abstraction permits deterministic HTTP policy tests without live API calls. */
public interface ApiTransport {
    ApiResponse get(String path, String jsonBody, String bearerToken) throws IOException, InterruptedException;
}
