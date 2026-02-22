import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Rendezvous {
    private static final int PORT = 8888;
    private static final int WAIT_TIMEOUT_MINUTES = 3;

    // Map of session ID -> waiting HOST peer
    private static final ConcurrentHashMap<String, WaitEntry> waiting = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private static class WaitEntry {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final String addr;
        final int natPort;
        volatile ScheduledFuture<?> timeoutFuture;

        WaitEntry(Socket socket, DataInputStream in, DataOutputStream out,
                  String addr, int natPort) {
            this.socket  = socket;
            this.in      = in;
            this.out     = out;
            this.addr    = addr;
            this.natPort = natPort;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        ServerSocket ss = new ServerSocket(port);
        LogUtils.info("Rendezvous server listening on port: " + port);
        LogUtils.info("Modes: PORT-FORWARD, HOLE-PUNCH, or RELAY");

        while (true) {
            Socket peer = ss.accept();
            // Each connection is handled independently so many peers can wait at once
            new Thread(() -> handleConnection(peer)).start();
        }
    }

    // -------------------------------------------------------------------------
    // Entry point for every incoming connection
    // -------------------------------------------------------------------------
    private static void handleConnection(Socket peer) {
        String addr    = peer.getInetAddress().getHostAddress();
        int    natPort = peer.getPort();

        DataInputStream  in;
        DataOutputStream out;
        try {
            in  = new DataInputStream(peer.getInputStream());
            out = new DataOutputStream(peer.getOutputStream());
        } catch (IOException e) {
            try { peer.close(); } catch (Exception ignored) {}
            return;
        }

        try {
            String role = in.readUTF();              // "HOST" or "JOIN"
            String id   = in.readUTF().toUpperCase().trim();

            if (role.equals("HOST")) {
                handleHost(peer, in, out, addr, natPort, id);
            } else if (role.equals("JOIN")) {
                handleJoin(peer, in, out, addr, natPort, id);
            } else {
                LogUtils.warn("Unknown role '" + role + "' from " + addr);
                peer.close();
            }
        } catch (IOException e) {
            LogUtils.warn("Connection lost from " + addr + ": " + e.getMessage());
            try { peer.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // HOST: register in the waiting map and arm the 3-minute timeout
    // -------------------------------------------------------------------------
    private static void handleHost(Socket peer, DataInputStream in, DataOutputStream out,
                                   String addr, int natPort, String id) throws IOException {
        LogUtils.success("HOST \033[1;36m" + addr + "\033[0m:\033[1;35m" + natPort
            + "\033[0m — session ID \033[1;33m" + id + "\033[0m");

        WaitEntry entry    = new WaitEntry(peer, in, out, addr, natPort);
        WaitEntry existing = waiting.putIfAbsent(id, entry); // atomic, no race

        if (existing != null) {
            LogUtils.warn("ID \033[1;33m" + id + "\033[0m already taken — rejected " + addr);
            out.writeUTF("ID_TAKEN");
            out.flush();
            peer.close();
            return;
        }

        // Tell the peer it's registered and can now send its listenPort
        out.writeUTF("WAITING");
        out.flush();
        LogUtils.info("Session \033[1;33m" + id + "\033[0m waiting (timeout: "
            + WAIT_TIMEOUT_MINUTES + " min)");

        // Auto-expire after 3 minutes if no JOIN arrives
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            WaitEntry removed = waiting.remove(id);
            if (removed != null) {                         // we won the race against JOIN
                LogUtils.warn("Session \033[1;33m" + id + "\033[0m timed out.");
                try {
                    removed.out.writeUTF("TIMEOUT");
                    removed.out.flush();
                } catch (IOException ignored) {}
                try { removed.socket.close(); } catch (Exception ignored) {}
            }
        }, WAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        entry.timeoutFuture = future;
        // This thread exits; the socket stays alive inside WaitEntry
        // and will be handled by whichever JOIN thread picks it up.
    }

    // -------------------------------------------------------------------------
    // JOIN: find the matching HOST, cancel its timeout, pair the two peers
    // -------------------------------------------------------------------------
    private static void handleJoin(Socket peer2, DataInputStream in2, DataOutputStream out2,
                                   String addr2, int natPort2, String id) throws IOException {
        LogUtils.success("JOIN \033[1;36m" + addr2 + "\033[0m:\033[1;35m" + natPort2
            + "\033[0m — session ID \033[1;33m" + id + "\033[0m");

        WaitEntry entry = waiting.remove(id);  // atomic removal — prevents double-join
        if (entry == null) {
            LogUtils.warn("No session \033[1;33m" + id + "\033[0m — rejecting " + addr2);
            out2.writeUTF("NOT_FOUND");
            out2.flush();
            peer2.close();
            return;
        }

        // Cancel the 3-minute timeout; the session is now active
        if (entry.timeoutFuture != null) {
            entry.timeoutFuture.cancel(false);
        }

        Socket           peer1   = entry.socket;
        DataInputStream  in1     = entry.in;
        DataOutputStream out1    = entry.out;
        String           addr1   = entry.addr;
        int              natPort1 = entry.natPort;

        // Notify the JOIN peer that the host was found
        out2.writeUTF("FOUND");
        out2.flush();

        LogUtils.success("Pairing \033[1;36m" + addr1 + "\033[0m:\033[1;35m" + natPort1
            + "\033[0m <-> \033[1;36m" + addr2 + "\033[0m:\033[1;35m" + natPort2 + "\033[0m");

        // Both peers sent their listenPort immediately after receiving WAITING/FOUND
        int listenPort1 = in1.readInt();
        int listenPort2 = in2.readInt();

        boolean pf1 = listenPort1 >= 1024 && listenPort1 <= 65535;
        boolean pf2 = listenPort2 >= 1024 && listenPort2 <= 65535;

        LogUtils.info("Peer 1: forwarded=\033[1;36m" + (pf1 ? listenPort1 : "\033[0mnone")
            + "\033[0m nat_port=\033[1;35m" + natPort1 + "\033[0m");
        LogUtils.info("Peer 2: forwarded=\033[1;36m" + (pf2 ? listenPort2 : "\033[0mnone")
            + "\033[0m nat_port=\033[1;35m" + natPort2 + "\033[0m");

        if (pf1) {
            out1.writeUTF("LISTEN");
            out1.writeInt(listenPort1);
            out1.writeUTF(addr2);
            out1.flush();

            out2.writeUTF("CONNECT");
            out2.writeInt(listenPort1);
            out2.writeUTF(addr1);
            out2.flush();

            LogUtils.info("Mode: PORT-FORWARD (Peer2 -> Peer1:\033[1;35m" + listenPort1 + "\033[0m)");
            peer1.close();
            peer2.close();
        }
        else if (pf2) {
            out1.writeUTF("CONNECT");
            out1.writeUTF(addr2);
            out1.writeInt(listenPort2);
            out1.flush();

            out2.writeUTF("LISTEN");
            out2.writeInt(listenPort2);
            out2.writeUTF(addr1);
            out2.flush();

            LogUtils.info("Mode: PORT-FORWARD (Peer1 -> Peer2:\033[1;35m" + listenPort2 + "\033[0m)");
            peer1.close();
            peer2.close();
        }
        else if (!addr1.equals(addr2)) {
            // Tell each peer:
            //   - the other's external IP:Port (as observed by the NAT mapping)
            //   - their OWN external port, so they know which local port to rebind
            // Flush both simultaneously — timing matters for hole punching
            out1.writeUTF("PUNCH");
            out1.writeUTF(addr2);      // peer2 external IP
            out1.writeInt(natPort2);   // peer2 external port (true NAT mapping)
            out1.writeInt(natPort1);   // peer1's OWN external port

            out2.writeUTF("PUNCH");
            out2.writeUTF(addr1);      // peer1 external IP
            out2.writeInt(natPort1);   // peer1 external port (true NAT mapping)
            out2.writeInt(natPort2);   // peer2's OWN external port

            out1.flush();
            out2.flush();

            LogUtils.info("Mode: HOLE-PUNCH (\033[1;36m"
                + addr1 + "\033[0m:\033[1;35m" + natPort1
                + "\033[0m <-> \033[1;36m"
                + addr2 + "\033[0m:\033[1;35m" + natPort2 + "\033[0m)");

            peer1.close();
            peer2.close();
        }
        else {
            // Same public IP = LAN, or symmetric NAT -> RELAY
            out1.writeUTF("RELAY");
            out1.flush();
            out2.writeUTF("RELAY");
            out2.flush();

            LogUtils.info("Mode: RELAY (same public IP or symmetric NAT)");

            Thread t1 = new Thread(() -> relay(peer1, peer2, "Peer1->Peer2"));
            Thread t2 = new Thread(() -> relay(peer2, peer1, "Peer2->Peer1"));
            t1.start();
            t2.start();
        }
    }

    private static void relay(Socket from, Socket to, String name) {
        try {
            InputStream  in     = from.getInputStream();
            OutputStream out    = to.getOutputStream();
            byte[]       buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        }
        catch (IOException e) {
            LogUtils.warn(name + " relay ended");
        }
        finally {
            try { from.close(); } catch (Exception e) {}
            try { to.close(); }   catch (Exception e) {}
        }
    }
}
