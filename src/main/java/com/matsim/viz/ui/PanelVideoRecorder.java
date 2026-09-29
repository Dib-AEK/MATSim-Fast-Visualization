package com.matsim.viz.ui;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PanelVideoRecorder {

    public enum Quality {
        PRESENTATION_4K("Presentation 4K / 15 fps", 3840, 2160, 15),
        VIEWPORT_SYNC("Viewport native (app sync)", 0, 0, 0),
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
        public boolean isViewportNative() { return width <= 0 || height <= 0; }

        @Override
        public String toString() {
            if (isViewportNative()) {
                return label + " (source resolution, exact app frames)";
            }
            return label + " (" + width + "x" + height + ", " + fps + " fps)";
        }
    }

    private final Path outputDir;
    private final Object stateLock = new Object();
    private final ExecutorService encodingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "video-encoder");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean recording;
    private volatile boolean encoding;
    private volatile Path currentFile;
    private volatile Quality quality;
    private volatile long frameCount;
    private volatile long lastCaptureNanos;
    private volatile long frameIntervalNanos;
    private volatile int recordingFps;
    private volatile int targetWidth;
    private volatile int targetHeight;
    private BufferedImage captureBuffer;
    private Path frameDirectory;
    private volatile IOException captureFailure;
    private List<Path> queuedFrames = new ArrayList<>();

    public PanelVideoRecorder(Path outputDir) {
        this.outputDir = outputDir.toAbsolutePath().normalize();
    }

    public synchronized void start(Quality quality) throws IOException {
        synchronized (stateLock) {
            if (recording) {
                throw new IllegalStateException("Already recording");
            }
            if (encoding) {
                throw new IllegalStateException("Encoding is still in progress");
            }

            this.quality = quality;
            this.frameCount = 0;
            this.recordingFps = quality.isViewportNative()
                    ? detectDisplayRefreshRate()
                    : Math.max(1, quality.fps());
            this.frameIntervalNanos = quality.isViewportNative() || quality == Quality.PRESENTATION_4K
                    ? 0L
                    : 1_000_000_000L / recordingFps;
            this.lastCaptureNanos = 0;
            this.targetWidth = -1;
            this.targetHeight = -1;
            this.captureBuffer = null;
            this.captureFailure = null;
            this.queuedFrames = new ArrayList<>(4096);
        }

        Files.createDirectories(outputDir);
        frameDirectory = Files.createTempDirectory(outputDir, "recording-frames-");
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String qualityTag = quality.name().toLowerCase();
        currentFile = outputDir.resolve("capture_" + timestamp + "_" + qualityTag + "_h264.mp4");

        synchronized (stateLock) {
            recording = true;
        }
    }

    public void captureFrame(NetworkPanel panel) {
        if (!recording) {
            return;
        }

        long now = System.nanoTime();
        synchronized (stateLock) {
            if (!recording) {
                return;
            }
            if (frameIntervalNanos > 0 && lastCaptureNanos != 0 && (now - lastCaptureNanos) < frameIntervalNanos) {
                return;
            }
            lastCaptureNanos = now;
        }

        int pw = panel.getWidth();
        int ph = panel.getHeight();
        if (pw <= 0 || ph <= 0) {
            return;
        }

        // Serialize capture with stop: a frame is either fully saved or not enqueued.
        synchronized (stateLock) {
            if (!recording || captureFailure != null) return;
            try {
                ensureCaptureBuffer(pw, ph);
                Graphics2D graphics = captureBuffer.createGraphics();
                try {
                    panel.paintRecordingFrame(graphics, targetWidth, targetHeight);
                } finally {
                    graphics.dispose();
                }
                Path frame = frameDirectory.resolve(String.format("frame-%08d.png", frameCount));
                if (!ImageIO.write(captureBuffer, "png", frame.toFile())) {
                    throw new IOException("No PNG writer is available");
                }
                queuedFrames.add(frame);
                frameCount = queuedFrames.size();
            } catch (IOException ex) {
                captureFailure = ex;
            }
        }
    }

    public boolean isPresentationRecording() {
        return recording && quality == Quality.PRESENTATION_4K;
    }

    public double presentationFrameSeconds() {
        return 1.0 / Quality.PRESENTATION_4K.fps();
    }

    public CompletableFuture<Path> stopAsync() {
        final Path outputPath;
        final int fps;
        final List<Path> framesToEncode;
        final Path spool;
        final IOException failure;

        synchronized (stateLock) {
            if (!recording) {
                return CompletableFuture.completedFuture(null);
            }
            recording = false;
            encoding = true;
            outputPath = currentFile;
            fps = recordingFps;
            framesToEncode = queuedFrames;
            spool = frameDirectory;
            failure = captureFailure;
            queuedFrames = new ArrayList<>();

            captureBuffer = null;

        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (failure != null) {
                    throw new IOException("Capture failed. Completed PNG frames are preserved in " + spool, failure);
                }
                if (framesToEncode.isEmpty()) {
                    Files.deleteIfExists(spool);
                    return null;
                }
                encodeQueuedFrames(outputPath, fps, framesToEncode);
                // Delete only files created by this recording, after a successful encode.
                for (Path frame : framesToEncode) Files.deleteIfExists(frame);
                Files.deleteIfExists(spool);
                System.out.printf("Video saved: %s (%d frames, %d fps, H.264 MP4)\n",
                        outputPath,
                        framesToEncode.size(),
                        fps);
                return outputPath;
            } catch (IOException ex) {
                throw new CompletionException(new IOException("Video export failed; source frames are in " + spool, ex));
            } finally {
                synchronized (stateLock) {
                    encoding = false;
                    frameCount = 0;
                }
            }
        }, encodingExecutor);
    }

    public Path stop() throws IOException {
        try {
            return stopAsync().get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while encoding video", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Failed to encode video", cause);
        }
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isEncoding() {
        return encoding;
    }

    public long frameCount() {
        return frameCount;
    }

    public Path currentFile() {
        return currentFile;
    }

    public int queuedFrameCount() {
        synchronized (stateLock) {
            return queuedFrames == null ? 0 : queuedFrames.size();
        }
    }

    private void ensureCaptureBuffer(int panelWidth, int panelHeight) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            if (quality != null && quality.isViewportNative()) {
                targetWidth = evenDimension(panelWidth);
                targetHeight = evenDimension(panelHeight);
            } else {
                targetWidth = quality == null ? 1280 : quality.width();
                targetHeight = quality == null ? 720 : quality.height();
            }
        }

        if (captureBuffer != null
                && captureBuffer.getWidth() == targetWidth
                && captureBuffer.getHeight() == targetHeight) {
            return;
        }

        captureBuffer = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    }

    private static int evenDimension(int value) {
        int clamped = Math.max(2, value);
        return (clamped & 1) == 0 ? clamped : clamped - 1;
    }

    private static void encodeQueuedFrames(Path outputPath, int fps, List<Path> frames) throws IOException {
        H264Mp4Encoder.encode(outputPath, fps, frames);
    }

    private static int detectDisplayRefreshRate() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice device = ge.getDefaultScreenDevice();
            DisplayMode mode = device.getDisplayMode();
            int rate = mode == null ? 0 : mode.getRefreshRate();
            if (rate == DisplayMode.REFRESH_RATE_UNKNOWN || rate <= 0) {
                return 60;
            }
            return Math.max(30, Math.min(240, rate));
        } catch (Throwable ignored) {
            return 60;
        }
    }
}
