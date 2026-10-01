package com.matsim.viz.ui;

import com.matsim.viz.config.AppDefaults;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/** Optional native H.264 encoder. Probe the actual hardware, never just its advertised codec list. */
final class FfmpegVideoEncoder {
    private FfmpegVideoEncoder() { }
    static String executable() {
        String configured = System.getProperty("matsim.recording.ffmpeg", AppDefaults.Recording.FFMPEG_EXECUTABLE);
        if (System.getProperty("matsim.recording.ffmpeg") != null || Files.isRegularFile(Path.of(configured))) return configured;
        return AppDefaults.Recording.FFMPEG_PATH_COMMAND;
    }

    static String encode(Path output, int fps, int count, H264Mp4Encoder.FrameReader frames,
                         IntConsumer progress) throws IOException {
        if (Files.exists(output) && !Files.isRegularFile(output)) throw new IOException("Video output is not a regular file: " + output);
        String requested = System.getProperty("matsim.recording.encoder", AppDefaults.Recording.ENCODER);
        if (requested.equals("jcodec")) return null;
        BufferedImage first = frames.read(0);
        int width = first.getWidth(), height = first.getHeight();
        first.flush();
        String executable = executable();
        List<String> candidates = requested.equals("auto") ? AppDefaults.Recording.ENCODER_ORDER : List.of(requested);
        Path scratch = Files.createTempDirectory(output.getParent(), "encoder-");
        Path log = scratch.resolve("ffmpeg.log"), movie = scratch.resolve("video.mp4");
        try {
            for (String codec : candidates) {
                if (!AppDefaults.Recording.ENCODER_ORDER.contains(codec)) throw new IOException("Unknown video encoder: " + codec);
                try {
                    var probe = base(executable);
                    probe.addAll(List.of("-f", "lavfi", "-i", "color=black:s=" + width + "x" + height + ":r=" + fps,
                            "-frames:v", "1"));
                    probe.addAll(options(codec, fps)); probe.addAll(List.of("-f", "null", "-"));
                    Process process = new ProcessBuilder(probe).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    boolean available;
                    try {
                        available = process.waitFor(AppDefaults.Recording.ENCODER_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                && process.exitValue() == 0;
                    } finally {
                        if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
                    }
                    if (!available) { System.out.println("Video encoder unavailable: " + codec); continue; }
                    progress.accept(0);
                    long started = System.nanoTime();
                    encodeFrames(executable, codec, movie, log, fps, count, width, height, frames, progress);
                    Files.move(movie, output, StandardCopyOption.REPLACE_EXISTING);
                    System.out.printf("Video encoder %s: %d frames in %.2f s%n", codec, count, (System.nanoTime()-started)/1e9);
                    return codec;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); throw new IOException("Video encoding interrupted", ex);
                } catch (IOException ex) {
                    System.err.println("Video encoder " + codec + " failed: " + ex.getMessage());
                    if (!requested.equals("auto")) throw ex;
                    // Reader is repeatable: retain original lossless frames until one encoder succeeds.
                }
            }
            if (!requested.equals("auto")) throw new IOException("Requested encoder is unavailable: " + requested);
            return null;
        } finally {
            Files.deleteIfExists(movie); Files.deleteIfExists(log); Files.deleteIfExists(scratch);
        }
    }

    private static List<String> base(String executable) {
        return new ArrayList<>(List.of(executable, "-hide_banner", "-loglevel", "error", "-nostdin", "-y"));
    }

    static List<String> options(String codec, int fps) {
        var args = new ArrayList<>(List.of("-an", "-c:v", codec, "-pix_fmt", "yuv420p", "-profile:v", "high",
                "-g", Integer.toString(fps), "-bf", "0"));
        String qp = Integer.toString(AppDefaults.Recording.H264_QP);
        switch (codec) {
            case "h264_nvenc" -> args.addAll(List.of("-preset", AppDefaults.Recording.NVENC_PRESET, "-tune", "hq", "-rc", "constqp", "-qp", qp));
            case "h264_qsv" -> args.addAll(List.of("-preset", AppDefaults.Recording.QSV_PRESET, "-global_quality", qp));
            case "h264_amf" -> args.addAll(List.of("-quality", AppDefaults.Recording.AMF_QUALITY, "-rc", "cqp", "-qp_i", qp, "-qp_p", qp));
            case "libx264" -> args.addAll(List.of("-preset", AppDefaults.Recording.X264_PRESET, "-crf", qp));
            default -> throw new IllegalArgumentException(codec);
        }
        return args;
    }

    private static void encodeFrames(String executable, String codec, Path output, Path log, int fps, int count,
            int width, int height, H264Mp4Encoder.FrameReader frames, IntConsumer progress) throws IOException, InterruptedException {
        var args = base(executable);
        args.addAll(List.of("-f", "rawvideo", "-pixel_format", "bgra", "-video_size", width+"x"+height,
                "-framerate", Integer.toString(fps), "-i", "pipe:0", "-vf",
                "scale=in_range=full:out_range=tv:out_color_matrix=bt709,format=yuv420p"));
        args.addAll(options(codec, fps));
        args.addAll(List.of("-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709",
                "-color_trc", "bt709", "-movflags", "+faststart", output.toString()));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        AtomicLong activity = new AtomicLong(System.nanoTime());
        var watchdog = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r,"encoder-watchdog"); t.setDaemon(true); return t; });
        watchdog.scheduleAtFixedRate(() -> {
            if (System.nanoTime()-activity.get() > TimeUnit.SECONDS.toNanos(AppDefaults.Recording.ENCODER_STALL_TIMEOUT_SECONDS)) process.destroyForcibly();
        }, AppDefaults.Recording.ENCODER_WATCHDOG_SECONDS, AppDefaults.Recording.ENCODER_WATCHDOG_SECONDS, TimeUnit.SECONDS);
        try {
            // Row buffers avoid a second full-sized image or any PNG decompression for RAM frames.
            byte[] bytes = new byte[width * 4]; int[] pixels = new int[width];
            try (var pipe = new BufferedOutputStream(process.getOutputStream(), AppDefaults.Recording.ENCODER_PIPE_BUFFER_BYTES)) {
                for (int index = 0; index < count; index++) {
                    BufferedImage frame = frames.read(index);
                    if (frame == null || frame.getWidth()!=width || frame.getHeight()!=height) throw new IOException("Invalid frame " + index);
                    try {
                        for (int y = 0; y < height; y++) {
                            frame.getRGB(0,y,width,1,pixels,0,width);
                            for (int x = 0, b = 0; x < width; x++) {
                                int rgb = pixels[x]; bytes[b++]=(byte)rgb; bytes[b++]=(byte)(rgb>>>8);
                                bytes[b++]=(byte)(rgb>>>16); bytes[b++]=(byte)255;
                            }
                            pipe.write(bytes); activity.set(System.nanoTime());
                        }
                    } finally { frame.flush(); }
                    progress.accept(index + 1);
                }
            }
            if (!process.waitFor(AppDefaults.Recording.ENCODER_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException("Encoder finalization timed out");
            if (process.exitValue()!=0) throw new IOException("FFmpeg: " + Files.readString(log));
        } finally {
            watchdog.shutdownNow();
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }
}
