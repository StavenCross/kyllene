import java.awt.EventQueue;
import java.awt.Frame;

/** Disposable AWT process: the JNI probe queries this JVM's AppKit application. */
public class AppearanceProbe {
    private static native String inspect();
    private static native void showPanel(String folder);

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Frame frame = new Frame("Kyllene native appearance qualification");
        try {
            EventQueue.invokeAndWait(() -> {
                frame.setSize(420, 180);
                frame.setVisible(true);
            });
            System.out.println(inspect());
            System.out.flush();
            if (args.length > 1) showPanel(args[1]);
        } finally {
            EventQueue.invokeAndWait(frame::dispose);
        }
        System.exit(0);
    }
}
