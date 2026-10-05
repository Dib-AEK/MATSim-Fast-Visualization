package com.matsim.viz.ui;

import com.matsim.viz.launcher.LaunchSettings;
import com.matsim.viz.launcher.PortableLauncher;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Desktop regression: exercise the actual Run button and child viewer, using synthetic inputs. */
public final class LauncherRunUiCheck {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "launcher-run-");
        LaunchSettings.write(home.resolve("launcher.properties"), LaunchSettings.read(Path.of(args[0])));
        System.setProperty("matsim.viz.userDir", home.toString());
        Object[] launcher = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                var constructor = PortableLauncher.class.getDeclaredConstructor();constructor.setAccessible(true);
                launcher[0] = constructor.newInstance();
                ((JFrame) field(launcher[0], "window")).setVisible(true);
                ((JButton) field(launcher[0], "run")).doClick();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        Process child = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            boolean[] ready = {false};
            while (System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        String log = ((JTextArea) field(launcher[0], "startupLog")).getText();
                        ready[0] = log.contains("[Startup] Reading network") && log.contains("[Startup] Viewer ready");
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                child = (Process) field(launcher[0], "process");
                if (ready[0]) {
                    if (child == null || !child.isAlive()) throw new AssertionError("Viewer exited");
                    System.out.println("PASS: Run launches viewer and displays live startup stages.");
                    break;
                }
                Thread.sleep(100);
            }
            if (!ready[0]) throw new AssertionError("Viewer did not start; logs: " + home);
        } finally {
            // Only terminate the test-owned viewer; it contains no user scenario or edits.
            if (child != null) { child.destroyForcibly();child.waitFor(); }
            SwingUtilities.invokeAndWait(() -> { for (var window : java.awt.Window.getWindows()) window.dispose(); });
        }
        System.exit(0);
    }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
}
