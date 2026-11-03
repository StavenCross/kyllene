import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.awt.image.BufferedImage;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Observe actual Kyllene's focused welcome field; never submit an address or contact a gateway. */
public class ApplicationProbe {
    private static Path output;
    private static Component target;
    private static final Map<String, String> report = new LinkedHashMap<>();

    public static void premain(String arguments, Instrumentation instrumentation) {
        String[] parts = arguments.split("\\|", 2);
        System.load(parts[0]);
        output = Path.of(parts[1]);
        Thread worker = new Thread(ApplicationProbe::observe, "mac-appearance-observer");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean ready(boolean activate) throws Exception {
        if (activate) InputSource.command("activate");
        final boolean[] accepted = {false};
        EventQueue.invokeAndWait(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            report.put("lastFocus", focus == null ? "none" : focus.getClass().getName());
            if (focus == null) return;
            Window window = javax.swing.SwingUtilities.getWindowAncestor(focus);
            report.put("lastWindow", window == null ? "none" : window.getClass().getName() + ":" + window.isActive() + ":" + window.isFocused());
            if (!(window instanceof Frame frame) || !frame.getTitle().equals("Kyllene") ||
                !window.getClass().getName().contains("ComposeWindow") || !window.isActive() || !window.isFocused()) return;
            String name = focus.getClass().getName();
            if (!name.startsWith("androidx.compose.") && !name.startsWith("org.jetbrains.skiko.")) return;
            if (target != null && target != focus) return;
            InputMethodRequests input = focus.getInputMethodRequests();
            report.put("inputRequests", input == null ? "none" : input.getClass().getName());
            if (input == null) return;
            try {
                Rectangle caret = input.getTextLocation(TextHitInfo.leading(0));
                if (caret.height <= 0 || !window.getBounds().contains(caret.x, caret.y)) return;
                report.put("focusClass", name);
                report.put("window", frame.getTitle());
                report.put("caret", caret.toString());
                report.put("committedCharacters", String.valueOf(input.getCommittedTextLength()));
                target = focus;
                accepted[0] = true;
            } catch (RuntimeException transientLayout) { report.put("lastLayoutError", transientLayout.toString()); }
        });
        return accepted[0] && InputSource.command("selected").contains("\"active\":true");
    }

    private static void capture(String name) throws Exception {
        Robot robot = new Robot();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        BufferedImage image = robot.createScreenCapture(screen);
        ImageIO.write(image, "jpg", output.resolve(name + ".jpg").toFile());
    }

    private static void observe() {
        try {
            Files.createDirectories(output);
            long deadline = System.nanoTime() + 40_000_000_000L;
            while (!ready(true) && System.nanoTime() < deadline) Thread.sleep(200);
            boolean focused = ready(false);
            report.put("nativeAppearance", AppearanceProbe.inspect());
            if (!focused) {
                report.put("indicator", "unverified: no active Kyllene Compose text input within 40 seconds; no input posted");
                String state = InputSource.command("selected");
                report.put("preflight", state);
                if (state.contains("\"screenCapture\":true")) capture("application");
                return;
            }
            String pair = InputSource.command("pair");
            report.put("inputPair", pair);
            if (!pair.contains("\"errors\":[]")) throw new IllegalStateException(pair);
            String permissions = InputSource.command("selected");
            report.put("preflight", permissions);
            if (!permissions.contains("\"errors\":[]")) throw new IllegalStateException(permissions);
            if (!permissions.contains("\"selectedAfter\":\"com.apple.keylayout.US\"")) throw new IllegalStateException("Controller did not prepare US layout");
            if (!permissions.contains("\"screenCapture\":true")) {
                report.put("indicator", "unverified: runner denies screen capture");
                return;
            }
            capture("application");
            boolean canPost = permissions.contains("\"postEvents\":true");
            Robot robot = new Robot();
            if (canPost) {
                if (!ready(false)) throw new IllegalStateException("Kyllene focus changed before typing");
                robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A);
                Thread.sleep(200);
                if (!ready(false)) throw new IllegalStateException("Kyllene focus changed before shortcut");
                robot.keyPress(KeyEvent.VK_CONTROL);
                try {
                    robot.keyPress(KeyEvent.VK_SPACE); robot.keyRelease(KeyEvent.VK_SPACE);
                    Thread.sleep(350);
                    capture("switch-held");
                } finally { robot.keyRelease(KeyEvent.VK_CONTROL); }
            } else {
                report.put("postedInput", "unavailable: runner denies posted events; testing direct source selection only");
                String selection = InputSource.command("bulgarian");
                report.put("directSelection", selection);
                if (!selection.contains("\"errors\":[]")) throw new IllegalStateException(selection);
            }
            for (int i = 0; i < 6; i++) {
                capture("switch-" + i);
                Thread.sleep(200);
            }
            String selected = InputSource.command("selected");
            report.put("selectedAfterSwitch", selected);
            if (!selected.contains("\"errors\":[]")) throw new IllegalStateException(selected);
            if (!selected.contains("\"selectedAfter\":\"com.apple.keylayout.Bulgarian\"")) {
                report.put("indicator", "unverified: requested source did not switch via shortcut");
                return;
            }
            if (canPost && ready(false)) {
                robot.keyPress(KeyEvent.VK_B); robot.keyRelease(KeyEvent.VK_B);
                capture("after-typing");
            }
            report.put("indicator", "captured-unreviewed: source switch confirmed; require visibly present accessory");
        } catch (Throwable error) {
            report.put("error", error.toString());
            error.printStackTrace();
        } finally {
            try {
                StringBuilder text = new StringBuilder();
                report.forEach((key, value) -> text.append(key).append("=").append(value).append('\n'));
                Files.writeString(output.resolve("application-report.txt"), text);
            } catch (Throwable error) { error.printStackTrace(); }
            // The controlling process independently restores keyboard state even if this JVM is killed.
            System.exit(0);
        }
    }
}
