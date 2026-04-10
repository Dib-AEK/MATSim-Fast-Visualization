package com.matsim.viz.ui;

import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.Codec;
import org.jcodec.common.Format;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Rational;
import org.jcodec.scale.AWTUtil;

import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
    private BufferedImage sourceBuffer;
    private int sourceBufferWidth;
    private int sourceBufferHeight;
    private List<BufferedImage> queuedFrames = new ArrayList<>();

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
            this.frameIntervalNanos = quality.isViewportNative()
                    ? 0L
                    : 1_000_000_000L / recordingFps;
            this.lastCaptureNanos = 0;
            this.targetWidth = -1;
            this.targetHeight = -1;
            this.captureBuffer = null;
            this.sourceBuffer = null;
            this.sourceBufferWidth = -1;
            this.sourceBufferHeight = -1;
            this.queuedFrames = new ArrayList<>(4096);
        }

        Files.createDirectories(outputDir);
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String qualityTag = quality.name().toLowerCase();
        currentFile = outputDir.resolve("capture_" + timestamp + "_" + qualityTag + "_lossless.mov");

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

        ensureSourceBuffer(pw, ph);
        ensureCaptureBuffer(pw, ph);

        Graphics2D sourceGraphics = sourceBuffer.createGraphics();
        sourceGraphics.setColor(panel.getBackground());
        sourceGraphics.fillRect(0, 0, pw, ph);
        panel.paintRecordingFrame(sourceGraphics);
        sourceGraphics.dispose();

        renderScaledWithAspectPreserved(sourceBuffer, captureBuffer);

        try {
            BufferedImage queuedFrame = deepCopyRgb(captureBuffer);
            synchronized (stateLock) {
                if (!recording) {
                    queuedFrame.flush();
                    return;
                }
                queuedFrames.add(queuedFrame);
                frameCount = queuedFrames.size();
            }
        } catch (OutOfMemoryError oom) {
            synchronized (stateLock) {
                recording = false;
            }
            System.err.println("Recording stopped: ran out of memory while queuing frames.");
        }
    }

    public CompletableFuture<Path> stopAsync() {
        final Path outputPath;
        final int fps;
        final List<BufferedImage> framesToEncode;

        synchronized (stateLock) {
            if (!recording) {
                return CompletableFuture.completedFuture(null);
            }
            recording = false;
            encoding = true;
            outputPath = currentFile;
            fps = recordingFps;
            framesToEncode = queuedFrames;
            queuedFrames = new ArrayList<>();

            captureBuffer = null;
            sourceBuffer = null;
            sourceBufferWidth = -1;
            sourceBufferHeight = -1;
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (framesToEncode.isEmpty()) {
                    return null;
                }
                encodeQueuedFrames(outputPath, fps, framesToEncode);
                System.out.printf("Video saved: %s (%d frames, %d fps, Lossless PNG MOV)\n",
                        outputPath,
                        framesToEncode.size(),
                        fps);
                return outputPath;
            } catch (IOException ex) {
                throw new CompletionException(ex);
            } finally {
                for (BufferedImage frame : framesToEncode) {
                    frame.flush();
                }
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

    private void ensureSourceBuffer(int width, int height) {
        if (sourceBuffer != null && sourceBufferWidth == width && sourceBufferHeight == height) {
            return;
        }
        sourceBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        sourceBufferWidth = width;
        sourceBufferHeight = height;
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

    private static void renderScaledWithAspectPreserved(BufferedImage source, BufferedImage target) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        int dstW = target.getWidth();
        int dstH = target.getHeight();

        Graphics2D g2 = target.createGraphics();
        g2.setColor(java.awt.Color.BLACK);
        g2.fillRect(0, 0, dstW, dstH);

        double scale = Math.min((double) dstW / srcW, (double) dstH / srcH);
        // Do not upscale the recorded panel image; this preserves sharpness.
        scale = Math.min(1.0, scale);

        int drawW = Math.max(1, (int) Math.round(srcW * scale));
        int drawH = Math.max(1, (int) Math.round(srcH * scale));
        int drawX = (dstW - drawW) / 2;
        int drawY = (dstH - drawH) / 2;

        if (drawW == srcW && drawH == srcH) {
            g2.drawImage(source, drawX, drawY, null);
            g2.dispose();
            return;
        }

        BufferedImage scaled = downscaleProgressive(source, drawW, drawH);
        g2.drawImage(scaled, drawX, drawY, null);
        g2.dispose();
    }

    private static BufferedImage downscaleProgressive(BufferedImage source, int targetW, int targetH) {
        int currentW = source.getWidth();
        int currentH = source.getHeight();
        BufferedImage currentImage = source;

        while (currentW / 2 >= targetW && currentH / 2 >= targetH) {
            int nextW = Math.max(targetW, currentW / 2);
            int nextH = Math.max(targetH, currentH / 2);
            BufferedImage nextImage = new BufferedImage(nextW, nextH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = nextImage.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(currentImage, 0, 0, nextW, nextH, null);
            g.dispose();

            if (currentImage != source) {
                currentImage.flush();
            }
            currentImage = nextImage;
            currentW = nextW;
            currentH = nextH;
        }

        if (currentW == targetW && currentH == targetH) {
            return currentImage;
        }

        BufferedImage finalImage = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = finalImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(currentImage, 0, 0, targetW, targetH, null);
        g.dispose();

        if (currentImage != source) {
            currentImage.flush();
        }
        return finalImage;
    }

    private static int evenDimension(int value) {
        int clamped = Math.max(2, value);
        return (clamped & 1) == 0 ? clamped : clamped - 1;
    }

    private static BufferedImage deepCopyRgb(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static void encodeQueuedFrames(Path outputPath, int fps, List<BufferedImage> frames) throws IOException {
        SeekableByteChannel encodeChannel = null;
        SequenceEncoder sequenceEncoder = null;
        try {
            encodeChannel = NIOUtils.writableFileChannel(outputPath.toString());
            sequenceEncoder = new SequenceEncoder(encodeChannel, Rational.R(fps, 1), Format.MOV, Codec.PNG, null);
            for (BufferedImage frame : frames) {
                sequenceEncoder.encodeNativeFrame(AWTUtil.fromBufferedImageRGB(frame));
            }
            sequenceEncoder.finish();
        } finally {
            NIOUtils.closeQuietly(encodeChannel);
        }
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
