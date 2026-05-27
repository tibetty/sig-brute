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
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
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
        fetchAndAdd(SOURCIFY + hex, seen);
        if (seen.isEmpty()) {
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
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                seen.addAll(parseTextSignatures(response.body()));
            }
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
