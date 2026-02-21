import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;

import com.sun.tools.javap.SourceWriter;

public class Send implements Runnable {
    public static final byte TYPE_TEXT   = 0x01;
    public static final byte TYPE_F_META = 0x02;
    public static final byte TYPE_F_CHNK = 0x03;

    public final String name;

    private final DataOutputStream dOut;
    private final Scanner scanner;
    private final Crypt crypto;
    private volatile boolean running = true;

    public Send(Socket socket, Crypto crypto, Scanner sc) throws Exception {
        this.dOut = new DataOutputStream(socket.getOutputStream());
        this.scanner = sc;
        this.crypto = crypto;

        System.out.print("Your name: ");
        this.name = scanner.nextLine();

        // Send name to the other peer, before Thread starts
        // so Receive can reliably read it at the start of its `run()`
        System.out.println("Sending name '" + this.name + "' to the peer...");
        sendText(name);
    }

    @Override
    public void run() {
        try {
            while (running) {
                System.out.print(name + ": ");
                String message = scanner.nextLine();

                if (message.equalsIgnoreCase("/quit")) {
                    running = false;
                    break;
                }

                if (message.equalsIgnoreCase("/file")) {
                    System.out.print("Enter the filename: ");
                    String filename = scanner.nextLine().trim();
                    sendFile(filename);
                    continue;
                }

                sendText(message);
            }
        }
        catch (IOException e) {
            System.err.println("Send error: " + e.getMessage());
        }
        catch (Exception e) {
            System.err.println("Encrypt error: " + e.getMessage());
        }
    }

    private void sendText(String message) throws Exception {
        byte[] plaintext = message.getBytes("UTF-8");
        byte[] ciphertext= crypto.encrypt(prependType(TYPE_TEXT, plaintext));

        dOut.writeInt(ciphertext.length);
        dOut.write(ciphertext);
        dOut.flush();
    }

    private void sendFile(String filename) throws Exception {
        Path path = Paths.get(filename);
        if (!Files.exists(path)) {
            System.err.println("File not found: " + filename);
            return;
        }

        long filesize = Files.size(path);
        String fname = path.getFileName().toString();
        byte[] namebytes = fname.getBytes("UTF-8");

        // Payload: [namelength(4B)][name][filesize]
        //          [   int        ][name][ long   ]
        ByteBuffer payload = ByteBuffer.allocate(4 + namebytes.length + 8);
        payload.putInt(namebytes.length);
        payload.put(namebytes);
        payload.putLong(filesize);

        byte[] ciphertext = crypto.encrypt(prependType(TYPE_F_META, payload.array()));
        dOut.writeInt(ciphertext.length);
        dOut.write(ciphertext);
        dOut.flush();

        // Stream file chunks
        int chunksize = 1024 * 1024; // 1MB
        byte[] buffer = new byte[chunksize];
        long bytesSent = 0;

        System.out.println("Uploading '" + fname + "'...");

        Instant start = Instant.now();
        try (InputStream is = Files.newInputStream(path)) {
            int read;
            while ((read = is.read(buffer)) > 0) {
                byte[] chnkPayload;
                if (read == chnkSize) {
                    chnkPayload = buffer;
                }
                else {
                    chnkPayload = new byte[read];
                    System.arraycopy(buffer, 0, chnkPayload, 0, read);
                }

                byte[] chnkCipher = crypto.encrypt(prependType(TYPE_F_CHNK, chnkPayload));
                dOut.writeInt(chnkCipher.length);
                dOut.write(chnkCipher);

                bytesSent += read;
                Utils.printProgressBar(bytesSend, filesize);
            }
        }
        Instant end = Instant.now();
        long execTime = Duration.between(start, end).toMillis();

        dOut.flush();
        System.out.println("\n[Sent file: " + fname + " (" + fileSize + " B)]");
        if (execTime >= 10000) {
            double execTimeSeconds = execTime / 1000.0;
            if (execTimeSeconds >= 60.0) {
                System.out.printf("Time: %dm %.2fs\n", (int)(execTimeSeconds/60), execTimeSeconds%60);
            }
            else {
                System.out.printf("Time: %.2fs\n", execTimeSeconds);
            }
        }
        else {
            System.out.println("Time: " + execTime + " ms");
        }
    }

    private byte[] prependType(byte type, byte[] data) {
        byte[] result = new byte[1 + data.length];
        result[0] = type;
        System.arraycopy(data, 0, result, 1, data.length);

        return result;
    }

    public void stop() {running=false;}
}
