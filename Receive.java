import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Receive implements Runnable {
    private String peerName = "Peer"; // fallback name
    private final String localName;
    private final DataInputStream dIn;
    private final Crypto crypto;
    private volatile boolean running = true;

    private OutputStream fOut;
    private long expectedFileSize;
    private long currentFileBytesReceived;

    // Directory where received files are saved
    private static final String DOWNLOAD_DIR = "received_files";

    public Receive(Socket socket, Crypto crypto, String localName) throws IOException {
        this.dIn = new DataInputStream(socket.getInputStream());
        this.crypto = crypto;
        this.localName = localName;
        // Ensure download directory exists
        Files.createDirectories(Paths.get(DOWNLOAD_DIR));
    }

    @Override
    public void run() {
        // First message is the peer's name
        // Send.java sends it before thread runs

        int length;
        byte[] encrypted; // to store encrypted message
        byte[] plaintext; // to store decrypted message
        byte[] payload;   // to store typebyte stripped message

        try {
            length = dIn.readInt();
            encrypted = new byte[length];
            dIn.readFully(encrypted);

            plaintext = crypto.decrypt(encrypted);
            // First byte = TYPE_TEXT 0x01
            payload = new byte[plaintext.length - 1];
            System.arraycopy(plaintext, 1, payload, 0, payload.length);

            peerName = new String(payload, "UTF-8");
            LogUtils.info("[" + peerName + " joined the chat]");
            System.out.print(localName + ": ");
        }
        catch (Exception e) {
            LogUtils.error("Failed to read Peer's name: "+ e.getMessage());
        }

        try {
            while (running) {
                length = dIn.readInt();
                encrypted = new byte[length];
                dIn.readFully(encrypted);

                plaintext = crypto.decrypt(encrypted);

                byte type = plaintext[0];
                payload = new byte[plaintext.length - 1];
                System.arraycopy(plaintext, 1, payload, 0, payload.length);

                if (type == Send.TYPE_TEXT) {
                    handleText(payload);
                }
                else if (type == Send.TYPE_F_META) {
                    handleFileMeta(payload);
                }
                else if (type == Send.TYPE_F_CHNK) {
                    handleFileChunk(payload);
                }
                else {
                    LogUtils.error("[Unknown TYPE: "+type+"]");
                }
            }
        }
        catch (IOException ioe) {
            System.err.println("Connecion closed");
        }
        catch (Exception e) {
            System.err.println("Decrypt error: " + e.getMessage());
        }
    }

    private void handleText(byte[] payload) throws Exception {
        String message = new String(payload, "UTF-8");
        System.out.println("\r" + peerName + ": " + message);
        System.out.print(localName + ": ");
    }

    private void handleFileMeta(byte[] payload) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        int nameLength = buffer.getInt();
        byte[] nameBytes = new byte[nameLength];
        buffer.get(nameBytes);
        String currentFileName = new String(nameBytes, "UTF-8");

        expectedFileSize = buffer.getLong();
        currentFileBytesReceived = 0;

        String safeName = Paths.get(currentFileName).getFileName().toString();
        Path savePath = resolveUnique(Paths.get(DOWNLOAD_DIR, safeName));

        fOut = Files.newOutputStream(savePath);

        if (expectedFileSize >= 1024) {
            double expectedFileSizeKB = expectedFileSize * 1.0 / 1024.0;

            if (expectedFileSizeKB >= 1024) {
                System.out.printf("\r[Receiving file: %s (%.2f MB)]\n", safeName, expectedFileSizeKB * 1.0 / 1024.0);
            }
            else {
                System.out.printf("\r[Receiving file: %s (%.2f KB)]\n", safeName, expectedFileSizeKB);
            }
        }
        else {
            System.out.print("\r[Receiving file: " + safeName + " (" + expectedFileSize + " B)]\n");
        }
    }

    private void handleFileChunk(byte[] payload) throws Exception {
        if (fOut == null) return;

        fOut.write(payload);
        currentFileBytesReceived += payload.length;

        LogUtils.printProgressBar(currentFileBytesReceived, expectedFileSize);

        if (currentFileBytesReceived >= expectedFileSize) {
            fOut.close();
            fOut = null;
            System.out.println();
            LogUtils.info("[File received fully]");
            System.out.print(localName + ": ");
        }
    }

    /**
     * If the target path already exists, appends '(1)', '(2)', etc.
     * To avoid overwriting
     */
    private Path resolveUnique(Path path) {
        if (!Files.exists(path)) return path; // if no exist, then just write

        String name = path.getFileName().toString();
        Path parent = path.getParent();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name; // base name is till last dot
        String ext  = dot >= 0 ? name.substring(dot)    : ""; // extension is after last dot

        // count until not existing name found
        int count = 1;
        Path candidate;
        do {
            candidate = parent.resolve(base + " (" + count + ")" + ext);
        } while (Files.exists(candidate));

        return candidate;
    }

    public void stop() {running=false;}
}
