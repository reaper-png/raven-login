package fun.rederaven.login;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Only the dedicated server reads this file. Never put the token in the client. */
public final class LoginConfig {
    private static final Path FILE = Path.of("config", "ravenlogin-server.properties");
    public final String apiUrl;
    public final String token;
    public final int loginTimeoutSeconds;
    public final int httpTimeoutSeconds;

    private LoginConfig(Properties props) {
        apiUrl = props.getProperty("api.url", "").trim();
        token = props.getProperty("api.token", "").trim();
        loginTimeoutSeconds = bounded(props.getProperty("login.timeout.seconds", "120"), 30, 600, 120);
        httpTimeoutSeconds = bounded(props.getProperty("http.timeout.seconds", "8"), 2, 20, 8);
    }

    public boolean isReady() {
        try {
            return !token.isBlank() && token.length() >= 32 && !token.startsWith("REPLACE_WITH")
                && "https".equalsIgnoreCase(java.net.URI.create(apiUrl).getScheme())
                && java.net.URI.create(apiUrl).getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static LoginConfig load() {
        Properties props = new Properties();
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.notExists(FILE)) {
                props.setProperty("api.url", "https://rederaven.fun/api/raven-login.php");
                props.setProperty("api.token", "REPLACE_WITH_SAME_RANDOM_TOKEN_AS_WEBSITE_ENV");
                props.setProperty("login.timeout.seconds", "120");
                props.setProperty("http.timeout.seconds", "8");
                try (OutputStream out = Files.newOutputStream(FILE)) {
                    props.store(out, "Raven Login - server only. NEVER share this file/token.");
                }
            }
            try (InputStream in = Files.newInputStream(FILE)) {
                props.load(in);
            }
        } catch (IOException e) {
            System.err.println("[RavenLogin] Could not read server configuration: " + e.getClass().getSimpleName());
        }
        return new LoginConfig(props);
    }

    private static int bounded(String s, int lo, int hi, int def) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(s))); }
        catch (NumberFormatException ex) { return def; }
    }
}
