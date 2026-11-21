package app.absentmotion.ffamp;

import android.hardware.HardwareBuffer;
import java.nio.ByteBuffer;

/* JADX INFO: compiled from: Ffamp.kt */
/* JADX INFO: loaded from: classes3.dex */
public final class FfampNative {
    public static final int $stable = 0;
    public static final FfampNative INSTANCE = new FfampNative();

    public final native void audioClose(long audio);

    public final native long audioOpen(long handle, int track);

    public final native boolean audioRead(long audio, long start, int frames, float[] out);

    public final native long[] audioShape(long audio);

    public final native void close(long handle);

    public final native String container(long handle);

    public final native byte[][] csd(long handle, int track);

    public final native String error();

    public final native String mime(long handle, int track);

    public final native long open(String path);

    public final native long picture(ByteBuffer y, ByteBuffer u, ByteBuffer v, int yRow, int uvRow, int width, int height, int[] coefficients);

    public final native HardwareBuffer pictureBuffer(long picture);

    public final native void pictureRelease(long picture);

    public final native int read(long handle, int track, int index, ByteBuffer buffer, int offset);

    public final native void table(long handle, int track, long[] pts, int[] sizes, boolean[] syncs);

    public final native long[] track(long handle, int track);

    public final native int tracks(long handle);

    public final native void videoClose(long video);

    public final native int videoFeed(long video, int index);

    public final native String videoName(long video);

    public final native long videoOpen(long handle, int track);

    public final native void videoReset(long video);

    public final native long videoTake(long video, ByteBuffer buffer, int[] shape);

    private FfampNative() {
    }

    static {
        System.loadLibrary("amffamp");
    }
}
