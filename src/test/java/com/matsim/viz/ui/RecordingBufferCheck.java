package com.matsim.viz.ui;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Verify bounded memory, lossless overflow, order and recovery without video compression. */
public final class RecordingBufferCheck {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory(Path.of("target"),"frame-buffer-check-");
        int width=320,height=180;
        long budget=5L*width*height*4;
        var store=new RecordingFrameStore(directory,budget);
        List<int[]> expected=new ArrayList<>();
        Random random=new Random(123);
        for(int i=0;i<20;i++){
            boolean memory=store.reserve(width,height);
            check(memory==(i<3),"Unexpected RAM/spill reservation");
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            int[] pixels=new int[width*height];for(int j=0;j<pixels.length;j++)pixels[j]=random.nextInt()|0xff000000;
            image.setRGB(0,0,width,height,pixels,0,width);expected.add(pixels);
            store.add(image,memory);
            check(store.retainedBytes()<=budget-2L*width*height*4,"Exceeded retained RAM budget");
        }
        store.finish();store.awaitWrites();
        for(int i=0;i<20;i++)check(Arrays.equals(expected.get(i),store.read(i).getRGB(0,0,width,height,null,0,width)),"Lost or reordered pixels in frame "+i);
        store.preserve();
        try(var files=Files.list(directory)){check(files.count()==20,"Recovery missing RAM frames");}
        store.cleanup();store.release();check(!Files.exists(directory),"Spool not cleaned");
        var failed=new RecordingFrameStore(Files.createTempDirectory(Path.of("target"),"frame-buffer-failure-"),0);
        // No retained frames: two spill slots remain available even with a zero RAM preference.
        boolean memory=failed.reserve(width,height);failed.cancelReservation(memory);
        failed.finish();failed.cleanup();failed.release();
        Path badDirectory=Files.createTempDirectory(Path.of("target"),"frame-writer-failure-");
        Files.createDirectory(badDirectory.resolve("frame-00000000.png"));
        var broken=new RecordingFrameStore(badDirectory,0);
        boolean ram=broken.reserve(width,height);broken.add(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),ram);broken.finish();
        boolean reported=false;try{broken.awaitWrites();}catch(java.io.IOException expectedError){reported=true;}
        check(reported,"Background write failure was hidden");
        reported=false;try{broken.reserve(width,height);}catch(java.io.IOException expectedError){reported=true;}
        check(reported,"Capture ignored a failed writer");broken.cleanup();broken.release();
        System.out.println("PASS: bounded frame buffer, asynchronous PNG spill, exact pixels/order, recovery and cleanup.");
    }
}
