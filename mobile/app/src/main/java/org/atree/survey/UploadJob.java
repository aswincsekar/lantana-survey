package org.atree.survey;

import android.app.job.*;
import android.content.*;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class UploadJob extends JobService {
  private Thread worker;

  static void schedule(Context c) {
    if (c.getSharedPreferences("sync", 0).getString("url", "").isEmpty()) return;
    JobScheduler jobs = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
    if (jobs.getPendingJob(12) == null)
      jobs.schedule(
          new JobInfo.Builder(12, new ComponentName(c, UploadJob.class))
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
              .build());
  }

  public boolean onStartJob(JobParameters params) {
    worker =
        new Thread(
            () -> {
              boolean retry = false;
              try (Visits visits = new Visits(this)) {
                SharedPreferences prefs = getSharedPreferences("sync", 0);
                String url = prefs.getString("url", ""), token = prefs.getString("key", "");
                String id;
                while (!Thread.currentThread().isInterrupted() && (id = visits.pending()) != null) {
                  HttpURLConnection conn =
                      (HttpURLConnection) new URL(url + "/visits/" + id).openConnection();
                  try {
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    byte[] body = visits.read(id).toString().getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = conn.getOutputStream()) {
                      out.write(body);
                    }
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                      retry = true;
                      break;
                    }
                    visits.acknowledged(id);
                  } finally {
                    conn.disconnect();
                  }
                }
              } catch (Exception e) {
                retry = true;
              }
              if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
            },
            "field-sync");
    worker.start();
    return true;
  }

  public boolean onStopJob(JobParameters p) {
    if (worker != null) worker.interrupt();
    return true;
  }
}
