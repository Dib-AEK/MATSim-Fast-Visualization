package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.Codec;
import org.jcodec.scale.AWTUtil;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Checks actual MP4 decoding, timing, resolution, cache restoration and spool cleanup. */
public final class RecordingQualityCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path output = Files.createTempDirectory(Path.of("target"), "recording-check-");
        LinkSegment link = new LinkSegment("road", "a", "b", 10, 10, 90, 45,
                90, 15, 2, Set.of("car"), Map.of());
        SimulationModel model = new SimulationModel(new NetworkData(Map.of(), Map.of("road", link), 0, 0, 100, 56.25),
                new VehicleTraversal[]{new VehicleTraversal(0, "car", "road", 0, 100)},
                Map.of(), Map.of("car", "car"), Map.of(), Map.of(), Map.of(), null);
        PlaybackController playback = new PlaybackController(model, 0, 100, 30);
        playback.seek(40);
        PanelVideoRecorder recorder = new PanelVideoRecorder(output);
        BufferedImage reference = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        recorder.start(PanelVideoRecorder.Quality.PRESENTATION_4K);
        SwingUtilities.invokeAndWait(() -> {
            NetworkPanel panel = new NetworkPanel(model, playback);
            panel.setSize(960, 600);
            BufferedImage before = new BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D screen = before.createGraphics();
            panel.paint(screen);
            screen.dispose();
            recorder.captureFrame(panel);
            recorder.captureFrame(panel); // Presentation capture must not throttle/drop this frame.
            Graphics2D graphics = reference.createGraphics();
            panel.paintRecordingFrame(graphics, 3840, 2160);
            graphics.dispose();
            // Regression: the old recorder centered a 960px image on a 3840px black canvas.
            int background = panel.getBackground().getRGB();
            check(reference.getRGB(0, 1080) == background, "Aspect-ratio margin is not preserved");
            boolean roadOutsideOldImage = false;
            for (int y = 0; y < 2160 && !roadOutsideOldImage; y++) {
                for (int x = 0; x < 1400; x++) {
                    if (reference.getRGB(x, y) != background) { roadOutsideOldImage = true; break; }
                }
            }
            check(roadOutsideOldImage, "4K frame is still a small image inside a large canvas");
            BufferedImage after = new BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB);
            screen = after.createGraphics();
            panel.paint(screen);
            screen.dispose();
            check(Arrays.equals(before.getRGB(0, 0, 960, 600, null, 0, 960),
                    after.getRGB(0, 0, 960, 600, null, 0, 960)), "Export altered the live viewport");
        });
        check(recorder.frameCount() == 2, "Missing presentation frame");
        Path movie = recorder.stop();
        check(movie.toString().endsWith(".mp4"), "Incorrect file extension");
        try (var channel = NIOUtils.readableFileChannel(movie.toString());
             var decodeChannel = NIOUtils.readableFileChannel(movie.toString())) {
            FrameGrab grab = FrameGrab.createFrameGrab(decodeChannel);
            MP4Demuxer demuxer = MP4Demuxer.createRawMP4Demuxer(channel);
            var track = demuxer.getVideoTrack();
            check(track.getMeta().getCodec() == Codec.H264, "Video must use H.264");
            for (int i = 0; i < 2; i++) {
                var packet = track.nextFrame();
                check(packet != null, "Missing encoded frame");
                check(Math.abs(packet.getDurationD() - 1.0 / 15) < 1e-8, "Incorrect frame duration");
                var picture = grab.getNativeFrame();
                check(picture != null, "Cannot decode H.264 frame");
                BufferedImage decoded = AWTUtil.toBufferedImage(picture);
                check(decoded.getWidth() == 3840 && decoded.getHeight() == 2160, "Incorrect output resolution");
                double error = 0;
                long samples = 0;
                int background = reference.getRGB(0, 1080);
                for (int y = 0; y < 2160; y++) {
                    for (int x = 0; x < 3840; x++) {
                        int expected = reference.getRGB(x, y);
                        // Measure rendered features, not the large easy-to-compress background.
                        if (expected == background) continue;
                        int actual = decoded.getRGB(x, y);
                        for (int shift = 0; shift <= 16; shift += 8) {
                            int delta = ((expected >> shift) & 255) - ((actual >> shift) & 255);
                            error += delta * delta;
                            samples++;
                        }
                    }
                }
                double psnr = 10 * Math.log10(255.0 * 255 * samples / Math.max(1, error));
                check(samples > 0 && psnr > 30, "Rendered-feature quality too low: " + psnr + " dB");
                System.out.printf("Frame %d rendered-feature PSNR: %.2f dB%n", i, psnr);
            }
            check(track.nextFrame() == null, "Unexpected extra frames");
        }
        try (var children = Files.list(output)) {
            check(children.noneMatch(Files::isDirectory), "Successful export left frame spool behind");
        }
        recorder.start(PanelVideoRecorder.Quality.PRESENTATION_4K);
        check(recorder.stop() == null, "Empty recording should produce no movie");
        // Failed encoding must retain its PNG source for recovery.
        recorder.start(PanelVideoRecorder.Quality.MEDIUM);
        SwingUtilities.invokeAndWait(() -> {
            NetworkPanel panel = new NetworkPanel(model, playback);
            panel.setSize(320, 180);
            recorder.captureFrame(panel);
        });
        Files.createDirectory(recorder.currentFile()); // Force the output path to be unwritable as a file.
        boolean failed = false;
        try { recorder.stop(); } catch (java.io.IOException expected) { failed = true; }
        check(failed && !recorder.isEncoding(), "Encoding failure was not reported/reset");
        try (var files = Files.walk(output)) {
            check(files.anyMatch(path -> path.toString().endsWith(".png")), "Failed export lost source frames");
        }
        System.out.println("PASS: high-quality 4K H.264 MP4 decodes; 15 fps, no frame loss, live viewport unchanged. " + movie);
    }
}
