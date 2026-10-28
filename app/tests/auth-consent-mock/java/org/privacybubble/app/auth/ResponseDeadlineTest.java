package org.privacybubble.app.auth;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Run on the host; no Android or device trust changes are required. */
public final class ResponseDeadlineTest {
    private static final class Clock implements ResponseDeadline.Clock {
        long now;
        @Override public long nanoTime() { return now; }
    }
    private static void expectTimeout(CheckedAction action) throws Exception {
        try { action.run(); throw new AssertionError("Expected a deadline failure"); }
        catch (SocketTimeoutException expected) { }
    }
    interface CheckedAction { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Clock clock = new Clock();
        ResponseDeadline normal = new ResponseDeadline(clock, 10);
        if (!"ok".equals(normal.read(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)), 2))) throw new AssertionError();
        try { normal.read(new ByteArrayInputStream(new byte[3]), 2); throw new AssertionError("oversized body accepted"); }
        catch (IllegalStateException expected) { }
        ResponseDeadline slow = new ResponseDeadline(clock, 10);
        InputStream dribble = new InputStream() {
            int bytes;
            @Override public int read() { clock.now += 2; return bytes++ < 6 ? 'x' : -1; }
            @Override public int read(byte[] b, int off, int len) { int value = read(); if (value < 0) return -1; b[off] = (byte) value; return 1; }
        };
        expectTimeout(() -> slow.read(dribble, 16));
        clock.now = 0;
        ResponseDeadline beforeHeaders = new ResponseDeadline(clock, 10);
        clock.now = 10;
        expectTimeout(beforeHeaders::check);
        realSlowResponse();
        System.out.println("Response deadline tests passed");
    }

    private static void realSlowResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[1024]);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    for (int i = 0; i < 20; i++) {
                        socket.getOutputStream().write('x'); socket.getOutputStream().flush();
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) { /* Expected when the client closes after its deadline. */ }
            });
            peer.start();
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getLocalPort() + "/").openConnection();
            connection.setReadTimeout(2000);
            try (InputStream input = connection.getInputStream()) {
                ResponseDeadline deadline = new ResponseDeadline(System::nanoTime, 500_000_000L);
                expectTimeout(() -> deadline.read(input, 100));
            } finally { connection.disconnect(); peer.join(3000); }
            if (peer.isAlive()) throw new AssertionError("test peer did not exit");
        }
    }
}
