package org.atree.survey;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class JournalFileJob extends JobService {
  private Thread worker;

  static void schedule(Context context) {
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler.getPendingJob(13) == null) {
      scheduler.schedule(
          new JobInfo.Builder(13, new ComponentName(context, JournalFileJob.class))
              .setOverrideDeadline(0)
              .build());
    }
  }

  public static final class Updated extends BroadcastReceiver {
    public void onReceive(Context context, Intent intent) {
      if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) schedule(context);
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    worker =
        new Thread(
            () -> {
              boolean retry = false;
              try (Visits visits = new Visits(this)) {
                JournalFiles.backfill(this, visits);
                org.json.JSONArray records = visits.list();
                if (records.length() > 0 && !Thread.currentThread().isInterrupted()) {
                  try {
                    PhotoDepth.generate(this, records.getJSONObject(0).getString("id"));
                    for (int i = 0; i < Math.min(2, records.length()); i++)
                      reviewSavedPhoto(records.getJSONObject(i));
                  } catch (Exception | LinkageError e) {
                    Log.w("Fieldbook", "Latest photo depth unavailable", e);
                  }
                }
              } catch (Exception e) {
                Log.w("Fieldbook", "USB journal copy deferred", e);
                retry = true;
              }
              if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
            },
            "journal-files");
    worker.start();
    return true;
  }

  // Recheck recent field evidence locally. Preserve the recorded visit and its original estimates.
  void reviewSavedPhoto(JSONObject visit) throws Exception {
    File dir =
        new File(JournalFiles.directory(this), UUID.fromString(visit.getString("id")).toString());
    File audit = new File(dir, "depth-review-v1.json");
    if (audit.isFile()) return;
    int supported = 0, overview = 0;
    File sweep = new File(dir, "sweep/sweep.json");
    if (sweep.isFile() && sweep.length() < 256000) {
      JSONArray frames =
          new JSONObject(new String(Files.readAllBytes(sweep.toPath()), StandardCharsets.UTF_8))
              .getJSONArray("frames");
      for (int i = 0; i < frames.length(); i++) {
        JSONObject frame = frames.getJSONObject(i);
        if (!"overview".equals(frame.optString("phase"))) continue;
        overview++;
        if (frame.optBoolean("frameEligible")) supported++;
      }
    }
    Bitmap photo = BitmapFactory.decodeFile(new File(dir, "photo.jpg").getPath());
    if (photo == null) return;
    JSONObject report =
        new JSONObject()
            .put("source", visit.getString("source"))
            .put("identity", visit.optString("identity", "legacy"))
            .put("overviewFrames", overview)
            .put("eligibleOverviewFrames", supported);
    try (Segmenter model = new Segmenter(this)) {
      Segmenter.Prediction p = model.predict(photo);
      report
          .put("rgbSpeciesScore", p.speciesScore)
          .put("rgbEligible", p.eligible)
          .put("method", model.version + DepthOutline.VERSION);
      if (p.eligible
          && "likely_lantana".equals(visit.optString("identity"))
          && !visit.optString("model").endsWith(DepthOutline.VERSION)) {
        PhotoDepth.Result relative = PhotoDepth.estimate(this, photo);
        DepthOutline outline = relative.refine(p);
        PhotoDepth.write(
            new File(dir, "depth-review-v1.png"),
            Bitmap.createBitmap(
                outline.overlay, outline.width, outline.height, Bitmap.Config.ARGB_8888));
        report.put("proposedCover", outline.cover());
      }
    }
    File tmp = File.createTempFile("depth-review-", ".tmp", dir);
    try {
      Files.write(tmp.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
      Files.move(
          tmp.toPath(),
          audit.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      tmp.delete();
    }
    Log.i("Fieldbook", "Capture review " + report);
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    if (worker != null) worker.interrupt();
    return true;
  }
}
