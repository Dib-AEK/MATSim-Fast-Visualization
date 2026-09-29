package com.matsim.viz.ui;

import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.codecs.h264.encode.RateControl;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import org.jcodec.common.Codec;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.*;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.scale.AWTUtil;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

/** H.264/AVC in MP4, using the existing JCodec dependency without external tools. */
final class H264Mp4Encoder {
    private H264Mp4Encoder() { }

    static void encode(Path output, int fps, List<Path> frames) throws IOException {
        // Fixed QP 18 prioritizes fine road/vehicle detail over a small file size.
        H264Encoder encoder = new H264Encoder(new RateControl() {
            public int startPicture(Size size, int maxSize, SliceType type) { return 18; }
            public int initialQpDelta() { return 0; }
            public int accept(int bits) { return 0; }
        }) {
            @Override public SeqParameterSet initSPS(Size size) {
                SeqParameterSet sps = super.initSPS(size);
                long blocks = ((size.getWidth() + 15L) / 16) * ((size.getHeight() + 15L) / 16);
                // JCodec defaults to level 4.0 even for 4K: signal the actual requirements.
                sps.levelIdc = blocks <= 8192 && blocks * fps <= 245760 ? 40
                        : blocks <= 8704 && blocks * fps <= 522240 ? 42
                        : blocks <= 36864 && blocks * fps <= 983040 ? 51
                        : blocks <= 36864 && blocks * fps <= 2073600 ? 52 : 62;
                sps.vuiParams = new VUIParameters();
                sps.vuiParams.videoSignalTypePresentFlag = true;
                sps.vuiParams.videoFormat = 5;
                sps.vuiParams.videoFullRangeFlag = false;
                sps.vuiParams.colourDescriptionPresentFlag = true;
                sps.vuiParams.colourPrimaries = 1;
                sps.vuiParams.transferCharacteristics = 1;
                sps.vuiParams.matrixCoefficients = 6; // JCodec RGB -> YUV uses BT.601 coefficients.
                return sps;
            }
        };
        encoder.setKeyInterval(fps); // Seekable once per second.
        try (var channel = NIOUtils.writableFileChannel(output.toString())) {
            var muxer = MP4Muxer.createMP4MuxerToChannel(channel);
            org.jcodec.common.MuxerTrack track = null;
            ByteBuffer buffer = null;
            int frameNumber = 0;
            for (Path path : frames) {
                var frame = ImageIO.read(path.toFile());
                if (frame == null) throw new IOException("Cannot read frame: " + path);
                try {
                    Picture yuv = AWTUtil.fromBufferedImage(frame, ColorSpace.YUV420);
                    // Encoder accepts only the YUV420J tag, but encodes plane values unchanged.
                    // Supply limited-range planes and describe that range explicitly in the SPS.
                    Picture input = Picture.createPicture(yuv.getWidth(), yuv.getHeight(),
                            yuv.getData(), ColorSpace.YUV420J);
                    if (track == null) {
                        track = muxer.addVideoTrack(Codec.H264,
                                VideoCodecMeta.createSimpleVideoCodecMeta(input.getSize(), ColorSpace.YUV420));
                        buffer = ByteBuffer.allocate(Math.max(65536, input.getWidth() * input.getHeight() * 6));
                    }
                    buffer.clear();
                    var encoded = encoder.encodeFrame(input, buffer);
                    // The muxer may retain packets until a chunk is flushed: do not reuse their bytes.
                    track.addFrame(Packet.createPacket(NIOUtils.clone(encoded.getData()), frameNumber,
                            fps, 1, frameNumber, encoded.isKeyFrame() ? Packet.FrameType.KEY : Packet.FrameType.INTER, null));
                    frameNumber++;
                } finally {
                    frame.flush();
                }
            }
            muxer.finish();
        }
    }
}
