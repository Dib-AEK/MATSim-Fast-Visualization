package com.matsim.viz.ui;

import com.matsim.viz.config.AppDefaults;
import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PanelVideoRecorder {

    public enum Quality {
        PRESENTATION_4K(AppDefaults.Recording.PRESENTATION_4K),
        VIEWPORT_SYNC(AppDefaults.Recording.VIEWPORT_SYNC),
        MEDIUM(AppDefaults.Recording.MEDIUM),
        HIGH(AppDefaults.Recording.HIGH),
        HIGH_60(AppDefaults.Recording.HIGH_60),
        QHD(AppDefaults.Recording.QHD),
        QHD_60(AppDefaults.Recording.QHD_60),
        UHD(AppDefaults.Recording.UHD),
        UHD_60(AppDefaults.Recording.UHD_60);

        private final String label;
        private final int width;
        private final int height;
        private final int fps;

        Quality(AppDefaults.VideoPreset preset) {
            this.label = preset.label();
            this.width = preset.width();
            this.height = preset.height();
            this.fps = preset.fps();
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
    private volatile int encodedFrames;
    private volatile String encoderName = "";
    private volatile Path currentFile;
    private volatile Quality quality;
    private volatile long frameCount;
    private volatile long lastCaptureNanos;
    private volatile long frameIntervalNanos;
    private volatile int recordingFps;
    private volatile int targetWidth;
    private volatile int targetHeight;
    private NetworkPanel capturePanel;
    private Path frameDirectory;
    private volatile IOException captureFailure;
    private RecordingFrameStore queuedFrames;
    private final long bufferBudget;

    public PanelVideoRecorder(Path outputDir) {
        this(outputDir, RecordingFrameStore.defaultBudget());
    }

    PanelVideoRecorder(Path outputDir,long bufferBudget) {
        this.outputDir = outputDir.toAbsolutePath().normalize();
        this.bufferBudget = bufferBudget;
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
            this.encodedFrames = 0;
            this.encoderName = "";
            this.recordingFps = quality.isViewportNative()
                    ? detectDisplayRefreshRate()
                    : Math.max(1, quality.fps());
            this.frameIntervalNanos = quality.isViewportNative() || quality == Quality.PRESENTATION_4K
                    ? 0L
                    : 1_000_000_000L / recordingFps;
            this.lastCaptureNanos = 0;
            this.targetWidth = -1;
            this.targetHeight = -1;
            this.capturePanel = null;
            this.captureFailure = null;

        }

        Files.createDirectories(outputDir);
        frameDirectory = Files.createTempDirectory(outputDir, "recording-frames-");
        queuedFrames = new RecordingFrameStore(frameDirectory,bufferBudget);
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
                ensureCaptureDimensions(pw, ph);
                boolean memory=queuedFrames.reserve(targetWidth,targetHeight);
                boolean submitted=false;
                try {
                    BufferedImage frame=new BufferedImage(targetWidth,targetHeight,BufferedImage.TYPE_INT_RGB);
                    capturePanel=panel;
                    panel.setRecordingActive(true);
                    Graphics2D graphics=frame.createGraphics();
                    try {panel.paintRecordingFrame(graphics,targetWidth,targetHeight);}finally {graphics.dispose();}
                    queuedFrames.add(frame,memory);submitted=true;
                    frameCount=queuedFrames.size();
                } finally {if(!submitted)queuedFrames.cancelReservation(memory);}
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
        final RecordingFrameStore framesToEncode;
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
            queuedFrames = null;
            framesToEncode.finish();

            NetworkPanel panel=capturePanel;capturePanel=null;
            if(panel!=null)javax.swing.SwingUtilities.invokeLater(()->panel.setRecordingActive(false));

        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                framesToEncode.awaitWrites();
                if (failure != null) {
                    throw new IOException("Capture failed. Completed PNG frames are preserved in " + spool, failure);
                }
                if (framesToEncode.size()==0) {
                    Files.deleteIfExists(spool);
                    return null;
                }
                encoderName = "Detecting encoder";
                String nativeEncoder = FfmpegVideoEncoder.encode(outputPath, fps, framesToEncode.size(),
                        framesToEncode::read, value -> encodedFrames = value);
                if (nativeEncoder == null) {
                    encoderName = "JCodec CPU";
                    H264Mp4Encoder.encode(outputPath, fps, framesToEncode.size(), framesToEncode::read,
                            value -> encodedFrames = value);
                } else encoderName = nativeEncoder;
                // Delete only files created by this recording, after a successful encode.
                framesToEncode.cleanup();
                System.out.printf("Video saved: %s (%d frames, %d fps, H.264 MP4)\n",
                        outputPath,
                        framesToEncode.size(),
                        fps);
                return outputPath;
            } catch (Exception ex) {
                try {framesToEncode.preserve();}catch(IOException recovery){ex.addSuppressed(recovery);}
                throw new CompletionException(new IOException("Video export failed; source frames are in " + spool, ex));
            } finally {
                framesToEncode.release();
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

    public String encodingStatus() {
        return encoding ? "Encoding " + encodedFrames + "/" + frameCount + " frames" : encoderName;
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

    private void ensureCaptureDimensions(int panelWidth, int panelHeight) {
        if (targetWidth <= 0 || targetHeight <= 0) {
            if (quality != null && quality.isViewportNative()) {
                targetWidth = evenDimension(panelWidth);
                targetHeight = evenDimension(panelHeight);
            } else {
                targetWidth = quality == null ? AppDefaults.Recording.MEDIUM.width() : quality.width();
                targetHeight = quality == null ? AppDefaults.Recording.MEDIUM.height() : quality.height();
            }
        }

    }

    private static int evenDimension(int value) {
        int clamped = Math.max(2, value);
        return (clamped & 1) == 0 ? clamped : clamped - 1;
    }

    private static int detectDisplayRefreshRate() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice device = ge.getDefaultScreenDevice();
            DisplayMode mode = device.getDisplayMode();
            int rate = mode == null ? 0 : mode.getRefreshRate();
            if (rate == DisplayMode.REFRESH_RATE_UNKNOWN || rate <= 0) {
                return AppDefaults.Recording.FALLBACK_DISPLAY_FPS;
            }
            return Math.max(30, Math.min(240, rate));
        } catch (Throwable ignored) {
            return AppDefaults.Recording.FALLBACK_DISPLAY_FPS;
        }
    }
}
