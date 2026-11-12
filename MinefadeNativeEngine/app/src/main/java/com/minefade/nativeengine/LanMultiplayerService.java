package com.minefade.nativeengine;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local Minefade cooperative transport.
 *
 * Discovery uses Android NSD/mDNS. A small TCP session carries player transforms
 * and authoritative block edits. This class never touches OpenGL or native world
 * state directly; snapshots are consumed by MinefadeSurfaceView on the GL thread.
 */
public final class LanMultiplayerService implements Closeable {
    private static final String TAG = "MinefadeLAN";
    private static final String SERVICE_TYPE = "_minefade._tcp.";
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_PLAYERS = 8;
    private static final long STATE_INTERVAL_NS = 100_000_000L;

    public interface Listener {
        void onStatus(String status);
        void onGamesChanged(List<GameInfo> games);
        void onConnected(int localPlayerId);
        void onDisconnected(String reason);
    }

    public static final class GameInfo {
        public final String name;
        public final String host;
        public final int port;

        GameInfo(String name, String host, int port) {
            this.name = name == null || name.isEmpty() ? "Partida Minefade" : name;
            this.host = host;
            this.port = port;
        }

        @Override public String toString() {
            return name + " · " + host;
        }
    }

    private static final class RemotePlayer {
        final int id;
        final String name;
        final float x, y, z, yaw;

