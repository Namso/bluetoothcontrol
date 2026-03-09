import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class FlowWebSocketServer {

    private final int port;
    private final String resultPayload;

    public FlowWebSocketServer(int port, String resultPayload) {
        this.port = port;
        this.resultPayload = resultPayload;
    }

    public void start() throws Exception {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Flow WebSocket escuchando en ws://localhost:" + port);

        while (true) {
            final Socket client = serverSocket.accept();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    handleClient(client);
                }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    private void handleClient(Socket client) {
        try {
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream());

            String headers = readHttpHeaders(in);
            String wsKey = readHeader(headers, "Sec-WebSocket-Key");
            if (wsKey == null) {
                client.close();
                return;
            }

            String acceptKey = generateAcceptKey(wsKey);
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // El resultado se envia de inmediato para que el cliente solo consuma y pinte.
            System.out.println("Enviando resultado al cliente. Bytes: " + resultPayload.getBytes(StandardCharsets.UTF_8).length);
            sendTextFrame(out, resultPayload);

            while (!client.isClosed()) {
                String payload = readTextFrame(in);
                if (payload == null) {
                    break;
                }
                processMessage(payload, out);
            }
        } catch (Exception e) {
            System.out.println("Conexion cerrada: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (IOException ignore) {
            }
        }
    }

    private void processMessage(String payload, BufferedOutputStream out) throws Exception {
        try {
            JSONObject request = new JSONObject(payload);
            String type = request.optString("type", "");
            if ("getResult".equals(type)) {
                sendTextFrame(out, resultPayload);
                return;
            }
            if (!"ping".equals(type)) {
                sendError(out, "Tipo de mensaje no soportado");
                return;
            }

            JSONObject pong = new JSONObject();
            pong.put("type", "pong");
            sendTextFrame(out, pong.toString());
        } catch (Exception ex) {
            sendError(out, "Error de analisis: " + ex.getMessage());
        }
    }

    private void sendError(BufferedOutputStream out, String message) throws Exception {
        JSONObject response = new JSONObject();
        response.put("type", "error");
        response.put("message", message);
        sendTextFrame(out, response.toString());
    }

    private static String readHttpHeaders(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        int curr;
        int tail = 0;
        while ((curr = in.read()) != -1) {
            baos.write(curr);
            if (prev == '\r' && curr == '\n') {
                tail++;
            } else if (curr != '\r') {
                tail = 0;
            }
            if (tail == 2) {
                break;
            }
            prev = curr;
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readHeader(String headers, String key) {
        String[] lines = headers.split("\\r?\\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx < 0) {
                continue;
            }
            String k = line.substring(0, idx).trim();
            if (key.equalsIgnoreCase(k)) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private static String generateAcceptKey(String wsKey) throws Exception {
        String seed = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(seed.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private static String readTextFrame(BufferedInputStream in) throws Exception {
        int b1 = in.read();
        if (b1 == -1) {
            return null;
        }
        int b2 = in.read();
        if (b2 == -1) {
            return null;
        }

        int opcode = b1 & 0x0F;
        if (opcode == 0x8) {
            return null;
        }

        boolean masked = (b2 & 0x80) != 0;
        long length = b2 & 0x7F;

        if (length == 126) {
            length = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (in.read() & 0xFF);
            }
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }

        byte[] payload = new byte[(int) length];
        readFully(in, payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void sendTextFrame(BufferedOutputStream out, String message) throws Exception {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        long length = payload.length;

        out.write(0x81);
        if (length <= 125) {
            out.write((int) length);
        } else if (length <= 65535) {
            out.write(126);
            out.write((int) ((length >> 8) & 0xFF));
            out.write((int) (length & 0xFF));
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((length >> (8 * i)) & 0xFF));
            }
        }
        out.write(payload);
        out.flush();
    }

    private static void readFully(BufferedInputStream in, byte[] buffer) throws Exception {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n == -1) {
                throw new IOException("Conexion cerrada en frame");
            }
            read += n;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: java FlowWebSocketServer <ruta-json> [puerto]");
            return;
        }

        String jsonPath = args[0];
        int port = 8081;
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }

        FlowAnalyzer analyzer = new FlowAnalyzer();
        System.out.println("Iniciando analisis desde archivo: " + jsonPath);
        long startedAt = System.currentTimeMillis();
        FlowAnalyzer.AnalysisResult result = analyzer.analyzeFile(jsonPath);
        long elapsed = System.currentTimeMillis() - startedAt;
        System.out.println("Analisis completado en " + elapsed + " ms. Jobs canonicos: " + result.canonicalCount);

        JSONObject response = new JSONObject();
        response.put("type", "analysisResult");
        response.put("elapsedMs", elapsed);
        response.put("data", result.toJson());

        new FlowWebSocketServer(port, response.toString()).start();
    }
}