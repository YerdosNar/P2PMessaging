import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LogUtils {
    private static int cachedConsoleWidth = -1;

    private static OS getOS() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return OS.WINDOWS;
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("mac")) {
            return OS.UNIX; // Covers Linux, macOS, BSD
        } else {
            return OS.UNKNOWN;
        }
    }

    enum OS { WINDOWS, UNIX, UNKNOWN }

    private static int getUnixConsoleWidth() throws IOException {
        Process process = new ProcessBuilder("tput", "cols")
        .redirectErrorStream(true) // Merge error stream to input stream
        .start();

        try (BufferedReader reader = new BufferedReader( new InputStreamReader(process.getInputStream()))) {
            String output = reader.readLine();
            if (output == null || output.trim().isEmpty()) {
                return -1; // Command failed
            }
            return Integer.parseInt(output.trim());
        } finally {
            process.destroy();
        }
    }

    private static int getWindowsConsoleWidth() throws IOException {
        Process process = new ProcessBuilder("cmd", "/c", "mode con")
        .redirectErrorStream(true)
        .start();

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("Columns:")) {
                    String[] parts = line.split(":", 2);
                    return Integer.parseInt(parts[1].trim());
                }
            }
            return -1; // Columns not found
        } finally {
            process.destroy();
        }
    }

    public static int detectConsoleWidth() {
        if (cachedConsoleWidth > 0) {
            return cachedConsoleWidth;
        }

        OS os = getOS();
        try {
            switch (os) {
                case WINDOWS:
                    cachedConsoleWidth = getWindowsConsoleWidth();
                case UNIX:
                    cachedConsoleWidth = getUnixConsoleWidth();
                default:
                    cachedConsoleWidth = 46; // Unknown OS fallbakc = 46
            }
        } catch (IOException | NumberFormatException e) {
            cachedConsoleWidth = 46; // Command failed or parsing error
        }

        return cachedConsoleWidth;
    }

    public static void printProgressBar(long current, long total) {
        int barLength = detectConsoleWidth() - 16; // "Progress: ".length+[]+100% = 16 chars
        int percent = (int)(((long) current * 100) / total);
        int filled =  (int)(((long) current * barLength) / total);

        StringBuilder bar = new StringBuilder("\rProgress: [");
        for(int i = 0; i < barLength; i++) {
            if(i < filled) bar.append("=");
            else if(i == filled) bar.append(">");
            else bar.append(" ");
        }
        bar.append("] ").append(percent).append("%");
        System.out.print(bar.toString());
    }

    public static void info(String message) {
        System.out.println("\033[34m[i]\033[0m " + message);
    }

    public static void warn(String message) {
        System.out.println("\033[33m[!]\033[0m " + message);
    }

    public static void success(String message) {
        System.out.println("\033[32m[✓]\033[0m " + message);
    }

    public static void error(String message) {
        System.err.println("\033[31m[x]\033[0m " + message);
    }
}
