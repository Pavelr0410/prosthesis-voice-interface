package com.whispercpp.java.whisper;

public class WhisperCpuConfig {
  // 4 потока надёжно доступны на большинстве телефонов
  public static int getPreferredThreadCount() {
    return Math.min(4, Math.max(Runtime.getRuntime().availableProcessors(), 2));
  }
}
