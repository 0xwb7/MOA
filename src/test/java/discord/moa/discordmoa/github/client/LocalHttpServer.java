package discord.moa.discordmoa.github.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * GitHub 대신 응답하는 테스트용 로컬 HTTP 서버.
 */
final class LocalHttpServer implements AutoCloseable {

    interface Handler {
        Response handle(String method, String path);
    }

    record Response(int status, String body, long delayMs) {
        static Response json(int status, String body) {
            return new Response(status, body, 0);
        }
    }

    private final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();

    LocalHttpServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, handler));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, Handler handler) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        Response response = handler.handle(method, path);
        try {
            Thread.sleep(response.delayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // 타임아웃 테스트에서는 클라이언트가 먼저 끊는다
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
