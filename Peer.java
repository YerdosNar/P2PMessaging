import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

public class Peer {
    private Socket socket;
    private Crypto crypto;
    private Send sender;
    private Receive receiver;
    private final Scanner sc = new Scanner(System.in);

    public void punch(String vpsIp, int vpsPort, int listenPort) throws Exception {
        System.out.println("Connecting to rendezvous server...");
        Socket vps = new Socket(vpsIp, vpsPort);

        DataInputStream in = new DataInputStream(vps.getInputStream());
        DataOutputStream out = new DataOutputStream(vps.getOutputStream());

        out.writeInt(listenPort);
        out.flush();

        System.out.println("Waiting for peer to join...");

        String role = in.readUTF();
        System.out.println("Role assigned: " + role);

        if (role.equals("LISTEN")) {
            int port = in.readInt();
            String peerIp = in.readUTF();
            vps.close();

            System.out.println("Waiting for peer (" + peerIp + ") to connect on port " + port);
            ServerSocket ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            socket = ss.accept();
            ss.close();
            System.out.println("Peer connected!");

            doKeyExchange();
            startChat();
        }
        else if(role.equals("CONNECT")) {
            String peerIp = in.readUTF();
            int peerPort = in.readInt();
            vps.close();

            System.out.println("Connecting to peer at " + peerIp + ":" + peerPort);

            int attempts = 0;
            while (attempts < 10) {
                try {
                    socket = new Socket(peerIp, peerPort);
                    break;
                }
                catch (IOException ioe) {
                    System.out.println("Attempt " + (++attempts) + " failed, retrying...");
                    Thread.sleep(1000);
                }
            }

            if (socket == null) {
                throw new IOException("Failed to connect to peer");
            }
            System.out.println("Connected to peer!");

            doKeyExchange();
            startChat();
        }
        else if (role.equals("PUNCH")) {
            // TCP simultaneous-open hole punching.
            //
            // Key idea: reuse the SAME local port we used to reach the VPS.
            // Our NAT already has a mapping:  localPort <-> myExtPort
            // The peer knows our external IP:port from the rendezvous server.
            //
            // Both peers simultaneously send SYN packets to each other.
            // Each outbound SYN "primes" the NAT, so when the peer's inbound
            // SYN arrives, the NAT recognises it as related and forwards it.
            // This is TCP simultaneous-open (RFC 793) and most NATs support it
            // for cone NAT types (full-cone, address-restricted, port-restricted).
            // It will NOT work with symmetric NAT.

            String peerIp   = in.readUTF();
            int peerExtPort = in.readInt(); // peer's external port as seen by VPS
            int myExtPort   = in.readInt(); // my own external port as seen by VPS
            int myLocalPort = vps.getLocalPort(); // local port NAT mapped to myExtPort
            vps.close();

            System.out.println("Hole punching to " + peerIp + ":" + peerExtPort);
            System.out.println("Binding local port " + myLocalPort
                + " (mapped externally to " + myExtPort + ")");

            socket = holePunch(peerIp, peerExtPort, myLocalPort);

            if (socket != null) {
                System.out.println("Hole punch succeeded!");
                doKeyExchange();
                startChat();
            } else {
                System.out.println("Hole punch failed (likely symmetric NAT).");
                System.out.println("Please restart and enter 0 for both peers to use RELAY mode.");
            }
        } else if (role.equals("RELAY")) {
            System.out.println("Using relay mode (same LAN or symmetric NAT detected).");
            System.out.println("Traffic is still end-to-end encrypted — VPS sees only ciphertext.");
            socket = vps;
            doKeyExchange();
            startChat();
        }
        else {
            vps.close();
            throw new IOException("Unknown role: " + role);
        }
    }

