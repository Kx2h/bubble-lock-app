import org.privacybubble.app.api.HttpsApiTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class HttpsApiTransportTest {
    interface Action { void run() throws Exception; }
    static void invalid(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        HttpsApiTransport client = new HttpsApiTransport(URI.create(args[1]));
        if (!"trusted".equals(mode)) {
            try { client.request("GET", "/ok", Collections.emptyMap(), null); throw new AssertionError("TLS failure was accepted"); }
            catch (java.io.IOException expected) { System.out.println("TLS rejected"); }
            return;
        }
        invalid(() -> new HttpsApiTransport(URI.create("http://localhost")));
        invalid(() -> new HttpsApiTransport(URI.create("https://user@localhost")));
        invalid(() -> new HttpsApiTransport(URI.create("https://localhost/base")));
        for (String path : new String[]{"//attacker/x", "https://attacker/x", "/a/../b", "/a/%2e%2e/b", "/a/%2f/b", "/a/%252e%252e/b", "/a/%252f/b", "/a/%255c/b", "/a#fragment"})
            invalid(() -> client.request("GET", path, Collections.emptyMap(), null));
        invalid(() -> client.request("PATCH", "/ok", Collections.emptyMap(), null));
        invalid(() -> client.request("GET", "/ok", Collections.singletonMap("Host", "attacker"), null));
        invalid(() -> client.request("GET", "/ok", Collections.singletonMap("X-Test", "value\r\nBad: x"), null));
        invalid(() -> client.request("GET", "/ok", Collections.emptyMap(), new byte[1]));
        invalid(() -> client.request("POST", "/ok", Collections.emptyMap(), new byte[65537]));
        Map<String,String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer synthetic-test"); headers.put("X-Test-Request", "fixture");
        HttpsApiTransport.Response response = client.request("POST", "/write?choice=test", headers, "example".getBytes(StandardCharsets.UTF_8));
        if (response.status() != 201 || !"stored".equals(new String(response.body(), StandardCharsets.UTF_8)) || !"fixture-response".equals(response.header("x-fixture"))) throw new AssertionError("write failed");
        byte[] copy = response.body(); copy[0] = 0;
        if (response.body()[0] == 0 || response.toString().contains("stored")) throw new AssertionError("response leaked or mutable");
        if (client.request("GET", "/redirect", Collections.emptyMap(), null).status() != 302) throw new AssertionError("redirect followed");
        HttpsApiTransport.Response denied = client.request("GET", "/denied", Collections.emptyMap(), null);
        if (denied.status() != 403 || !"denied".equals(new String(denied.body(), StandardCharsets.UTF_8))) throw new AssertionError("error response lost");
        if (client.request("GET", "/duplicate", Collections.emptyMap(), null).header("x-duplicate") != null) throw new AssertionError("duplicate response header accepted");
        try { client.request("GET", "/oversize", Collections.emptyMap(), null); throw new AssertionError("oversized response accepted"); }
        catch (java.io.IOException expected) { }
        try { client.request("GET", "/slow", Collections.emptyMap(), null); throw new AssertionError("slow response accepted"); }
        catch (java.io.IOException expected) { }
        System.out.println("HTTPS transport tests passed");
    }
}
