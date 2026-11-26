package org.atree.survey;

import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;

final class Segmenter implements AutoCloseable {
  final OrtEnvironment env = OrtEnvironment.getEnvironment();
  final OrtSession model;
  final String version;
  static final int SIDE = 224;
  static final float[] MEAN = {.485f, .456f, .406f}, STD = {.229f, .224f, .225f};

  Segmenter(Context context) throws Exception {
    try (InputStream in = context.getAssets().open("lantana.onnx");
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      byte[] bytes = new byte[in.available()];
      new java.io.DataInputStream(in).readFully(bytes);
      StringBuilder id = new StringBuilder("squeezenet-");
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
        id.append(String.format(Locale.US, "%02x", b));
      version = id.substring(0, 22);
      opts.setIntraOpNumThreads(2);
      model = env.createSession(bytes, opts);
    }
  }

  static final class Prediction {
    Bitmap frame, mask;
    double cover, sharpness, ambiguous;
    Double height;
    float[][] probabilities;
    int[] gray;
  }

  Prediction predict(Bitmap frame) throws OrtException {
    int n = SIDE * SIDE;
    int[] pixels = new int[n];
    Bitmap.createScaledBitmap(frame, SIDE, SIDE, true).getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
    float[] input = new float[n * 3];
    double edges = 0;
    for (int i = 0; i < n; i++) {
      int rgb = pixels[i];
      for (int c = 0; c < 3; c++)
        input[c * n + i] = (((rgb >> (16 - 8 * c)) & 255) / 255f - MEAN[c]) / STD[c];
      if (i % SIDE > 0 && i >= SIDE)
        edges +=
            Math.abs(Color.green(rgb) - Color.green(pixels[i - 1]))
                + Math.abs(Color.green(rgb) - Color.green(pixels[i - SIDE]));
    }
    Prediction p = new Prediction();
    p.frame = frame;
    p.sharpness = edges / (2 * n);
    try (OnnxTensor tensor =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[] {1, 3, SIDE, SIDE});
        OrtSession.Result result = model.run(Collections.singletonMap("data", tensor))) {
      p.probabilities = ((float[][][][]) result.get(0).getValue())[0][0];
    }
    int h = p.probabilities.length, w = p.probabilities[0].length;
    int[] overlay = new int[w * h];
    p.gray = new int[w * h];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
        p.gray[y * w + x] = Color.green(pixels[(y * SIDE / h) * SIDE + x * SIDE / w]);
    int foreground = 0, uncertain = 0;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        float score = p.probabilities[y][x];
        if (score >= .5) {
          overlay[y * w + x] = 0xAA4285F4;
          foreground++;
        }
        if (score > .25 && score < .75) uncertain++;
      }
    p.mask = Bitmap.createBitmap(overlay, w, h, Bitmap.Config.ARGB_8888);
    p.cover = 100.0 * foreground / (w * h);
    p.ambiguous = (double) uncertain / (w * h);
    return p;
  }

  /** Align neighbouring thumbnails by translation, then compare masks in their shared region. */
  static double agreement(Prediction a, Prediction b) {
    int h = a.probabilities.length, w = a.probabilities[0].length;
    int bx = 0, by = 0;
    double best = Double.MAX_VALUE;
    for (int dy = -4; dy <= 4; dy++)
      for (int dx = -4; dx <= 4; dx++) {
        int error = 0, count = 0;
        for (int y = 4; y < h - 4; y += 2)
          for (int x = 4; x < w - 4; x += 2) {
            error += Math.abs(a.gray[y * w + x] - b.gray[(y + dy) * w + x + dx]);
            count++;
          }
        double mae = (double) error / count + .01 * (Math.abs(dx) + Math.abs(dy));
        if (mae < best) {
          best = mae;
          bx = dx;
          by = dy;
        }
      }
    if (best > 22) return -1;
    int intersection = 0, union = 0;
    for (int y = Math.max(0, -by); y < Math.min(h, h - by); y++)
      for (int x = Math.max(0, -bx); x < Math.min(w, w - bx); x++) {
        boolean before = a.probabilities[y][x] >= .5, after = b.probabilities[y + by][x + bx] >= .5;
        if (before || after) union++;
        if (before && after) intersection++;
      }
    return union == 0 ? 1 : (double) intersection / union;
  }

  public void close() throws OrtException {
    model.close();
  }
}
