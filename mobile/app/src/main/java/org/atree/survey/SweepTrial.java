package org.atree.survey;

import android.graphics.*;
import com.google.ar.core.Pose;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/**
 * Local field experiment. All candidate masks are compared in the original overview coordinates.
 */
final class SweepTrial {
  static final int BLUE = 0xaa4285f4;
  final ArrayList<View> frames = new ArrayList<>();

  static final class View {
    final long ms, cameraTime;
    final int w, h;
    final float[][] scores;
    final int[] gray;
    final boolean eligible, close;
    final byte[] jpeg;
    final SweepGeometry geometry;

    View(Segmenter.Prediction p, long ms, boolean close) {
      this.ms = ms;
      this.close = close;
      scores = p.probabilities;
      h = scores.length;
      w = scores[0].length;
      eligible = p.eligible;
      gray = p.gray;
      geometry = p.geometry;
      cameraTime = p.cameraTime;
      jpeg = p.jpeg == null ? jpeg(p.frame) : p.jpeg;
    }
  }

  static byte[] jpeg(Bitmap bitmap) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out))
      throw new IllegalStateException("JPEG encode failed");
    return out.toByteArray();
  }

  void add(Segmenter.Prediction p, long ms, boolean close) {
    p.sweepIndex = frames.size();
    frames.add(new View(p, ms, close));
  }

  static boolean[] baseline(View view) {
    boolean[] cells = new boolean[view.w * view.h];
    for (int i = 0; i < cells.length; i++)
      cells[i] = view.scores[i / view.w][i % view.w] >= Segmenter.MASK_THRESHOLD;
    return cells;
  }

  static boolean depthGap(View ref, boolean[] base, int i) {
    SweepGeometry g = ref.geometry;
    if (g == null || g.mm[i] == 0) return false;
    int x = i % ref.w, y = i / ref.w, nearer = 0, known = 0;
    for (int yy = Math.max(0, y - 2); yy <= Math.min(ref.h - 1, y + 2); yy++)
      for (int xx = Math.max(0, x - 2); xx <= Math.min(ref.w - 1, x + 2); xx++) {
        int j = yy * ref.w + xx;
        if (!base[j] || g.mm[j] == 0) continue;
        known++;
        if (g.mm[i] - g.mm[j] > Math.max(180, g.mm[j] * .08)) nearer++;
      }
    return nearer >= 2 && nearer * 2 > known;
  }

  boolean[] fuse(int index, JSONObject report) throws JSONException {
    View ref = frames.get(index);
    boolean[] base = baseline(ref), out = base.clone();
    int[] yes = new int[out.length], seen = new int[out.length];
    int used = 0, geometryVotes = 0, filled = 0, depthVeto = 0;
    for (View source : frames) {
      if (source == ref
          || !source.eligible
          || source.close
          || Math.abs(ref.ms - source.ms) > 1800
          || source.w != ref.w
          || source.h != ref.h) continue;
      int[] shift = Segmenter.translation(ref.gray, source.gray, ref.w, ref.h);
      Pose transform =
          ref.geometry != null && source.geometry != null
              ? source.geometry.pose.inverse().compose(ref.geometry.pose)
              : null;
      boolean contributed = false;
      for (int i = 0; i < out.length; i++) {
        if (base[i]) continue;
        int j = transform == null ? -1 : ref.geometry.project(i, source.geometry, transform);
        if (j == -2) continue;
        if (j >= 0) geometryVotes++;
        else {
          if (shift == null) continue;
          int x = i % ref.w + shift[0], y = i / ref.w + shift[1];
          if (x < 0 || y < 0 || x >= ref.w || y >= ref.h) continue;
          j = y * ref.w + x;
        }
        if (Math.abs(ref.gray[i] - source.gray[j]) > 22) continue;
        contributed = true;
        seen[i]++;
        if (source.scores[j / ref.w][j % ref.w] >= Segmenter.MASK_THRESHOLD) yes[i]++;
      }
      if (contributed) used++;
    }
    for (int i = 0; i < out.length; i++) {
      if (base[i] || yes[i] < 2 || yes[i] * 3 < seen[i] * 2) continue;
      // Only repair gaps adjacent to original foreground, never grow an isolated prediction.
      int x = i % ref.w, y = i / ref.w, adjacent = 0;
      for (int yy = Math.max(0, y - 1); yy <= Math.min(ref.h - 1, y + 1); yy++)
        for (int xx = Math.max(0, x - 1); xx <= Math.min(ref.w - 1, x + 1); xx++)
          if (base[yy * ref.w + xx]) adjacent++;
      if (adjacent < 2) continue;
      if (depthGap(ref, base, i)) {
        depthVeto++;
        continue;
      }
      out[i] = true;
      filled++;
    }
    report
        .put("supportingViews", used)
        .put("geometricCorrespondences", geometryVotes)
        .put("cellsRestored", filled)
        .put("depthVetoes", depthVeto);
    return out;
  }

  static Bitmap bitmap(boolean[] values, int w, int h) {
    int[] pixels = new int[values.length];
    for (int i = 0; i < pixels.length; i++) if (values[i]) pixels[i] = BLUE;
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
  }

  static void png(File dir, String name, Bitmap bitmap) throws IOException {
    try (OutputStream out = new FileOutputStream(new File(dir, name))) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        throw new IOException("PNG encode failed");
    }
  }

  private JSONArray saveFrames(File dir) throws Exception {
    JSONArray list = new JSONArray();
    for (int i = 0; i < frames.size(); i++) {
      View v = frames.get(i);
      String key = String.format(Locale.ROOT, "%03d", i);
      Files.write(new File(dir, key + ".jpg").toPath(), v.jpeg);
      byte[] scores = new byte[v.w * v.h];
      for (int j = 0; j < scores.length; j++)
        scores[j] = (byte) Math.round(255 * v.scores[j / v.w][j % v.w]);
      Files.write(new File(dir, key + ".scores.u8").toPath(), scores);
      JSONObject item =
          new JSONObject()
              .put("key", key)
              .put("elapsedMs", v.ms)
              .put("cameraTimestampNs", v.cameraTime)
              .put("phase", v.close ? "closeup" : "overview")
              .put("frameEligible", v.eligible);
      if (v.geometry != null) {
        item.put("camera", v.geometry.metadata());
        if (v.geometry.valid > 0) {
          ByteBuffer depth = ByteBuffer.allocate(scores.length * 2).order(ByteOrder.LITTLE_ENDIAN);
          depth.asShortBuffer().put(v.geometry.mm);
          Files.write(new File(dir, key + ".mm.u16le").toPath(), depth.array());
          Files.write(new File(dir, key + ".confidence.u8").toPath(), v.geometry.confidence);
        }
      }
      list.put(item);
    }
    return list;
  }

  JSONObject depthSummary(int reference, boolean measured) throws JSONException {
    return depthSummary(reference, measured, null);
  }

  JSONObject depthSummary(int reference, boolean measured, float[][] refinedMask)
      throws JSONException {
    int selected = reference;
    View ref = frames.get(reference);
    if (ref.geometry == null || ref.geometry.valid < 25) {
      for (int i = 0; i < frames.size(); i++) {
        View v = frames.get(i), current = frames.get(selected);
        if (!v.close
            && v.geometry != null
            && v.geometry.valid > (current.geometry == null ? 0 : current.geometry.valid))
          selected = i;
      }
    }
    View view = frames.get(selected);
    JSONObject stats =
        view.geometry == null
            ? new JSONObject().put("validCells", 0).put("totalCells", view.w * view.h)
            : view.geometry.measurements(
                refinedMask != null && selected == reference ? refinedMask : view.scores,
                measured && view.eligible && selected == reference);
    return stats.put("frame", selected);
  }

  static String depthOverlay(File folder, int index, int width, int height) throws IOException {
    if (index < 0 || index > 99 || width <= 0 || height <= 0 || width > 256 || height > 256)
      return "";
    File file = new File(folder, String.format(Locale.ROOT, "sweep/%03d.mm.u16le", index));
    if (file.length() != 2L * width * height) return "";
    byte[] bytes = Files.readAllBytes(file.toPath());
    if (bytes.length != 2 * width * height) return "";
    short[] data = new short[width * height];
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data);
    return FieldActivity.encoded(SweepGeometry.heatmap(data, width, height), true);
  }

  JSONObject save(File folder, int index, boolean measured, JSONObject depth) throws Exception {
    File stage = new File(folder, ".sweep-pending");
    Files.createDirectories(stage.toPath());
    JSONObject report = new JSONObject();
    try {
      View ref = frames.get(index);
      JSONArray list = saveFrames(stage);
      int depthFrames = 0;
      for (View f : frames) if (f.geometry != null && f.geometry.valid > 0) depthFrames++;
      report
          .put("frames", list)
          .put("depthFrames", depthFrames)
          .put("reference", index)
          .put("gridWidth", ref.w)
          .put("gridHeight", ref.h)
          .put("measured", measured);
      boolean[] seed = null;
      if (measured && ref.eligible) {
        seed = fuse(index, report);
        png(stage, "single.png", bitmap(baseline(ref), ref.w, ref.h));
        png(stage, "multiview.png", bitmap(seed, ref.w, ref.h));
      }
      report.put("depth", depth);
      Files.write(
          new File(stage, "sweep.json").toPath(),
          report.toString(2).getBytes(StandardCharsets.UTF_8));
      Files.move(stage.toPath(), new File(folder, "sweep").toPath());
      return report;
    } finally {
      File[] incomplete = stage.listFiles();
      if (incomplete != null) {
        for (File f : incomplete) f.delete();
        stage.delete();
      }
    }
  }

  static JSONObject display(File folder) throws Exception {
    File dir = new File(folder, "sweep"), manifest = new File(dir, "sweep.json");
    if (!manifest.isFile() || manifest.length() > 256000) return null;
    JSONObject report =
        new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
    JSONArray frames = report.getJSONArray("frames");
    report.put("count", frames.length());
    // Old sweeps already contain raw depth grids. Pick a paired overview for replay.
    if (!report.has("depth")) {
      int best = report.getInt("reference"), valid = 0;
      for (int i = 0; i < frames.length(); i++) {
        JSONObject f = frames.getJSONObject(i), camera = f.optJSONObject("camera");
        if (camera != null
            && "overview".equals(f.optString("phase"))
            && camera.optInt("validDepthCells") > valid) {
          valid = camera.optInt("validDepthCells");
          best = i;
        }
      }
      report.put(
          "depth",
          new JSONObject()
              .put("frame", best)
              .put("validCells", valid)
              .put("totalCells", report.getInt("gridWidth") * report.getInt("gridHeight")));
    }
    report.remove("frames");
    return report;
  }
}