    /**
     * TCP simultaneous-open hole punching.
     *
     * Runs two threads concurrently on the same local port:
     *   - A ServerSocket waiting for the peer's SYN to arrive inbound
     *   - Repeated Socket.connect() calls sending our SYN outbound
     *
     * Both use SO_REUSEADDR (and SO_REUSEPORT where the OS exposes it via
     * setReuseAddress) so they can share the local port.
     *
     * Whichever thread establishes the connection first wins; the other is
     * cancelled. Returns `null` if neither succeeds within the timeout.
     */
    private Socket holePunch(String peerIp, int peerPort, int localPort) {
        final Socket[] result = {null};
        final Object lock = new Object();

        // Thread 1: listen for the peer's inbound SYN
        Thread listenThread = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(localPort));
                ss.setSoTimeout(15_000);
                Socket s = ss.accept();
                synchronized (lock) {
                    if (result[0] == null) {
                        result[0] = s;
                    } else {
                        s.close();
                    }
                    lock.notifyAll();
                }
            } catch (IOException e) {
                // Timed out or interrupted - connect thread may still win
            }
        });

        // Thread 2: send our outbound SYN repeatedly
        Thread connectThread = new Thread(() -> {
            for (int attempt = 1; attempt <= 15; attempt++) {
                synchronized (lock) {
                    if (result[0] != null) return; // listen thread already won
                }
                try {
                    Socket s = new Socket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(localPort));
                    s.connect(new InetSocketAddress(InetAddress.getByName(peerIp), peerPort), 1000);
                    synchronized (lock) {
                        if (result[0] == null) {
                            result[0] = s;
                        } else {
                            s.close();
                        }
                        lock.notifyAll();
                    }
                    return;
                } catch (IOException e) {
                    System.out.println("Punch attempt " + attempt + "/15 failed, retrying...");
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        });

        listenThread.setDaemon(true);
        connectThread.setDaemon(true);
        listenThread.start();
        connectThread.start();

        synchronized (lock) {
            if (result[0] == null) {
                try { lock.wait(20_000); } catch (InterruptedException e) {}
            }
        }

        listenThread.interrupt();
        connectThread.interrupt();

        return result[0];
    }

    public void connect(String ip, int port) throws Exception {
        System.out.println("Connecting to " + ip + ":" + port);
        socket = new Socket(ip, port);
        System.out.println("Connected!");
        doKeyExchange();
        startChat();
    }

    public void listen(int port) throws Exception {
        ServerSocket server = new ServerSocket(port);
        System.out.println("Listening on port: " + port + "...");
        socket = server.accept();
        System.out.println("Peer connected from " + socket.getInetAddress());
        server.close();
        doKeyExchange();
        startChat();
    }

    private void doKeyExchange() throws Exception {
        System.out.println("Performing key exchange...");
        crypto = new Crypto();

        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        byte[] myPubKey = crypto.getPublicKeyBytes();
        out.writeInt(myPubKey.length);
        out.write(myPubKey);
        out.flush();

        int length = in.readInt();
        byte[] peerPubKey = new byte[length];
        in.readFully(peerPubKey);

        crypto.computeSharedSecret(peerPubKey);
        System.out.println("Encryption established!");
    }

    private void startChat() throws Exception {
        // Send asks name and sends it to the peer
        sender = new Send(socket, crypto, sc);

        // Receive is constructed after taking sender.name
        receiver = new Receive(socket, crypto, sender.name);

        Thread sendThread = new Thread(sender);
        Thread recvThread = new Thread(receiver);

        recvThread.start();
        sendThread.start();

        try {
            sendThread.join();
        }
        catch (InterruptedException e) {
            System.err.println(e.getMessage());
        }

        receiver.stop();
        socket.close();
        System.out.println("Disconnected...");
    }

    public static void main(String[] args) {
        Peer peer = new Peer();

        System.out.println("1. Connect to peer (direct)");
        System.out.println("2. Wait for peer (direct)");
        System.out.println("3. Via VPS (NAT traversal)");
        System.out.print("Select: ");
        int choice = peer.sc.nextInt();
        peer.sc.nextLine(); // get new line

        try {
            if (choice == 1) {
                System.out.print("Peer IP: ");
                String ip = peer.sc.nextLine();
                System.out.print("Port: ");
                int port = peer.sc.nextInt();
                peer.sc.nextLine();
                peer.connect(ip, port);
            }
            else if (choice == 2) {
                System.out.print("Port to listen: ");
                int port = peer.sc.nextInt();
                peer.sc.nextLine();
                peer.listen(port);
            }
            else if (choice == 3) {
                String vpsIp = "";
                System.out.print("IP/Domain name [i/D]: ");
                String ipOrDomain = peer.sc.nextLine();
                if (ipOrDomain.equalsIgnoreCase("i")) {
                    System.out.print("VPS IP: ");
                    vpsIp = peer.sc.nextLine();
                }
                else {
                    System.out.print("VPS Domain name: ");
                    String domName = peer.sc.nextLine();
                    try {
                        InetAddress inetAddress = InetAddress.getByName(domName);
                        vpsIp = inetAddress.getHostAddress();
                    }
                    catch (UnknownHostException e) {
                        System.err.println("Failed to resolve IP for domain: " + domName);
                        e.printStackTrace();
                    }
                }
                System.out.print("VPS port (default 8888): ");
                String vpsPortStr = peer.sc.nextLine();
                int vpsPort = vpsPortStr.isEmpty() ? 8888 : Integer.parseInt(vpsPortStr);

                System.out.print("Port forwarded? Enter port (or 0 for HOLE-PUNCH/RELAY): ");
                String listenPortStr = peer.sc.nextLine();
                int listenPort = listenPortStr.isEmpty() ? 0 : Integer.parseInt(listenPortStr);

                peer.punch(vpsIp, vpsPort, listenPort);
            }
            else {
                System.err.println("Unknown choice...");
            }
        }
        catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }

        peer.sc.close();
    }
}
