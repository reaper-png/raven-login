package fun.rederaven.login;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** HTTPS requests are async; never block the main Minecraft thread on network IO. */
public final class LoginApi {
    public record Answer(boolean ok, String status, int attemptsRemaining) {}
    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final LoginConfig config;

    public LoginApi(LoginConfig config) { this.config = config; }

    public CompletableFuture<Answer> call(String action, String nickname, String secret) {
        if (!config.isReady()) {
            return CompletableFuture.completedFuture(new Answer(false, "not_configured", 0));
        }
        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        data.addProperty("nickname", nickname);
        if ("authenticate".equals(action)) data.addProperty("password", secret);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiUrl))
            .timeout(Duration.ofSeconds(config.httpTimeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Raven-Token", config.token)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(data)))
            .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    return new Answer(false, "api_unavailable", 0);
                }
                try {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    if (json == null || !json.has("status") || !json.has("ok")) {
                        return new Answer(false, "api_invalid_response", 0);
                    }
                    String status = json.get("status").getAsString();
                    boolean ok = json.get("ok").getAsBoolean();
                    int remaining = json.has("attempts_remaining") ? json.get("attempts_remaining").getAsInt() : 0;
                    return new Answer(ok, status, remaining);
                } catch (Exception ex) {
                    return new Answer(false, "api_invalid_response", 0);
                }
            })
            .exceptionally(ex -> new Answer(false, "api_unavailable", 0));
    }
}
