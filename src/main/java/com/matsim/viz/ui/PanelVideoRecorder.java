package com.matsim.viz.ui;

import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Rational;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PanelVideoRecorder {

    public enum Quality {
        MEDIUM("720p 30fps", 1280, 720, 30),
        HIGH("1080p 30fps", 1920, 1080, 30),
        HIGH_60("1080p 60fps", 1920, 1080, 60),
        QHD("1440p 30fps", 2560, 1440, 30),
        QHD_60("1440p 60fps", 2560, 1440, 60),
        UHD("4K 30fps", 3840, 2160, 30),
        UHD_60("4K 60fps", 3840, 2160, 60);

        private final String label;
        private final int width;
        private final int height;
        private final int fps;

        Quality(String label, int width, int height, int fps) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        public String label() { return label; }
        public int width() { return width; }
        public int height() { return height; }
        public int fps() { return fps; }

        @Override
        public String toString() {
            return label + " (" + width + "x" + height + ", " + fps + " fps)";
        }
    }

    private final Path outputDir;
    private volatile AWTSequenceEncoder encoder;
    private volatile SeekableByteChannel channel;
    private volatile boolean recording;
    private volatile Path currentFile;
    private volatile Quality quality;
    private volatile long frameCount;
    private volatile long lastCaptureNanos;
    private volatile long frameIntervalNanos;
    private BufferedImage captureBuffer;

    public PanelVideoRecorder(Path outputDir) {
        this.outputDir = outputDir.toAbsolutePath().normalize();
    }

    public synchronized void start(Quality quality) throws IOException {
        if (recording) {
            throw new IllegalStateException("Already recording");
        }

        this.quality = quality;
        this.frameCount = 0;
        this.frameIntervalNanos = 1_000_000_000L / quality.fps();
        this.lastCaptureNanos = 0;

        Files.createDirectories(outputDir);
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        currentFile = outputDir.resolve("recording_" + timestamp + "_" + quality.label() + ".mp4");

        channel = NIOUtils.writableFileChannel(currentFile.toString());
        encoder = new AWTSequenceEncoder(channel, Rational.R(quality.fps(), 1));
        captureBuffer = new BufferedImage(quality.width(), quality.height(), BufferedImage.TYPE_3BYTE_BGR);
        recording = true;
    }

    public void captureFrame(NetworkPanel panel) {
        if (!recording || encoder == null) {
            return;
        }

        long now = System.nanoTime();
        if (lastCaptureNanos != 0 && (now - lastCaptureNanos) < frameIntervalNanos) {
            return;
        }
        lastCaptureNanos = now;

        int pw = panel.getWidth();
        int ph = panel.getHeight();
        if (pw <= 0 || ph <= 0) {
            return;
        }

        panel.setSuppressOverlays(true);
        BufferedImage source = new BufferedImage(pw, ph, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D sg = source.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        panel.paint(sg);
        sg.dispose();
        panel.setSuppressOverlays(false);

        Graphics2D g2 = captureBuffer.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(source, 0, 0, quality.width(), quality.height(), null);
        g2.dispose();

        try {
            encoder.encodeImage(captureBuffer);
            frameCount++;
        } catch (IOException ex) {
            System.err.println("Warning: failed to encode frame: " + ex.getMessage());
        }
    }

    public synchronized Path stop() throws IOException {
        if (!recording) {
            return null;
        }

        recording = false;
        Path result = currentFile;

        try {
            if (encoder != null) {
                encoder.finish();
            }
        } finally {
            NIOUtils.closeQuietly(channel);
            encoder = null;
            channel = null;
            captureBuffer = null;
        }

        System.out.printf("Video saved: %s (%d frames)%n", result, frameCount);
        return result;
    }

    public boolean isRecording() {
        return recording;
    }

    public long frameCount() {
        return frameCount;
    }

    public Path currentFile() {
        return currentFile;
    }
}
