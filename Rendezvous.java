import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Rendezvous {
    private static final int PORT = 8888;

    public static void main(String[] args) throws Exception {
        int port = PORT;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        ServerSocket ss = new ServerSocket(port);
        System.out.println("Rendezvous server listening on port: " + port);
        System.out.println("Modes: PORT-FORWARD, HOLE-PUNCH, or RELAY");

        while(true) {
            System.out.println("\nWaiting for two peers...");

            // Accept first
            Socket peer1 = ss.accept();
            String addr1 = peer1.getInetAddress().getHostAddress();
            int natPort1 = peer1.getPort();
            System.out.println("Peer 1 connected: " + addr1 + ":" + natPort1);

            // Accept second peers
            Socket peer2 = ss.accept();
            String addr2 = peer2.getInetAddress().getHostAddress();
            int natPort2 = peer2.getPort();
            System.out.println("Peer 2 connected: " + addr2 + ":" + natPort2);

            DataInputStream in1 = new DataInputStream(peer1.getInputStream());
            DataInputStream in2 = new DataInputStream(peer2.getInputStream());
            DataOutputStream out1 = new DataOutputStream(peer1.getOutputStream());
            DataOutputStream out2 = new DataOutputStream(peer2.getOutputStream());

            // Read port each peer claims to have forwarded (0=none)
            int listenPort1 = in1.readInt();
            int listenPort2 = in2.readInt();

            boolean pf1 = listenPort1 >= 1024 && listenPort1 <= 65535;
            boolean pf2 = listenPort2 >= 1024 && listenPort2 <= 65535;

            System.out.println("Peer 1: forwarded=" + (pf1 ? listenPort1 : "none") + " nat_port=" + natPort1);
            System.out.println("Peer 2: forwarded=" + (pf2 ? listenPort2 : "none") + " nat_port=" + natPort2);

            if (pf1) {
                // peer1
                out1.writeUTF("LISTEN");
                out1.writeInt(listenPort1);
                out1.writeUTF(addr2);
                out1.flush();

                // peer2
                out2.writeUTF("CONNECT");
                out2.writeInt(listenPort2);
                out2.writeUTF(addr1);
                out2.flush();

                System.out.println("Mode: PORT-FORWARD (Peer2 -> Peer1:" + listenPort1 + ")");
                peer1.close();
                peer2.close();
            }
            else if (pf2) {
                // peer1
                out1.writeUTF("CONNECT");
                out1.writeInt(listenPort1);
                out1.writeUTF(addr2);
                out1.flush();

                // peer2
                out2.writeUTF("LISTEN");
                out2.writeInt(listenPort2);
                out2.writeUTF(addr1);
                out2.flush();

                System.out.println("Mode: PORT-FORWARD (Peer1 -> Peer2:" + listenPort2 + ")");
                peer1.close();
                peer2.close();
            }
            else if (!addr2.equals(addr2)) {
                // They are not in the same NAT
                //
                // Tell each peer:
                //      - the other's external IP:Port (as we could observe NAT's mapping)
                //      - their OWN external port, so they know which local port to rebind
                //
                // Crucially, we flush both at the time so both peers start
                // their simultaneous SYN exchange at roughly the same moment
                out1.writeUTF("PUNCH");
                out1.writeUTF(addr2);       // peer2 exteral IP
                out1.writeInt(natPort2);    // peer2 external port (true NAT mapping)
                out1.writeInt(natPort1);    // peer1's OWN external port

                out2.writeUTF("PUNCH");
                out2.writeUTF(addr1);
                out2.writeInt(natPort1);
                out2.writeInt(natPort2);

                // Flush both simultaneously - timing matters for hole punching
                out1.flush();
                out2.flush();

                System.out.println("Mode: HOLE-PUNCH ("
                    + addr1 + ":" + natPort1
                    + " <-> "
                    + addr2 + ":" + natPort2 + ")");

                // close Rendezvous connections - peers are alone now
                peer1.close();
                peer2.close();
            }
            else {
                // Same public IP = LAN, or symmatric NAT -> use RELAY
                // Neither can listen - RELAY mode
                out1.writeUTF("RELAY");
                out1.flush();
                out2.writeUTF("RELAY");
                out2.flush();

                System.out.println("Mode: RELAY (same IP or symmatric NAT)");

                // Start relay threads
                Thread t1 = new Thread(() -> relay(peer1, peer2, "Peer1->Peer2"));
                Thread t2 = new Thread(() -> relay(peer2, peer1, "Peer2->Peer1"));
                t1.start();
                t2.start();
            }
        }
    }

    private static void relay(Socket from, Socket to, String name) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to .getOutputStream();
            byte[] buffer = new byte[8192]; // 8 * 1024
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        }
        catch (IOException e) {
            System.out.println(name + " relay ended");
        }
        finally {
            try { from.close(); } catch (Exception e) {}
            try { to.close(); } catch (Exception e) {}
        }
    }
}
