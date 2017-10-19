package net.ossrs.yasea;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Leo Ma on 4/1/2016.
 */
public class SrsEncoder {
    private static final String TAG = "SrsEncoder";

    public static final String VCODEC = "video/avc";
    public static final String ACODEC = "audio/mp4a-latm";
    public static String x264Preset = "veryfast";
    public static int vPrevWidth = 640;
    public static int vPrevHeight = 360;
    public static int vPortraitWidth = 720;
    public static int vPortraitHeight = 1280;
    public static int vLandscapeWidth = 1280;
    public static int vLandscapeHeight = 720;
    public static int vOutWidth = 720;   // Note: the stride of resolution must be set as 16x for hard encoding with some chip like MTK
    public static int vOutHeight = 1280;  // Since Y component is quadruple size as U and V component, the stride must be set as 32x
    public static int vBitrate = 1200 * 1024;  // 1200 kbps
    public static final int VFPS = 24;
    public static final int VGOP = 24;
    public static final int ASAMPLERATE = 44100;
    public static int aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
    public static final int ABITRATE = 128 * 1024;  // 128 kbps

    private SrsEncodeHandler mHandler;

    private SrsFlvMuxer flvMuxer;
    private SrsMp4Muxer mp4Muxer;

    private MediaCodec vencoder;
    private MediaCodec aencoder;
    private MediaCodec.BufferInfo vebi = new MediaCodec.BufferInfo();
    private MediaCodec.BufferInfo aebi = new MediaCodec.BufferInfo();

    private boolean networkWeakTriggered = false;
    private boolean mCameraFaceFront = true;
    private boolean useSoftEncoder = false;
    private boolean canSoftEncode = false;

    private long mPresentTimeUs;

    private int mVideoColorFormat;

    private int videoFlvTrack;
    private int videoMp4Track;
    private int audioFlvTrack;
    private int audioMp4Track;

    private int rotate = 0;
    private int rotateFlip = 180;

    private int vInputWidth;
    private int vInputHeight;

    private byte[] y_frame;
    private byte[] u_frame;
    private byte[] v_frame;

    /**
     * ID for last encoded channel
     */
    private int lastChannelID = 0;

    private long lastPTS = -1;

    // Y, U (Cb) and V (Cr)
    // yuv420                     yuv yuv yuv yuv
    // yuv420p (planar)   yyyy*2 uu vv
    // yuv420sp(semi-planner)   yyyy*2 uv uv
    // I420 -> YUV420P   yyyy*2 uu vv
    // YV12 -> YUV420P   yyyy*2 vv uu
    // NV12 -> YUV420SP  yyyy*2 uv uv
    // NV21 -> YUV420SP  yyyy*2 vu vu
    // NV16 -> YUV422SP  yyyy uv uv
    // YUY2 -> YUV422SP  yuyv yuyv

    public SrsEncoder(SrsEncodeHandler handler) {
        mHandler = handler;
    }

    public void setFlvMuxer(SrsFlvMuxer flvMuxer) {
        this.flvMuxer = flvMuxer;
    }

    public void setMp4Muxer(SrsMp4Muxer mp4Muxer) {
        this.mp4Muxer = mp4Muxer;
    }

