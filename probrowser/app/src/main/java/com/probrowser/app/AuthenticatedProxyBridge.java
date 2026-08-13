package com.probrowser.app;

import com.probrowser.app.model.ProxyServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small loopback proxy used only inside this app.
 *
 * Why this exists:
 * AndroidX WebView ProxyController accepts host/port proxy rules, but it does
 * not expose username/password fields. Authenticated upstream proxies can
 * therefore be unreliable if WebView is pointed at them directly.
 *
 * This bridge binds only to 127.0.0.1, receives WebView's CONNECT request,
 * adds Proxy-Authorization for the upstream HTTP/HTTPS proxy, then tunnels
 * bytes in both directions. Credentials never appear in the WebView proxy URL.
 */
public final class AuthenticatedProxyBridge implements Closeable {

    private final ProxyServer upstream;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private int localPort;

    public AuthenticatedProxyBridge(ProxyServer upstream) {
        this.upstream = upstream;
    }

    public synchronized int start() throws IOException {
        if (running.get()) {
            return localPort;
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        localPort = serverSocket.getLocalPort();
        running.set(true);

        executor.execute(this::acceptLoop);
        return localPort;
    }

    public int getLocalPort() {
        return localPort;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30000);
                executor.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    // Continue accepting unless stop() closed the socket.
                }
            }
        }
    }

    private void handleClient(Socket client) {
        Socket remote = null;
        try {
            BufferedInputStream clientIn = new BufferedInputStream(client.getInputStream());
            BufferedOutputStream clientOut = new BufferedOutputStream(client.getOutputStream());

            byte[] headerBytes = readHeaders(clientIn);
            if (headerBytes.length == 0) {
                return;
            }

            String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String firstLine = firstLine(headers);
            if (firstLine.isEmpty()) {
                writeSimpleError(clientOut, 400, "Bad proxy request");
                return;
            }

            String method = firstLine.split("\\s+", 2)[0].toUpperCase(Locale.US);

            remote = new Socket();
            remote.connect(new InetSocketAddress(upstream.host.trim(), upstream.port), 15000);
            remote.setSoTimeout(30000);

            BufferedInputStream remoteIn = new BufferedInputStream(remote.getInputStream());
            BufferedOutputStream remoteOut = new BufferedOutputStream(remote.getOutputStream());

            String authenticatedHeaders = injectProxyAuthorization(headers);
            remoteOut.write(authenticatedHeaders.getBytes(StandardCharsets.ISO_8859_1));
            remoteOut.flush();

            if ("CONNECT".equals(method)) {
                // Read and forward the upstream proxy's CONNECT response first.
                byte[] responseHeaders = readHeaders(remoteIn);
                if (responseHeaders.length == 0) {
                    writeSimpleError(clientOut, 502, "Proxy did not respond");
                    return;
                }

                clientOut.write(responseHeaders);
                clientOut.flush();

                String responseLine = firstLine(new String(responseHeaders, StandardCharsets.ISO_8859_1));
                if (!responseLine.contains(" 200 ") && !responseLine.endsWith(" 200")) {
                    // 407/403/etc. is forwarded to WebView; do not open a tunnel.
                    return;
                }

                final Socket tunnelRemote = remote;
                executor.execute(() -> copyQuietly(clientIn, tunnelRemote));
                copyQuietly(remoteIn, client);
            } else {
                // HTTP (non-CONNECT) request. After adding auth, relay the stream.
                // This path is mainly for plain-http sites; HTTPS uses CONNECT above.
                final Socket httpRemote = remote;
                executor.execute(() -> copyQuietly(clientIn, httpRemote));
                copyQuietly(remoteIn, client);
            }

        } catch (IOException e) {
            try {
                OutputStream out = client.getOutputStream();
                writeSimpleError(out, 502, "Upstream proxy connection failed");
            } catch (Exception ignored) {
            }
        } finally {
            closeQuietly(remote);
            closeQuietly(client);
        }
    }

    private String injectProxyAuthorization(String headers) {
        StringBuilder clean = new StringBuilder();
        String[] lines = headers.split("\\r?\\n");
        boolean first = true;

        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (!first && line.toLowerCase(Locale.US).startsWith("proxy-authorization:")) {
                continue;
            }
            clean.append(line).append("\r\n");
            first = false;
        }

        if (upstream.hasCredentials()) {
            String raw = upstream.username + ":" + upstream.password;
            String basic = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            clean.append("Proxy-Authorization: Basic ").append(basic).append("\r\n");
        }

        clean.append("\r\n");
        return clean.toString();
    }

    private static byte[] readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        int value;
        int limit = 64 * 1024;

        while ((value = input.read()) != -1 && buffer.size() < limit) {
            buffer.write(value);
            switch (state) {
                case 0: state = (value == '\r') ? 1 : 0; break;
                case 1: state = (value == '\n') ? 2 : 0; break;
                case 2: state = (value == '\r') ? 3 : 0; break;
                case 3:
                    if (value == '\n') {
                        return buffer.toByteArray();
                    }
                    state = 0;
                    break;
                default: state = 0;
            }
        }
        return buffer.toByteArray();
    }

    private static String firstLine(String headers) {
        int end = headers.indexOf("\r\n");
        if (end < 0) end = headers.indexOf('\n');
        return (end < 0 ? headers : headers.substring(0, end)).trim();
    }

    private static void copyQuietly(InputStream input, Socket outputSocket) {
        try {
            OutputStream output = new BufferedOutputStream(outputSocket.getOutputStream());
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
        }
        try {
            outputSocket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    private static void writeSimpleError(OutputStream output, int code, String message) throws IOException {
        String body = message + "\n";
        String response = "HTTP/1.1 " + code + " Proxy Error\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n\r\n"
                + body;
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    public synchronized void stop() {
        running.set(false);
        closeQuietly(serverSocket);
        serverSocket = null;
        localPort = 0;
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }
}
