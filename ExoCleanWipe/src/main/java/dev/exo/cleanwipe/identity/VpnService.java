package dev.exo.cleanwipe.identity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.exo.cleanwipe.storage.CoreStore;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * VPN / proxy lookups through proxycheck.io's v2 API ({@code GET https://proxycheck.io/v2/<ip>?key=..&vpn=1}).
 * Lookups are asynchronous with a hard timeout and cached (memory + database). The API key is only ever placed in
 * the request URL; the URL is never logged and errors are reported without it.
 *
 * <p>A clean result does not prove anything: residential proxies, mobile networks, friends' connections and new
 * VPN ranges are not detected. This is one signal among several, not ban-evasion prevention.
 */
public final class VpnService {

    public enum Verdict { CLEAN, VPN, ERROR }

    public record Result(Verdict verdict, String detail) {
    }

    /** Pluggable for tests and other providers. */
    public interface Provider {
        String name();

        CompletableFuture<Result> lookup(String ip);
    }

    private final Provider provider;
    private final CoreStore store;
    private final long cacheMillis;
    private final long timeoutMillis;
    private final Logger logger;
    private final Map<String, CoreStore.VpnEntry> memory = new ConcurrentHashMap<>();

    public VpnService(Provider provider, CoreStore store, long cacheMillis, long timeoutMillis, Logger logger) {
        this.provider = provider;
        this.store = store;
        this.cacheMillis = cacheMillis;
        this.timeoutMillis = timeoutMillis;
        this.logger = logger;
    }

    /** Blocking check for the async pre-login thread; never waits longer than the timeout. */
    public Result check(String ip) {
        long now = System.currentTimeMillis();
        CoreStore.VpnEntry cached = memory.get(ip);
        if (cached == null) {
            try {
                Optional<CoreStore.VpnEntry> db = store.vpnCached(ip, now - cacheMillis);
                if (db.isPresent()) {
                    cached = db.get();
                    memory.put(ip, cached);
                }
            } catch (Exception e) {
                logger.warning("vpn cache read failed: " + e.getMessage());
            }
        }
        if (cached != null && cached.checkedAt() >= now - cacheMillis) {
            return new Result(Verdict.valueOf(cached.verdict()), cached.detail());
        }
        Result r;
        try {
            r = provider.lookup(ip).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return new Result(Verdict.ERROR, "timed out after " + timeoutMillis + " ms");
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Result(Verdict.ERROR, cause.getClass().getSimpleName());
        }
        if (r.verdict() != Verdict.ERROR) {
            CoreStore.VpnEntry entry = new CoreStore.VpnEntry(r.verdict().name(), r.detail(), now);
            memory.put(ip, entry);
            try {
                store.vpnStore(ip, r.verdict().name(), r.detail(), provider.name(), now);
            } catch (Exception e) {
                logger.warning("vpn cache write failed: " + e.getMessage());
            }
        }
        return r;
    }

    /** proxycheck.io v2. */
    public static final class ProxyCheck implements Provider {
        private final HttpClient http;
        private final String key;
        private final long timeoutMillis;

        public ProxyCheck(String key, long timeoutMillis) {
            this.key = key == null ? "" : key.trim();
            this.timeoutMillis = timeoutMillis;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis)).build();
        }

        @Override
        public String name() {
            return "proxycheck";
        }

        @Override
        public CompletableFuture<Result> lookup(String ip) {
            String url = "https://proxycheck.io/v2/" + URLEncoder.encode(ip, StandardCharsets.UTF_8) + "?vpn=1&risk=1"
                    + (key.isEmpty() ? "" : "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMillis)).GET().build();
            return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> parse(ip, resp.statusCode(), resp.body()))
                    .exceptionally(t -> new Result(Verdict.ERROR, "request failed (" + t.getClass().getSimpleName() + ")"));
        }

        static Result parse(String ip, int status, String body) {
            if (status != 200) {
                return new Result(Verdict.ERROR, "http " + status);
            }
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                String st = root.has("status") ? root.get("status").getAsString() : "";
                if (!st.equals("ok") && !st.equals("warning")) {
                    return new Result(Verdict.ERROR, "provider status " + st);
                }
                JsonElement entry = root.get(ip);
                if (entry == null || !entry.isJsonObject()) {
                    return new Result(Verdict.ERROR, "no result for address");
                }
                JsonObject o = entry.getAsJsonObject();
                boolean proxy = o.has("proxy") && o.get("proxy").getAsString().equalsIgnoreCase("yes");
                String type = o.has("type") ? o.get("type").getAsString().toLowerCase() : "proxy";
                String risk = o.has("risk") ? " risk " + o.get("risk").getAsString() : "";
                return proxy ? new Result(Verdict.VPN, type + risk) : new Result(Verdict.CLEAN, type + risk);
            } catch (RuntimeException e) {
                return new Result(Verdict.ERROR, "unreadable provider response");
            }
        }
    }
}
