package org.atree.survey;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** USB-readable copies of the photos and model outputs in the private visit journal. */
final class JournalFiles {
  static File directory(Context context) throws IOException {
    for (File dir : context.getExternalMediaDirs()) {
      if (dir != null
          && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(dir))) {
        File root = new File(dir, "journals");
        Files.createDirectories(root.toPath());
        return root;
      }
    }
    throw new IOException("Phone storage is unavailable");
  }

  static void backfill(Context context, Visits visits) throws Exception {
    File root = directory(context);
    int recovered = JournalRestore.restore(root, visits);
    recovered += JournalRestore.restore(new File(root.getParentFile(), "restore-inbox"), visits);
    if (recovered > 0)
      android.util.Log.i("Fieldbook", "Recovered " + recovered + " local visits from USB archive");
    JSONArray records = visits.list();
    for (int i = 0; i < records.length(); i++) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("Copy interrupted");
      String id = records.getJSONObject(i).getString("id");
      write(root, visits, id, records.getJSONObject(i).optBoolean("hasCloseup"));
    }
    android.util.Log.i("Fieldbook", "USB journal ready: " + records.length() + " visits");
    recheckLegacy(context, root, records);
  }

  static JSONObject readCheck(Context context, String id) {
    try {
      File folder = new File(directory(context), UUID.fromString(id).toString());
      File file = new File(folder, "photo-check.json");
      if (!file.isFile() || file.length() > 4096) return null;
      return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    } catch (Exception unavailable) {
      return null;
    }
  }

  private static void recheckLegacy(Context context, File root, JSONArray records)
      throws Exception {
    Segmenter model = null;
    int count = 0, matched = 0;
    try {
      for (int i = 0; i < records.length(); i++) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Recheck interrupted");
        JSONObject record = records.getJSONObject(i);
        if (record.has("identity")) continue;
        String id = record.getString("id");
        if (model == null) model = new Segmenter(context);
        JSONObject cached = readCheck(context, id);
        if (cached != null && model.version.equals(cached.optString("model"))) continue;
        File folder = new File(root, UUID.fromString(id).toString());
        android.graphics.Bitmap bitmap =
            android.graphics.BitmapFactory.decodeFile(new File(folder, "photo.jpg").getPath());
        if (bitmap == null) throw new IOException("Unreadable journal photograph");
        Segmenter.Prediction pred = model.predict(bitmap);
        JSONObject result =
            new JSONObject()
                .put("model", model.version)
                .put("source", "Saved overview photograph")
                .put("identity", pred.eligible ? "likely_lantana" : "unidentified")
                .put("cover", pred.eligible ? pred.cover : JSONObject.NULL);
        File temp = new File(folder, ".photo-check.pending");
        Files.write(temp.toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(
            temp.toPath(),
            new File(folder, "photo-check.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        count++;
        if (pred.eligible) matched++;
      }
    } finally {
      if (model != null) model.close();
    }
    if (count > 0)
      android.util.Log.i(
          "Fieldbook",
          "Legacy photo recheck: " + count + " photos, " + matched + " likely Lantana");
  }

  static synchronized void write(File root, Visits visits, String id, boolean hasCloseup)
      throws IOException, JSONException {
    File target = new File(root, UUID.fromString(id).toString());
    if (new File(target, "photo.jpg").length() > 0
        && new File(target, "mask.png").length() > 0
        && (!hasCloseup || new File(target, "closeup.jpg").length() > 0)
        && new File(target, "visit.json").length() > 0) return;
    JSONObject record = visits.read(id);
    if (record == null) throw new IOException("Missing visit");
    File stage = new File(root, "." + id + ".pending");
    Files.createDirectories(stage.toPath());
    Files.write(
        new File(stage, "photo.jpg").toPath(),
        Base64.decode(record.getString("photo"), Base64.DEFAULT));
    Files.write(
        new File(stage, "mask.png").toPath(),
        Base64.decode(record.getString("mask"), Base64.DEFAULT));
    if (hasCloseup)
      Files.write(
          new File(stage, "closeup.jpg").toPath(),
          Base64.decode(record.getString("closeup"), Base64.DEFAULT));
    JSONObject summary = new JSONObject();
    for (String key :
        new String[] {
          "id",
          "patch",
          "at",
          "phase",
          "source",
          "identity",
          "identificationReason",
          "model",
          "frames",
          "hasCloseup",
          "cover",
          "review",
          "depth",
          "height"
        }) {
      if (record.has(key)) summary.put(key, record.get(key));
    }
    Files.write(
        new File(stage, "visit.json").toPath(),
        summary.toString(2).getBytes(StandardCharsets.UTF_8));
    if (!target.exists()) {
      Files.move(stage.toPath(), target.toPath());
    } else {
      // Restore a partial copy, e.g. when a file was moved off the phone.
      for (String name :
          hasCloseup
              ? new String[] {"photo.jpg", "mask.png", "visit.json", "closeup.jpg"}
              : new String[] {"photo.jpg", "mask.png", "visit.json"}) {
        Files.move(
            new File(stage, name).toPath(),
            new File(target, name).toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      Files.delete(stage.toPath());
    }
  }
}