        RemotePlayer(int id, String name, float x, float y, float z, float yaw) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    private static final class Peer {
        final int id;
        final String name;
        final Socket socket;
        final BufferedWriter writer;

        Peer(int id, String name, Socket socket, BufferedWriter writer) {
            this.id = id;
            this.name = name;
            this.socket = socket;
            this.writer = writer;
        }

        synchronized void send(String message) throws IOException {
            writer.write(message);
            writer.newLine();
            writer.flush();
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private final Context appContext;
    private final NsdManager nsdManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<String, GameInfo> discoveredGames = new ConcurrentHashMap<>();
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();
    private final Map<Integer, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();
    private final Map<Integer, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<int[]> inboundBlockEdits = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);
    private final Object clientWriteLock = new Object();

    private volatile Listener listener;
    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile BufferedWriter clientWriter;
    private volatile NsdManager.RegistrationListener registrationListener;
    private volatile NsdManager.DiscoveryListener discoveryListener;
    private volatile WifiManager.MulticastLock multicastLock;
    private volatile boolean hosting;
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile int localPlayerId = -1;
    private volatile long lastStateSentNs;
    private volatile String playerName = "Jugador";
    private volatile String advertisedName = "Minefade";

    public LanMultiplayerService(Context context) {
        appContext = context.getApplicationContext();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    public boolean isHosting() {
        return hosting && isConnected();
    }

    public int getPlayerCount() {
        return hosting ? peers.size() + 1 : Math.max(1, playerNames.size());
    }

    public void startHost(String nickname, Listener callback) {
        stopSession();
        listener = callback;
        playerName = safeField(nickname, "Jugador");
        hosting = true;
        connected = false;
        localPlayerId = 0;
        playerNames.clear();
        playerNames.put(0, playerName);
        remotePlayers.clear();
        inboundBlockEdits.clear();
        postStatus("Preparando partida local…");

        io.execute(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(0));
                serverSocket = server;
                connected = true;
                io.execute(() -> acceptLoop(server));

                NsdServiceInfo service = new NsdServiceInfo();
                advertisedName = "Minefade-" + playerName.replace(' ', '-');
                service.setServiceName(advertisedName);
                service.setServiceType(SERVICE_TYPE);
                service.setPort(server.getLocalPort());
                service.setAttribute("protocol", String.valueOf(PROTOCOL_VERSION));
                NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
                    @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                        Log.e(TAG, "NSD registration failed: " + errorCode);
                        stopSession();
                        postStatus("No se pudo publicar la partida en la red local (" + errorCode + ").");
                        postDisconnected("No se pudo anunciar la partida.");
                    }
                    @Override public void onServiceRegistered(NsdServiceInfo info) {
                        advertisedName = info.getServiceName();
                        postStatus("Partida creada · esperando jugadores en LOCAL");
                        Listener current = listener;
                        if (current != null) main.post(() -> current.onConnected(0));
                    }
                    @Override public void onServiceUnregistered(NsdServiceInfo info) {
                        postStatus("Partida local cerrada.");
                    }
                    @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                        Log.w(TAG, "NSD unregister failed: " + errorCode);
                    }
                };
                registrationListener = registration;
                nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Could not start LAN host", error);
                stopSession();
                postStatus("No se pudo crear la partida. Comprueba que ambos teléfonos estén en la misma Wi-Fi.");
                postDisconnected("Error al iniciar el servidor local.");
            }
        });
    }

    public void discover(Listener callback) {
        stopDiscovery();
        listener = callback;
        discoveredGames.clear();
        notifyGames();
        acquireMulticastLock();
        if (nsdManager == null) {
            postStatus("Este dispositivo no ofrece descubrimiento de servicios de red.");
            return;
        }
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {
                postStatus("Buscando partidas cercanas…");
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                if (!SERVICE_TYPE.equals(service.getServiceType())) return;
                if (advertisedName.equals(service.getServiceName())) return;
                try {
                    nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            Log.w(TAG, "Could not resolve " + info.getServiceName() + ": " + errorCode);
                        }
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            InetAddress address = info.getHost();
                            if (address == null || info.getPort() <= 0) return;
                            GameInfo game = new GameInfo(info.getServiceName(),
                                    address.getHostAddress(), info.getPort());
                            discoveredGames.put(game.name, game);
                            notifyGames();
                        }
                    });
                } catch (RuntimeException error) {
                    Log.w(TAG, "NSD resolve request failed", error);
                }
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                discoveredGames.remove(service.getServiceName());
                notifyGames();
            }
            @Override public void onDiscoveryStopped(String type) {
                Log.i(TAG, "LAN discovery stopped");
            }
            @Override public void onStartDiscoveryFailed(String type, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
                stopDiscovery();
                postStatus("No se pudo buscar partidas (" + errorCode + ").");
            }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "Discovery stop failed: " + errorCode);
            }
        };
        discoveryListener = discovery;
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException error) {
            Log.e(TAG, "NSD discovery failed", error);
            postStatus("No se pudo iniciar la búsqueda de partidas.");
        }
    }

    public void join(GameInfo game, String nickname, Listener callback) {
        if (game == null) {
            postStatus("Selecciona una partida de la lista.");
            return;
        }
        stopDiscovery();
        stopSession();
        listener = callback;
        playerName = safeField(nickname, "Jugador");
        postStatus("Conectando con " + game.name + "…");
        io.execute(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(game.host, game.port), 5000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                clientSocket = socket;
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                clientWriter = writer;
                synchronized (clientWriteLock) {
                    writer.write("HELLO|" + playerName);
                    writer.newLine();
                    writer.flush();
                }
                String line;
                boolean welcomed = false;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.startsWith("WELCOME|")) {
                        String[] fields = line.split("\\|", -1);
                        if (fields.length >= 3) {
                            localPlayerId = parseInt(fields[1], -1);
                            playerNames.put(0, fields[2]);
                            connected = localPlayerId > 0;
                            welcomed = connected;
                            if (welcomed) {
                                int assigned = localPlayerId;
                                Listener current = listener;
                                main.post(() -> {
                                    postStatus("Conectado a " + game.name + " · sincronizando jugadores");
                                    if (current != null) current.onConnected(assigned);
                                });
                            }
                        }
                    } else if (welcomed) {
                        handleClientMessage(line);
                    }
                }
                if (!closed) {
                    connected = false;
                    clearRemotePlayers();
                    postDisconnected("La conexión con la partida se ha cerrado.");
                }
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "LAN join failed", error);
                if (!closed) {
                    connected = false;
                    postStatus("No se pudo conectar. Comprueba que ambos dispositivos estén en la misma Wi-Fi.");
                    postDisconnected("Conexión local fallida.");
                }
            } finally {
                closeQuietly(socket);
                if (clientSocket == socket) {
                    clientSocket = null;
                    clientWriter = null;
                }
            }
        });
    }

    private void acceptLoop(ServerSocket server) {
        while (!closed && hosting && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                if (peers.size() >= MAX_PLAYERS - 1) {
                    socket.close();
                    continue;
                }
                io.execute(() -> handlePeer(socket));
            } catch (IOException error) {
                if (!closed && !server.isClosed()) Log.w(TAG, "LAN accept failed", error);
                break;
            }
        }
    }

    private void handlePeer(Socket socket) {
        int id = -1;
        Peer peer = null;
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            String hello = reader.readLine();
            if (hello == null || !hello.startsWith("HELLO|")) {
                socket.close();
                return;
            }
            id = nextPlayerId.getAndIncrement();
            String name = safeField(hello.substring("HELLO|".length()), "Jugador");
            peer = new Peer(id, name, socket, writer);
            peers.put(id, peer);
            playerNames.put(id, name);
            peer.send("WELCOME|" + id + "|" + playerName);
            peer.send("PLAYER|0|" + playerName);
            for (Map.Entry<Integer, String> entry : playerNames.entrySet()) {
                if (entry.getKey() != 0 && entry.getKey() != id)
                    peer.send("PLAYER|" + entry.getKey() + "|" + entry.getValue());
            }
            broadcast("PLAYER|" + id + "|" + name, peer);
            postStatus("Jugador conectado: " + name + " · " + peers.size() + " conectado(s)");

            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.startsWith("STATE|")) {
                    RemotePlayer state = parseState(line);
                    if (state != null && state.id == id) {
                        remotePlayers.put(id, state);
                        broadcast(line, null);
                    }
                } else if (line.startsWith("BLOCK|")) {
                    int[] edit = parseBlock(line);
                    if (edit != null) {
                        inboundBlockEdits.offer(edit);
                        broadcast(line, peer);
                    }
                } else if (line.startsWith("PING|")) {
                    peer.send("PONG|" + line.substring(5));
                }
            }
        } catch (IOException | RuntimeException error) {
            if (!closed) Log.i(TAG, "Peer disconnected", error);
        } finally {
            if (id >= 0) {
                peers.remove(id);
                playerNames.remove(id);
                remotePlayers.remove(id);
                broadcast("LEAVE|" + id, null);
                postStatus("Un jugador salió · " + peers.size() + " conectado(s)");
            }
            closeQuietly(socket);
        }
    }

    private void handleClientMessage(String line) {
        if (line.startsWith("PLAYER|")) {
            String[] f = line.split("\\|", -1);
            if (f.length >= 3) playerNames.put(parseInt(f[1], -1), f[2]);
        } else if (line.startsWith("STATE|")) {
            RemotePlayer state = parseState(line);
            if (state != null && state.id != localPlayerId) {
                remotePlayers.put(state.id, state);
                playerNames.put(state.id, state.name);
            }
        } else if (line.startsWith("LEAVE|")) {
            int id = parseInt(line.substring(6), -1);
            remotePlayers.remove(id);
            playerNames.remove(id);
        } else if (line.startsWith("BLOCK|")) {
            int[] edit = parseBlock(line);
            if (edit != null) inboundBlockEdits.offer(edit);
        }
    }

    private void broadcast(String line, Peer excluded) {
        for (Peer peer : peers.values()) {
            if (peer == excluded) continue;
            try { peer.send(line); }
            catch (IOException error) {
                Log.w(TAG, "Could not forward LAN event to " + peer.name, error);
            }
        }
    }

    /** Called from the GL thread. Sends at most ten movement packets per second. */
    public void publishLocalState(float[] state) {
        if (!isConnected() || state == null || state.length < 4) return;
        final long now = System.nanoTime();
        if (now - lastStateSentNs < STATE_INTERVAL_NS) return;
        lastStateSentNs = now;
        String line = "STATE|" + localPlayerId + "|" + playerName + "|"
                + finite(state[0]) + "|" + finite(state[1]) + "|"
                + finite(state[2]) + "|" + finite(state[3]);
        if (hosting) {
            broadcast(line, null);
        } else {
            synchronized (clientWriteLock) {
                BufferedWriter writer = clientWriter;
                if (writer == null) return;
                try {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                } catch (IOException error) {
                    Log.w(TAG, "Could not send player state", error);
                    connected = false;
                    postDisconnected("Se perdió la conexión con el servidor.");
                }
            }
        }
    }

    /** Block edits are four integers per event: world X, Y, Z and block ID. */
    public void publishBlockEdits(int[] edits) {
        if (!isConnected() || edits == null || edits.length < 4) return;
        int count = edits.length - (edits.length % 4);
        for (int i = 0; i < count; i += 4) {
            String line = "BLOCK|" + edits[i] + "|" + edits[i + 1] + "|"
                    + edits[i + 2] + "|" + edits[i + 3];
            if (hosting) {
                broadcast(line, null);
            } else {
                synchronized (clientWriteLock) {
                    BufferedWriter writer = clientWriter;
                    if (writer == null) return;
                    try {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                    } catch (IOException error) {
                        Log.w(TAG, "Could not send block edit", error);
                        connected = false;
                        postDisconnected("Se perdió la conexión al sincronizar el mundo.");
                        return;
                    }
                }
            }
        }
    }

    /** Flattened [x,y,z,yaw] values, excluding this device's own avatar. */
    public float[] getRemotePlayerSnapshot() {
        List<RemotePlayer> snapshot = new ArrayList<>(remotePlayers.values());
        if (snapshot.isEmpty()) return new float[0];
        int count = Math.min(snapshot.size(), MAX_PLAYERS - 1);
        float[] result = new float[count * 4];
        int index = 0;
        for (RemotePlayer player : snapshot) {
            if (index >= result.length) break;
            result[index++] = player.x;
            result[index++] = player.y;
            result[index++] = player.z;
            result[index++] = player.yaw;
        }
        return result;
    }

    /** Drain authoritative block edits received from other LAN peers. */
    public int[] drainRemoteBlockEdits() {
        if (inboundBlockEdits.isEmpty()) return new int[0];
        List<int[]> values = new ArrayList<>();
        int[] next;
        while ((next = inboundBlockEdits.poll()) != null) values.add(next);
        int[] result = new int[values.size() * 4];
        int offset = 0;
        for (int[] edit : values) {
            System.arraycopy(edit, 0, result, offset, 4);
            offset += 4;
        }
        return result;
    }

    private RemotePlayer parseState(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 7) return null;
        try {
            int id = Integer.parseInt(fields[1]);
            String name = safeField(fields[2], "Jugador");
            float x = Float.parseFloat(fields[3]);
            float y = Float.parseFloat(fields[4]);
            float z = Float.parseFloat(fields[5]);
            float yaw = Float.parseFloat(fields[6]);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(yaw)
                    || Math.abs(x) > 1_000_000f || y < -128f || y > 1024f || Math.abs(z) > 1_000_000f) {
                return null;
            }
            return new RemotePlayer(id, name, x, y, z, yaw);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private int[] parseBlock(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 5) return null;
        try {
            int x = Integer.parseInt(fields[1]);
            int y = Integer.parseInt(fields[2]);
            int z = Integer.parseInt(fields[3]);
            int blockId = Integer.parseInt(fields[4]);
            if (Math.abs(x) > 1_000_000 || y < 0 || y >= 96
                    || Math.abs(z) > 1_000_000 || blockId < 0 || blockId > 65535) return null;
            return new int[]{x, y, z, blockId};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String safeField(String value, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) return fallback;
        if (cleaned.length() > 20) cleaned = cleaned.substring(0, 20);
        return cleaned;
    }

    private static String finite(float value) {
        return Float.isFinite(value) ? Float.toString(value) : "0";
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) return;
        try {
            WifiManager manager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (manager != null) {
                multicastLock = manager.createMulticastLock("minefade-lan-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not acquire Wi-Fi multicast lock", error);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (RuntimeException ignored) { }
        multicastLock = null;
    }

    private void stopDiscovery() {
        NsdManager.DiscoveryListener current = discoveryListener;
        discoveryListener = null;
        if (current != null && nsdManager != null) {
            try { nsdManager.stopServiceDiscovery(current); }
            catch (RuntimeException error) { Log.w(TAG, "Stop discovery failed", error); }
        }
        releaseMulticastLock();
    }

    private void stopSession() {
        connected = false;
        hosting = false;
        localPlayerId = -1;
        NsdManager.RegistrationListener registration = registrationListener;
        registrationListener = null;
        if (registration != null && nsdManager != null) {
            try { nsdManager.unregisterService(registration); }
            catch (RuntimeException error) { Log.w(TAG, "Unregister service failed", error); }
        }
        ServerSocket server = serverSocket;
        serverSocket = null;
        closeQuietly(server);
        Socket client = clientSocket;
        clientSocket = null;
        closeQuietly(client);
        clientWriter = null;
        for (Peer peer : peers.values()) peer.close();
        peers.clear();
        playerNames.clear();
        remotePlayers.clear();
        inboundBlockEdits.clear();
        releaseMulticastLock();
    }

    private void clearRemotePlayers() {
        remotePlayers.clear();
        playerNames.clear();
    }

    private void notifyGames() {
        Listener current = listener;
        if (current == null) return;
        List<GameInfo> snapshot = new ArrayList<>(discoveredGames.values());
        Collections.sort(snapshot, (a, b) -> a.name.compareToIgnoreCase(b.name));
        main.post(() -> {
            Listener latest = listener;
            if (latest != null) latest.onGamesChanged(snapshot);
        });
    }

    private void postStatus(String status) {
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.onStatus(status);
        });
    }

    private void postDisconnected(String reason) {
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.onDisconnected(reason);
        });
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        closed = true;
        stopDiscovery();
        stopSession();
        io.shutdownNow();
    }
}
