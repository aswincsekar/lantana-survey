package org.atree.survey;

import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.*;

/** Rebuild missing local records from complete USB exports without changing newer visits. */
final class JournalRestore {
  static int restore(File folder, Visits visits) throws Exception {
    File[] dirs = folder.listFiles(File::isDirectory);
    if (dirs == null) return 0;
    int count = 0;
    for (File dir : dirs) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("Restore interrupted");
      try {
        String id = UUID.fromString(dir.getName()).toString();
        if (!id.equals(dir.getName()) || visits.contains(id)) continue;
        File summary = new File(dir, "visit.json");
        if (!summary.isFile() || summary.length() > 8192) continue;
        JSONObject meta =
            new JSONObject(
                new String(Files.readAllBytes(summary.toPath()), StandardCharsets.UTF_8));
        if (!id.equals(meta.getString("id"))
            || !UUID.fromString(meta.getString("patch")).toString().equals(meta.getString("patch"))
            || meta.getLong("at") < 0
            || meta.getInt("frames") < 1
            || !meta.has("cover")) continue;
        meta.put("restoredFromUsb", true);
        String photo = readImage(dir, "photo.jpg", "image/jpeg"),
            mask = readImage(dir, "mask.png", "image/png");
        String close =
            meta.optBoolean("hasCloseup") ? readImage(dir, "closeup.jpg", "image/jpeg") : null;
        if (visits.restore(meta, photo, mask, close)) count++;
      } catch (IllegalArgumentException | JSONException | IOException invalid) {
        Log.w("Fieldbook", "Skipped incomplete journal archive");
      }
    }
    return count;
  }

  static String readImage(File dir, String name, String mime) throws IOException {
    File f = new File(dir, name);
    if (!f.isFile() || f.length() < 8 || f.length() > 2000000)
      throw new IOException("Missing image");
    byte[] bytes = Files.readAllBytes(f.toPath());
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    if (!mime.equals(o.outMimeType)
        || o.outWidth <= 0
        || o.outHeight <= 0
        || o.outWidth > 4096
        || o.outHeight > 4096) throw new IOException("Invalid image");
    return Base64.encodeToString(bytes, Base64.NO_WRAP);
  }
}
