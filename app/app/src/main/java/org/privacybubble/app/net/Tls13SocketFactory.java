package org.privacybubble.app.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Restricts the process-default HTTPS socket factory to TLS 1.3. */
public final class Tls13SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    private Tls13SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

    /** Keeps the connection's default hostname verifier and process-default trust configuration. */
    public static void configure(HttpsURLConnection connection) {
        connection.setSSLSocketFactory(new Tls13SocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory()));
    }

    private Socket restrict(Socket socket) throws IOException {
        if (!(socket instanceof SSLSocket)) { socket.close(); throw new SSLException("TLS unavailable"); }
        try { ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.3"}); return socket; }
        catch (IllegalArgumentException e) { socket.close(); throw new SSLException("TLS 1.3 unavailable"); }
    }
    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException { return restrict(delegate.createSocket(socket, host, port, autoClose)); }
    @Override public Socket createSocket(String host, int port) throws IOException { return restrict(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return restrict(delegate.createSocket(host, port, local, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return restrict(delegate.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return restrict(delegate.createSocket(host, port, local, localPort)); }
    @Override public Socket createSocket() throws IOException { return restrict(delegate.createSocket()); }
}
