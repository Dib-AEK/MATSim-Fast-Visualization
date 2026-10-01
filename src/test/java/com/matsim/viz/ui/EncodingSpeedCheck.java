package com.matsim.viz.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Optional real native-encoder benchmark; fallback remains available on machines without FFmpeg. */
public final class EncodingSpeedCheck {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of("target"),"encoding-speed-");
        BufferedImage image=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(new Color(0x111820));g.fillRect(0,0,1920,1080);
        for(int i=0;i<250;i++){g.setColor(i%2==0?Color.GRAY:Color.CYAN);g.drawLine(i*11%1920,0,i*31%1920,1080);g.fillRect(i*37%1920,i*71%1080,12,5);}g.dispose();
        int frames=30;
        long start=System.nanoTime();H264Mp4Encoder.encode(dir.resolve("java.mp4"),15,frames,i->image);
        double javaSeconds=(System.nanoTime()-start)/1e9;
        start=System.nanoTime();
        String backend=FfmpegVideoEncoder.encode(dir.resolve("native.mp4"),15,frames,i->image,done->{ });
        double nativeSeconds=(System.nanoTime()-start)/1e9;
        if(backend!=null)System.out.printf("PASS: 30 identical 1080p source frames: JCodec %.2f s; %s %.2f s including detection (%.1fx faster).%n",javaSeconds,backend,nativeSeconds,javaSeconds/nativeSeconds);
        else System.out.println("PASS: FFmpeg unavailable; Java encoding works.");
        String previous=System.getProperty("matsim.recording.ffmpeg");
        try {
            System.setProperty("matsim.recording.ffmpeg",dir.resolve("missing-ffmpeg.exe").toString());
            if(FfmpegVideoEncoder.encode(dir.resolve("missing.mp4"),15,1,i->image,done->{})!=null)throw new AssertionError("Missing executable did not fall back");
        }finally{if(previous==null)System.clearProperty("matsim.recording.ffmpeg");else System.setProperty("matsim.recording.ffmpeg",previous);}
    }
}
