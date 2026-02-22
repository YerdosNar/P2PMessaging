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

public class Send implements Runnable {
    public static final byte TYPE_TEXT   = 0x01;
    public static final byte TYPE_F_META = 0x02;
    public static final byte TYPE_F_CHNK = 0x03;

    public final String name;

    private final DataOutputStream dOut;
    private final Scanner scanner;
    private final Crypto crypto;
    private volatile boolean running = true;

    public Send(Socket socket, Crypto crypto, Scanner scanner) throws Exception {
        this.dOut = new DataOutputStream(socket.getOutputStream());
        this.scanner = scanner;
        this.crypto = crypto;

        System.out.print("Your name: ");
        this.name = scanner.nextLine().trim();

        // Send name to the other peer, before Thread starts
        // so Receive can reliably read it at the start of its `run()`
        LogUtils.info("Sending name '" + this.name + "' to the peer...");
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
        byte[] ciphertext = crypto.encrypt(prependType(TYPE_TEXT, plaintext));

        dOut.writeInt(ciphertext.length);
        dOut.write(ciphertext);
        dOut.flush();
    }

    private void sendFile(String filename) throws Exception {
        Path path = Paths.get(filename);
        if (!Files.exists(path)) {
            LogUtils.error("File not found: " + filename);
            return;
        }

        long fileSize = Files.size(path);
        String fname = path.getFileName().toString();
        byte[] nameBytes = fname.getBytes("UTF-8");

        // Payload: [namelength(4B)][name][filesize]
        //          [   int        ][name][ long   ]
        ByteBuffer payload = ByteBuffer.allocate(4 + nameBytes.length + 8);
        payload.putInt(nameBytes.length);
        payload.put(nameBytes);
        payload.putLong(fileSize);

        byte[] ciphertext = crypto.encrypt(prependType(TYPE_F_META, payload.array()));
        dOut.writeInt(ciphertext.length);
        dOut.write(ciphertext);
        dOut.flush();

        // Stream file chunks
        int chunkSize = 1024 * 1024; // 1MB
        byte[] buffer = new byte[chunkSize];
        long bytesSent = 0;

        LogUtils.info("Uploading \033[1;36m" + fname + "\033[0m...");

        Instant start = Instant.now();
        try (InputStream fis = Files.newInputStream(path)) {
            int read;
            while ((read = fis.read(buffer)) > 0) {
                // Encrypt only read bytes
                byte[] chunkPayload;
                if (read == chunkSize) {
                    chunkPayload = buffer;
                }
                else {
                    chunkPayload = new byte[read];
                    System.arraycopy(buffer, 0, chunkPayload, 0, read);
                }

                byte[] chunkCipher = crypto.encrypt(prependType(TYPE_F_CHNK, chunkPayload));
                dOut.writeInt(chunkCipher.length);
                dOut.write(chunkCipher);

                bytesSent += read;
                LogUtils.printProgressBar(bytesSent, fileSize);
            }
        }
        Instant end = Instant.now();
        long execTime = Duration.between(start, end).toMillis();

        dOut.flush();
        if (fileSize >= 1024) {
            double fileSizeKB = fileSize * 1.0 / 1024.0;
            if (fileSizeKB >= 1024) {
                LogUtils.success("\n[Sent file: " + fname + " (" + (fileSizeKB / 1024.0) + " MB)]");
            }
            else {
                LogUtils.success("\n[Sent file: " + fname + " (" + fileSizeKB + " KB)]");
            }
        }
        else {
            LogUtils.success("\n[Sent file: " + fname + " (" + fileSize + " B)]");
        }
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
