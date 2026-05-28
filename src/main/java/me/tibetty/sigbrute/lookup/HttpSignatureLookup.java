package me.tibetty.sigbrute.lookup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;

/**
 * Queries Sourcify unified 4byte API, then original 4byte.directory as fallback.
 */
public final class HttpSignatureLookup implements SignatureLookup {

    private static final String SOURCIFY =
        "https://api.4byte.sourcify.dev/v1/signatures/?hex_signature=0x";
    private static final String FOURBYTE =
        "https://www.4byte.directory/api/v1/signatures/?hex_signature=0x";

    private final HttpClient client;
    private final Duration timeout;

    public HttpSignatureLookup() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
            Duration.ofSeconds(10));
    }

    HttpSignatureLookup(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public List<String> lookup(byte[] selector4) {
        if (selector4.length != 4) {
            throw new IllegalArgumentException("selector must be 4 bytes, got " + selector4.length);
        }

        var hex = HexUtil.toHex(selector4);
        var seen = new LinkedHashSet<String>();
        var sourcifyTransient = false;
        try {
            fetchAndAdd(SOURCIFY + hex, seen);
        } catch (LookupException e) {
            // Sourcify returned a non-2xx; fall through to the 4byte.directory backup.
            sourcifyTransient = true;
        }
        if (seen.isEmpty() || sourcifyTransient) {
            fetchAndAdd(FOURBYTE + hex, seen);
        }
        return List.copyOf(seen);
    }

    private void fetchAndAdd(String url, LinkedHashSet<String> seen) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET()
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var status = response.statusCode();
            if (status >= 200 && status < 300) {
                seen.addAll(parseTextSignatures(response.body()));
            } else {
                // Non-2xx (e.g. 429 Too Many Requests, 503 Service Unavailable):
                // treat as empty for this endpoint so the fallback URL is tried next.
                // Callers interpret an empty result as "no known signatures" and continue
                // to brute-force search, which is the correct safe-fallback behaviour.
                throw new LookupException(status);
            }
        } catch (LookupException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Network failure — caller treats as empty lookup
        }
    }

    static List<String> parseTextSignatures(String json) {
        var out = new ArrayList<String>();
        var key = "\"text_signature\"";
        var idx = 0;
        while ((idx = json.indexOf(key, idx)) >= 0) {
            var extracted = extractTextSignature(json, idx + key.length());
            if (extracted == null) {
                break;
            }
            if (!extracted.signature().isBlank()) {
                out.add(extracted.signature());
            }
            idx = extracted.nextIndex();
        }
        return out;
    }

    private record ExtractedSignature(String signature, int nextIndex) {
    }

    private static ExtractedSignature extractTextSignature(String json, int afterKey) {
        var valueStart = json.indexOf(':', afterKey);
        if (valueStart < 0) {
            return null;
        }
        var quote = json.indexOf('"', valueStart + 1);
        if (quote < 0) {
            return null;
        }
        var end = json.indexOf('"', quote + 1);
        if (end < 0) {
            return null;
        }
        return new ExtractedSignature(json.substring(quote + 1, end), end + 1);
    }
}
