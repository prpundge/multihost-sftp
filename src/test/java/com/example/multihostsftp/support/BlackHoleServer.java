package com.example.multihostsftp.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Accepts TCP connections and then never says a word - like an SFTP server that hangs (or a firewall that swallows
 * the traffic). Counts the connection attempts it received.
 */
public final class BlackHoleServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final List<Socket> connections = new CopyOnWriteArrayList<>();

    public BlackHoleServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName(EmbeddedSftpServer.HOST));
        Thread acceptor = new Thread(this::acceptForever, "black-hole-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptForever() {
        while (!serverSocket.isClosed()) {
            try {
                connections.add(serverSocket.accept()); // keep it open, never answer
            } catch (IOException e) {
                return;
            }
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int connectionAttempts() {
        return connections.size();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Socket socket : connections) {
            socket.close();
        }
    }
}
