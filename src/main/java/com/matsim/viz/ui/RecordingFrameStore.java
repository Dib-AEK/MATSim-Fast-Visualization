package com.matsim.viz.ui;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Ordered, lossless frames: bounded RAM first, bounded asynchronous PNG spill afterwards. */
final class RecordingFrameStore {
    private record Frame(BufferedImage image, Path file, CompletableFuture<Void> written) { }
    private final Path directory;
    private final long budget;
    private final List<Frame> frames = new ArrayList<>();
    private final Semaphore pending = new Semaphore(2);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recording-frame-writer"); t.setDaemon(true); return t;
    });
    private long retainedBytes;
    private volatile IOException failure;

    RecordingFrameStore(Path directory, long budget) { this.directory=directory; this.budget=Math.max(0,budget); }
    static long defaultBudget() {
        Runtime runtime=Runtime.getRuntime();
        long available=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory());
        long requested=Math.max(0,Math.min(4096,Long.getLong("matsim.recording.bufferMiB",512L))) * 1024 * 1024;
        return Math.min(requested,Math.min(runtime.maxMemory()/4,available/3));
    }
    // Reserve a spill slot BEFORE allocating another image, limiting queued full-size images to two.
    boolean reserve(int width,int height) throws IOException {
        if(failure!=null)throw failure;
        long bytes=4L*width*height;
        if(retainedBytes+bytes<=Math.max(0,budget-2*bytes))return true;
        try { pending.acquire(); } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Interrupted while buffering frames",e);}
        if(failure!=null){pending.release();throw failure;}
        return false;
    }
    void cancelReservation(boolean memory) { if(!memory)pending.release(); }
    void add(BufferedImage image,boolean memory) {
        Path file=directory.resolve(String.format("frame-%08d.png",frames.size()));
        if(memory){retainedBytes+=4L*image.getWidth()*image.getHeight();frames.add(new Frame(image,file,null));return;}
        CompletableFuture<Void> written=CompletableFuture.runAsync(()->{
            try {writePng(image,file);}catch(Exception e){failure=e instanceof IOException io?io:new IOException("Frame writer failed",e);throw new CompletionException(failure);}
            finally {image.flush();pending.release();}
        },writer);
        frames.add(new Frame(null,file,written));
    }
    int size(){return frames.size();}
    long retainedBytes(){return retainedBytes;}
    void finish(){writer.shutdown();}
    void awaitWrites() throws IOException {
        IOException error=null;
        for(Frame frame:frames)if(frame.written()!=null)try {frame.written().join();}
        catch(CompletionException e){error=new IOException("Could not spool recording frames",e.getCause());}
        if(error!=null)throw error;
    }
    BufferedImage read(int index) throws IOException {
        Frame frame=frames.get(index);
        if(frame.image()!=null)return frame.image();
        BufferedImage image=ImageIO.read(frame.file().toFile());
        if(image==null)throw new IOException("Cannot read frame "+frame.file());
        return image;
    }
    void preserve() throws IOException {
        // On encoder failure, materialize RAM frames as standard PNGs for recovery.
        IOException error=null;
        for(Frame frame:frames)if(frame.image()!=null)try {writePng(frame.image(),frame.file());}catch(IOException e){error=e;}
        if(error!=null)throw error;
    }
    void cleanup() throws IOException {
        for(Frame frame:frames)Files.deleteIfExists(frame.file());
        Files.deleteIfExists(directory);
    }
    void release(){frames.clear();retainedBytes=0;}
    static void writePng(BufferedImage image,Path file) throws IOException {
        var writers=ImageIO.getImageWritersByFormatName("png");
        if(!writers.hasNext())throw new IOException("No PNG writer available");
        var png=writers.next();
        try(var output=ImageIO.createImageOutputStream(file.toFile())) {
            if(output==null)throw new IOException("Cannot open frame output: "+file);
            png.setOutput(output);var params=png.getDefaultWriteParam();
            if(params.canWriteCompressed()){params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);params.setCompressionQuality(0.9f);}
            // PNG stays lossless at every compression level; favour speed over temporary file size.
            png.write(null,new javax.imageio.IIOImage(image,null,null),params);
        } finally {png.dispose();}
    }
}
