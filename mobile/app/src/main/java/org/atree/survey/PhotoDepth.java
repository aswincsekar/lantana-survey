package org.atree.survey;

import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.util.*;

/** Relative inverse depth for one photograph. Values have no physical scale. */
final class PhotoDepth {
  private static final int LONG_EDGE = 420;
  private static final String OUTPUT = "relative-depth-dav2-q4-v1.png";

  static synchronized File generate(Context context, String id) throws Exception {
    File folder = new File(JournalFiles.directory(context), UUID.fromString(id).toString());
    File output = new File(folder, OUTPUT);
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    if (output.isFile()) {
      BitmapFactory.decodeFile(output.getPath(), bounds);
      if (bounds.outWidth > 0 && bounds.outHeight > 0) return output;
    }

    Bitmap photo = BitmapFactory.decodeFile(new File(folder, "photo.jpg").getPath());
    if (photo == null) throw new IOException("Journal photograph unavailable");
    long started = SystemClock.elapsedRealtime();
    Result result = estimate(context, photo);
    write(output, result.map);
    Log.i(
        "Fieldbook",
        "Single-photo depth saved "
            + result.map.getWidth()
            + "x"
            + result.map.getHeight()
            + ", "
            + (SystemClock.elapsedRealtime() - started)
            + " ms");
    return output;
  }

  static final class Result {
    final Bitmap map;
    final int[] rgb;
    final float[] depth;
    final int width, height;

    Result(Bitmap map, int[] rgb, float[] depth, int width, int height) {
      this.map = map;
      this.rgb = rgb;
      this.depth = depth;
      this.width = width;
      this.height = height;
    }

    DepthOutline refine(Segmenter.Prediction p) {
      if (!p.eligible) throw new IllegalArgumentException("No eligible Lantana observation");
      return new DepthOutline(p.probabilities, Segmenter.MASK_THRESHOLD, rgb, depth, width, height);
    }
  }

  static Result estimate(Context context, Bitmap photo) throws Exception {
    double scale = (double) LONG_EDGE / Math.max(photo.getWidth(), photo.getHeight());
    int width = Math.max(14, 14 * (int) Math.round(photo.getWidth() * scale / 14));
    int height = Math.max(14, 14 * (int) Math.round(photo.getHeight() * scale / 14));
    Bitmap input = Bitmap.createScaledBitmap(photo, width, height, true);
    int n = width * height;
    int[] pixels = new int[n];
    input.getPixels(pixels, 0, width, 0, 0, width, height);
    float[] rgb = new float[3 * n];
    for (int i = 0; i < n; i++)
      for (int channel = 0; channel < 3; channel++)
        rgb[channel * n + i] =
            (((pixels[i] >> (16 - 8 * channel)) & 255) / 255f - Segmenter.MEAN[channel])
                / Segmenter.STD[channel];

    OrtEnvironment env = OrtEnvironment.getEnvironment();
    float[] depth = new float[n];
    try (InputStream asset = context.getAssets().open("photo-depth.onnx");
        OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      byte[] weights = new byte[asset.available()];
      new DataInputStream(asset).readFully(weights);
      options.setIntraOpNumThreads(2);
      try (OrtSession session = env.createSession(weights, options);
          OnnxTensor tensor =
              OnnxTensor.createTensor(
                  env, FloatBuffer.wrap(rgb), new long[] {1, 3, height, width});
          OrtSession.Result result =
              session.run(Collections.singletonMap("pixel_values", tensor))) {
        OnnxTensor prediction = (OnnxTensor) result.get("predicted_depth").get();
        if (!Arrays.equals(prediction.getInfo().getShape(), new long[] {1, height, width}))
          throw new IOException("Unexpected depth dimensions");
        prediction.getFloatBuffer().get(depth);
      }
    }
    for (float d : depth) if (!Float.isFinite(d)) throw new IOException("Invalid depth prediction");
    float[] sorted = depth.clone();
    Arrays.sort(sorted);
    float low = sorted[n / 50], high = sorted[n - 1 - n / 50];
    if (high - low < 1e-5f) throw new IOException("Insufficient depth contrast");
    int[] colors = new int[n];
    for (int i = 0; i < n; i++) {
      float t = Math.min(1, Math.max(0, (depth[i] - low) / (high - low)));
      depth[i] = t;
      colors[i] =
          Color.rgb(Math.round(36 + 219 * t), Math.round(86 + 95 * t), Math.round(202 - 154 * t));
    }
    Bitmap map = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
    map =
        Bitmap.createScaledBitmap(
            map,
            Math.max(1, (int) Math.round(photo.getWidth() * scale)),
            Math.max(1, (int) Math.round(photo.getHeight() * scale)),
            true);
    return new Result(map, pixels, depth, width, height);
  }

  static void saveMap(File folder, Result result) throws IOException {
    write(new File(folder, OUTPUT), result.map);
  }

  static void write(File output, Bitmap map) throws IOException {
    File folder = output.getParentFile();
    File temp = File.createTempFile("relative-depth-", ".tmp", folder);
    try {
      try (FileOutputStream stream = new FileOutputStream(temp)) {
        if (!map.compress(Bitmap.CompressFormat.PNG, 100, stream))
          throw new IOException("Could not encode depth map");
      }
      Files.move(
          temp.toPath(),
          output.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      temp.delete();
    }
  }
}