    public boolean start() {
        if (flvMuxer == null && mp4Muxer == null) {
            return false;
        }

        // the referent PTS for video and audio encoder.
        mPresentTimeUs = System.currentTimeMillis() * 1000;

        setEncoderResolution(vOutWidth, vOutHeight);
        setEncoderFps(VFPS);
        setEncoderGop(VGOP);
        // Unfortunately for some android phone, the output fps is less than 10 limited by the
        // capacity of poor cheap chips even with x264. So for the sake of quick appearance of
        // the first picture on the player, a spare lower GOP value is suggested. But note that
        // lower GOP will produce more I frames and therefore more streaming data flow.
        // setEncoderGop(15);
        setEncoderBitrate(vBitrate);
        setEncoderPreset(x264Preset);

        if (useSoftEncoder) {
            canSoftEncode = openSoftEncoder();
            if (!canSoftEncode) {
                return false;
            }
        }

        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

        // vencoder yuv to 264 es stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            mVideoColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;

            // setup the vencoder.
            // Note: landscape to portrait, 90 degree rotation, so we need to switch width and height in configuration
            MediaFormat videoFormat = MediaFormat.createVideoFormat(VCODEC, vOutWidth, vOutHeight);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mVideoColorFormat);
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, vBitrate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VFPS);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VGOP / VFPS);

            String videoCodecName = list.findEncoderForFormat(videoFormat);
            vencoder = MediaCodec.createByCodecName(videoCodecName);
            vencoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            // add the video tracker to muxer.
            if (flvMuxer != null) videoFlvTrack = flvMuxer.addTrack(videoFormat);
            if (mp4Muxer != null) videoMp4Track = mp4Muxer.addTrack(videoFormat);

        } catch (IOException e) {
            Log.e(TAG, "create vencoder failed.");
            e.printStackTrace();
            return false;
        }

        // aencoder pcm to aac raw stream.
        // requires sdk level 16+, Android 4.1, 4.1.1, the JELLY_BEAN
        try {
            // setup the aencoder.
            // @see https://developer.android.com/reference/android/media/MediaCodec.html
            int ach = aChannelConfig == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
            MediaFormat audioFormat = MediaFormat.createAudioFormat(ACODEC, ASAMPLERATE, ach);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, ABITRATE);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);

            String AudioCodecName = list.findEncoderForFormat(audioFormat);
            aencoder = MediaCodec.createByCodecName(AudioCodecName);
            aencoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            // add the audio tracker to muxer.
            if (flvMuxer != null) audioFlvTrack = flvMuxer.addTrack(audioFormat);
            if (mp4Muxer != null) audioMp4Track = mp4Muxer.addTrack(audioFormat);

        } catch (IOException e) {
            Log.e(TAG, "create aencoder failed.");
            e.printStackTrace();
            return false;
        }

        // start device and encoder.
        vencoder.start();
        aencoder.start();
        return true;
    }

    public void stop() {
        if (useSoftEncoder) {
            closeSoftEncoder();
            canSoftEncode = false;
        }

        if (aencoder != null) {
            Log.i(TAG, "stop aencoder");
            aencoder.stop();
            aencoder.release();
            aencoder = null;
        }

        if (vencoder != null) {
            Log.i(TAG, "stop vencoder");
            vencoder.stop();
            vencoder.release();
            vencoder = null;
        }
    }

    public void setCameraFrontFace() {
        mCameraFaceFront = true;
    }

    public void setCameraBackFace() {
        mCameraFaceFront = false;
    }

    public void switchToSoftEncoder() {
        useSoftEncoder = true;
    }

    public void switchToHardEncoder() {
        useSoftEncoder = false;
    }

    public boolean isSoftEncoder() {
        return useSoftEncoder;
    }

    public boolean canHardEncode() {
        return vencoder != null;
    }

    public boolean canSoftEncode() {
        return canSoftEncode;
    }

    public boolean isEnabled() {
        return canHardEncode() || canSoftEncode();
    }

    public void setPreviewResolution(int width, int height) {
        vPrevWidth = width;
        vPrevHeight = height;
    }

    public void setPortraitResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vPortraitWidth = width;
        vPortraitHeight = height;
        vLandscapeWidth = height;
        vLandscapeHeight = width;
    }

    public void setInputResolution(int width, int height) {
        vInputWidth = width;
        vInputHeight = height;

        y_frame = new byte[width * height];
        u_frame = new byte[(width * height) / 2 - 1];
        v_frame = new byte[(width * height) / 2 - 1];
    }

    public void setLandscapeResolution(int width, int height) {
        vOutWidth = width;
        vOutHeight = height;
        vLandscapeWidth = width;
        vLandscapeHeight = height;
        vPortraitWidth = height;
        vPortraitHeight = width;
    }

    public void setVideoHDMode() {
        vBitrate = 1200 * 1024;  // 1200 kbps
        x264Preset = "veryfast";
    }

    public void setVideoSmoothMode() {
        vBitrate = 500 * 1024;  // 500 kbps
        x264Preset = "superfast";
    }

    public int getPreviewWidth() {
        return vPrevWidth;
    }

    public int getPreviewHeight() {
        return vPrevHeight;
    }

    public int getOutputWidth() {
        return vOutWidth;
    }

    public int getOutputHeight() {
        return vOutHeight;
    }

    public void setScreenOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            vOutWidth = vPortraitWidth;
            vOutHeight = vPortraitHeight;
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            vOutWidth = vLandscapeWidth;
            vOutHeight = vLandscapeHeight;
        }
        setEncoderResolution(vOutWidth, vOutHeight);
    }

    public void setCameraOrientation(int degrees) {
        if (degrees < 0) {
            rotate = 360 + degrees;
            rotateFlip = 180 - degrees;
        } else {
            rotate = degrees;
            rotateFlip = 180 + degrees;
        }
    }

    private void encodeYuvFrame(byte[] yuvFrame, long pts) {
        int inBufferIndex = vencoder.dequeueInputBuffer(-1);
        if (inBufferIndex >= 0) {
            ByteBuffer bb = vencoder.getInputBuffer(inBufferIndex);
            bb.put(yuvFrame, 0, yuvFrame.length);
            vencoder.queueInputBuffer(inBufferIndex, 0, yuvFrame.length, pts, 0);
        }
    }

    public byte[] getH264Frame() {
        int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
        if (outBufferIndex >= 0) {
            ByteBuffer bb = vencoder.getOutputBuffer(outBufferIndex);
            byte[] frame = new byte[vebi.size + 8];
            bb.get(frame, 0, vebi.size);

            // Encode timestamp
            long time = vebi.presentationTimeUs + mPresentTimeUs;
            for (int i = 7; i >= 0; i--) {
                frame[vebi.size+i] = (byte)(time & 0xFF);
                time >>= 8;
            }

            vencoder.releaseOutputBuffer(outBufferIndex, false);
            return frame;
        }
        return null;
    }

    public void muxH264Frame(byte[] frame) {
        ByteBuffer bb = ByteBuffer.wrap(frame, 0, frame.length-8);
        vebi.offset = 0;
        vebi.size = frame.length-8;

        // Decode timestamp
        long time = 0;
        for (int i = 0; i < 8; i++) {
            time <<= 8;
            time |= (frame[vebi.size+i] & 0xFF);
        }
        vebi.presentationTimeUs = time - mPresentTimeUs;

        vebi.flags = 0;
        mux264Frame(bb, vebi);
    }

    public void muxH264Frame() {
        int outBufferIndex = vencoder.dequeueOutputBuffer(vebi, 0);
        if (outBufferIndex >= 0) {
            ByteBuffer bb = vencoder.getOutputBuffer(outBufferIndex);
            mux264Frame(bb, vebi);
            vencoder.releaseOutputBuffer(outBufferIndex, false);

            muxH264Frame();
        }
    }

    // when got encoded h264 es stream.
    private void mux264Frame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        // Check video frame cache number to judge the networking situation.
        // Just cache GOP / FPS seconds data according to latency.
        AtomicInteger videoFrameCacheNumber = flvMuxer.getVideoFrameCacheNumber();
        if (videoFrameCacheNumber != null && videoFrameCacheNumber.get() < VGOP) {

            if (bi.presentationTimeUs > lastPTS) {
                if (flvMuxer != null) flvMuxer.writeSampleData(videoFlvTrack, es, bi);
                if (mp4Muxer != null) mp4Muxer.writeSampleData(videoMp4Track, es.duplicate(), bi);

                lastPTS = bi.presentationTimeUs;
            }

            if (networkWeakTriggered) {
                networkWeakTriggered = false;
                mHandler.notifyNetworkResume();
            }
        } else {
            mHandler.notifyNetworkWeak();
            networkWeakTriggered = true;
        }
    }

    // when got encoded aac raw stream.
    private void onEncodedAacFrame(ByteBuffer es, MediaCodec.BufferInfo bi) {
        if (mp4Muxer != null) mp4Muxer.writeSampleData(audioMp4Track, es.duplicate(), bi);
        if (flvMuxer != null) flvMuxer.writeSampleData(audioFlvTrack, es, bi);
    }

    public void onGetPcmFrame(byte[] data, int size) {
        int inBufferIndex = aencoder.dequeueInputBuffer(-1);
        if (inBufferIndex >= 0) {
            ByteBuffer bb = aencoder.getInputBuffer(inBufferIndex);
            bb.put(data, 0, size);
            long pts = System.currentTimeMillis() * 1000 - mPresentTimeUs;
            aencoder.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
        }

        for (; ; ) {
            int outBufferIndex = aencoder.dequeueOutputBuffer(aebi, 0);
            if (outBufferIndex >= 0) {
                ByteBuffer bb = aencoder.getOutputBuffer(outBufferIndex);
                onEncodedAacFrame(bb, aebi);
                aencoder.releaseOutputBuffer(outBufferIndex, false);
            } else {
                break;
            }
        }
    }

    public void onGetRgbaFrame(byte[] data, int width, int height) {
        encodeYuvFrame(RGBAtoYUV(data, width, height));
    }

    public void onGetYuvNV21Frame(byte[] data, int width, int height, Rect boundingBox) {
        encodeYuvFrame(NV21toYUV(data, width, height, boundingBox));
    }

    public void onGetYUV420_888Frame(Image image, Rect boundingBox) {
        encodeYuvFrame(YUV420_888toYUV(image, boundingBox));
    }

    public void onGetArgbFrame(int[] data, int width, int height, Rect boundingBox) {
        encodeYuvFrame(ARGBtoYUV(data, width, height, boundingBox));
    }

    public void encodeYuvFrame(byte[] frame) {
        long pts = System.currentTimeMillis() * 1000 - mPresentTimeUs;
        encodeYuvFrame(frame, pts);
    }

    public byte[] RGBAtoYUV(byte[] data, int width, int height) {
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return RGBAToI420(data, width, height, true, rotateFlip);
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return RGBAToNV12(data, width, height, true, rotateFlip);
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    public byte[] NV21toYUV(byte[] data, int width, int height, Rect boundingBox) {
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return NV21ToI420(data, width, height, true, rotateFlip, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return NV21ToNV12(data, width, height, true, rotateFlip, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    public byte[] YUV420_888toYUV(Image image, Rect cropArea) {
        Image.Plane[] planes = image.getPlanes();
        planes[0].getBuffer().get(y_frame);
        planes[1].getBuffer().get(u_frame);
        planes[2].getBuffer().get(v_frame);
        return YUV420_888toI420(y_frame, u_frame, v_frame, vInputWidth, vInputHeight, false, 0,
                cropArea.left, cropArea.top, cropArea.width(), cropArea.height());
    }

    public byte[] ARGBtoYUV(int[] data, int width, int height, Rect boundingBox) {
        switch (mVideoColorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                return ARGBToI420(data, width, height, false, rotate, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return ARGBToNV12(data, width, height, false, rotate, boundingBox.left, boundingBox.top, boundingBox.width(), boundingBox.height());
            default:
                throw new IllegalStateException("Unsupported color format!");
        }
    }

    public void onGetRgbaSoftFrame(byte[] data, int width, int height, long pts) {
        RGBASoftEncode(data, width, height, true, 180, pts);
    }

    public AudioRecord chooseAudioRecord() {
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsEncoder.ASAMPLERATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SrsEncoder.ASAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, getPcmBufferSize() * 4);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
            } else {
                SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_MONO;
            }
        } else {
            SrsEncoder.aChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }

        return mic;
    }

    public int getPcmBufferSize() {
        int pcmBufSize = AudioRecord.getMinBufferSize(ASAMPLERATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) + 8191;
        return pcmBufSize - (pcmBufSize % 8192);
    }

    private native void setEncoderResolution(int outWidth, int outHeight);

    private native void setEncoderFps(int fps);

    private native void setEncoderGop(int gop);

    private native void setEncoderBitrate(int bitrate);

    private native void setEncoderPreset(String preset);

    private native byte[] RGBAToI420(byte[] frame, int width, int height, boolean flip, int rotate);

    private native byte[] RGBAToNV12(byte[] frame, int width, int height, boolean flip, int rotate);

    private native byte[] ARGBToI420(int[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] YUV420_888toI420(byte[] y_frame, byte[] u_frame, byte[] v_frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] ARGBToNV12(int[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] NV21ToNV12(byte[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native byte[] NV21ToI420(byte[] frame, int width, int height, boolean flip, int rotate, int crop_x, int crop_y, int crop_width, int crop_height);

    private native int RGBASoftEncode(byte[] frame, int width, int height, boolean flip, int rotate, long pts);

    private native boolean openSoftEncoder();

    private native void closeSoftEncoder();

    static {
        System.loadLibrary("yuv");
        System.loadLibrary("enc");
    }
}
