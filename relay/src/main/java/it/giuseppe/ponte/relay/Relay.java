package it.giuseppe.ponte.relay;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Ponte TCP → socket locale astratto di scrcpy-server.
 *
 * Avvio: app_process / it.giuseppe.ponte.relay.Relay TOKEN NOME_SOCKET NUMERO_CONNESSIONI
 *
 * Apre una porta TCP libera e la stampa ("PONTE_RELAY_PORT n"). Ogni client deve inviare per
 * prima cosa il TOKEN (impedisce ad altri dispositivi della rete di collegarsi); poi la
 * connessione viene unita, nell'ordine di arrivo, a una nuova connessione al socket locale.
 * Il processo termina quando tutte le connessioni sono chiuse.
 */
public final class Relay {

    private Relay() {
    }

    public static void main(String... args) throws Exception {
        if (args.length < 3) {
            System.out.println("PONTE_RELAY_ERROR argomenti mancanti");
            return;
        }
        byte[] token = args[0].getBytes(StandardCharsets.US_ASCII);
        String socketName = args[1];
        int count = Integer.parseInt(args[2]);

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(0));
        server.setSoTimeout(20_000);
        System.out.println("PONTE_RELAY_PORT " + server.getLocalPort());
        System.out.flush();

        int accepted = 0;
        try {
            while (accepted < count) {
                Socket client;
                try {
                    client = server.accept();
                } catch (SocketTimeoutException e) {
                    System.out.println("PONTE_RELAY_TIMEOUT");
                    break;
                }
                try {
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(5_000);
                    byte[] received = readExactly(client.getInputStream(), token.length);
                    if (received == null || !MessageDigest.isEqual(received, token)) {
                        client.close();
                        continue;
                    }
                    client.setSoTimeout(0);
                    LocalSocket local = connectLocal(socketName, 10_000);
                    pipe(client.getInputStream(), local.getOutputStream(), client, local, "in-" + accepted);
                    pipe(local.getInputStream(), client.getOutputStream(), client, local, "out-" + accepted);
                    accepted++;
                    System.out.println("PONTE_RELAY_CONNECTED " + accepted);
                    System.out.flush();
                } catch (IOException e) {
                    System.out.println("PONTE_RELAY_ERROR " + e);
                    System.out.flush();
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            server.close();
        }
    }

    private static LocalSocket connectLocal(String name, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                return socket;
            } catch (IOException e) {
                last = e;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    throw new IOException(ie);
                }
            }
        }
        throw last != null ? last : new IOException("socket locale non disponibile");
    }

    private static byte[] readExactly(InputStream in, int size) throws IOException {
        byte[] buffer = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(buffer, read, size - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }
        return buffer;
    }

    private static void pipe(InputStream in, OutputStream out, Socket tcp, LocalSocket local, String name) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[64 * 1024];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // connessione chiusa
            } finally {
                try {
                    tcp.close();
                } catch (IOException ignored) {
                }
                try {
                    local.close();
                } catch (IOException ignored) {
                }
            }
        }, "relay-" + name);
        thread.start();
    }
}
