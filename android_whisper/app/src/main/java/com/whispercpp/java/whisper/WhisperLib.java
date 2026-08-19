package com.whispercpp.java.whisper;

import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.InputStream;

@RequiresApi(api = Build.VERSION_CODES.O)
public class WhisperLib {
  private static final String LOG_TAG = "LibWhisper";

  static {
    Log.d(LOG_TAG, "Loading libwhisper.so (ABI: " + Build.SUPPORTED_ABIS[0] + ")");
    System.loadLibrary("whisper");
  }

  public static native long initContextFromInputStream(InputStream inputStream);

  public static native long initContextFromAsset(AssetManager assetManager, String assetPath);

  public static native long initContext(String modelPath);

  public static native void freeContext(long contextPtr);

  public static native void fullTranscribe(long contextPtr, int numThreads, float[] audioData);

  public static native int getTextSegmentCount(long contextPtr);

  public static native String getTextSegment(long contextPtr, int index);

  public static native long getTextSegmentT0(long contextPtr, int index);

  public static native long getTextSegmentT1(long contextPtr, int index);

  public static native String getSystemInfo();

  public static native String benchMemcpy(int nthread);

  public static native String benchGgmlMulMat(int nthread);
}
