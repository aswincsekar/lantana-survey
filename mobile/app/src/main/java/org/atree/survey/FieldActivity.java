package org.atree.survey;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public final class FieldActivity extends Activity implements LocationListener, SensorEventListener {
  WebView web;
  HandlerThread thread;
  Handler work, analysis;
  HandlerThread analysisThread;
  FrameLayout root;
  volatile ScanView scanView;
  String referenceVisit = "";
  volatile boolean active;
  Segmenter model;
  SweepCamera camera;
  Visits visits;
  Segmenter.Prediction best;
  SweepTrial trial;
  final ArrayList<Double> covers = new ArrayList<>(), heights = new ArrayList<>();
  Segmenter.Prediction preceding;
  int matches, inconsistent, supportedFrames, streak, closeFrames;
  Segmenter.Prediction supportedOverview, detailFrame;
  Location supportedLocation;
  Double supportedBearing;
  boolean closeup;
  long closeStarted;
  long started;
  volatile Location fix;
  volatile Double bearing;
  Location bestLocation;
  Double bestBearing;
  String patch, phase;
  volatile boolean scanning;
  boolean awaitingLocationPermission, resumeScan;
  LocationManager locations;
  SensorManager sensors;

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    thread = new HandlerThread("field-analysis");
    thread.start();
    work = new Handler(thread.getLooper());
    analysisThread = new HandlerThread("field-vision");
    analysisThread.start();
    analysis = new Handler(analysisThread.getLooper());
    visits = new Visits(this);
    JournalFileJob.schedule(this);
    locations = (LocationManager) getSystemService(LOCATION_SERVICE);
    sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
    web = new WebView(this);
    web.setBackgroundColor(0xfff8faff);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setAllowContentAccess(false);
    web.getSettings().setAllowFileAccess(false);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri uri = req.getUrl();
            if ("data".equals(uri.getScheme())) return null;
            String path = uri.getPath();
            if ("fieldbook.invalid".equals(uri.getHost())
                && "https".equals(uri.getScheme())
                && Arrays.asList(
                        "/index.html",
                        "/field.js",
                        "/field.css",
                        "/shrub.jpg",
                        "/vanarekha.png",
                        "/google-sans.ttf")
                    .contains(path)) {
              String mime =
                  path.endsWith(".js")
                      ? "application/javascript"
                      : path.endsWith(".css")
                          ? "text/css"
                          : path.endsWith(".png")
                              ? "image/png"
                              : path.endsWith(".ttf")
                                  ? "font/ttf"
                                  : path.endsWith(".jpg") ? "image/jpeg" : "text/html";
              try {
                return new WebResourceResponse(mime, "UTF-8", getAssets().open(path.substring(1)));
              } catch (IOException unavailable) {
                /* Fall through to local 404. */
              }
            }
            return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Missing",
                Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
          }

          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return true;
          }
        });
    web.addJavascriptInterface(new Bridge(), "Field");
    root = new FrameLayout(this);
    root.addView(web);
    setContentView(root);
    web.loadUrl("https://fieldbook.invalid/index.html");
    UploadJob.schedule(this);
  }

  void js(String expression) {
    runOnUiThread(
        () -> {
          if (!isDestroyed()) web.evaluateJavascript(expression, null);
        });
  }

  void message(String text) {
    js("notice(" + JSONObject.quote(text) + ")");
  }

  void addPhotoCheck(JSONObject record) throws JSONException {
    if (record.has("identity")) return;
    JSONObject check = JournalFiles.readCheck(this, record.getString("id"));
    if (check != null) record.put("photoCheck", check);
  }

  class Bridge {
    @JavascriptInterface
    public String sweepFrame(String id, int index) {
      try {
        if (index < 0 || index > 99) return "";
        File folder =
            new File(JournalFiles.directory(FieldActivity.this), UUID.fromString(id).toString());
        File f = new File(folder, String.format(Locale.ROOT, "sweep/%03d.jpg", index));
        if (!f.isFile() || f.length() > 2000000) return "";
        return Base64.encodeToString(java.nio.file.Files.readAllBytes(f.toPath()), Base64.NO_WRAP);
      } catch (Exception unavailable) {
        return "";
      }
    }

    @JavascriptInterface
    public String sweepDepth(String id, int index, int width, int height) {
      try {
        File folder =
            new File(JournalFiles.directory(FieldActivity.this), UUID.fromString(id).toString());
        return SweepTrial.depthOverlay(folder, index, width, height);
      } catch (Exception unavailable) {
        return "";
      }
    }

    @JavascriptInterface
    public void photoDepth(String id) {
      work.post(
          () -> {
            String encodedMap = "";
            try {
              File map = PhotoDepth.generate(FieldActivity.this, id);
              encodedMap =
                  Base64.encodeToString(
                      java.nio.file.Files.readAllBytes(map.toPath()), Base64.NO_WRAP);
            } catch (Exception | LinkageError e) {
              android.util.Log.w("Fieldbook", "Photo depth unavailable", e);
            }
            js(
                "photoDepthResult("
                    + JSONObject.quote(id)
                    + ","
                    + JSONObject.quote(encodedMap)
                    + ")");
          });
    }

    @JavascriptInterface
    public void setupDepth() {
      runOnUiThread(
          () -> {
            try {
              if (com.google.ar.core.ArCoreApk.getInstance()
                      .requestInstall(FieldActivity.this, true)
                  == com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED)
                message("Google Play Services for AR is ready. Start a sweep to check depth.");
            } catch (Exception unavailable) {
              message("AR setup unavailable on this device.");
            }
          });
    }

    @JavascriptInterface
    public String visits() {
      try {
        JSONArray saved = visits.list();
        for (int i = 0; i < saved.length(); i++) addPhotoCheck(saved.getJSONObject(i));
        android.util.Log.i("Fieldbook", "Journal loaded, visits=" + saved.length());
        return saved.toString();
      } catch (Exception e) {
        return "[]";
      }
    }

    @JavascriptInterface
    public String visit(String id) {
      try {
        JSONObject record = visits.read(id);
        if (record == null) return "null";
        addPhotoCheck(record);
        try {
          JSONObject comparison =
              SweepTrial.display(
                  new File(
                      JournalFiles.directory(FieldActivity.this), UUID.fromString(id).toString()));
          if (comparison != null) record.put("sweepTrial", comparison);
        } catch (Exception unavailable) {
          android.util.Log.w("Fieldbook", "Sweep comparison unavailable", unavailable);
        }
        return record.toString();
      } catch (Exception e) {
        return "null";
      }
    }

    @JavascriptInterface
    public void scan(String previous, String stage, String visitId) {
      runOnUiThread(
          () -> {
            if (scanning) return;
            if (!Arrays.asList("Baseline", "After clearance", "Regrowth").contains(stage)) return;
            referenceVisit = visitId;
            patch =
                previous.isEmpty()
                    ? UUID.randomUUID().toString()
                    : UUID.fromString(previous).toString();
            phase = stage;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || (!awaitingLocationPermission
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED)) {
              awaitingLocationPermission = true;
              requestPermissions(
                  new String[] {
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                  },
                  9);
            } else begin();
          });
    }

    @JavascriptInterface
    public void cancel() {
      work.post(
          () -> {
            stopCamera();
            js("home()");
          });
    }

    @JavascriptInterface
    public void pick() {
      runOnUiThread(
          () -> {
            if (scanning) return;
            startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*"),
                41);
          });
    }

    @JavascriptInterface
    public void example() {
      work.post(
          () -> {
            if (scanning) return;
            try (InputStream in = getAssets().open("shrub.jpg")) {
              ensureModel();
              long start = SystemClock.elapsedRealtime();
              Segmenter.Prediction p = model.predict(BitmapFactory.decodeStream(in));
              android.util.Log.i(
                  "Fieldbook",
                  String.format(
                      Locale.US,
                      "Sample inference: %.1f%% cover, %dx%d mask, %d ms",
                      p.cover,
                      p.mask.getWidth(),
                      p.mask.getHeight(),
                      SystemClock.elapsedRealtime() - start));
              js(
                  "exampleResult("
                      + JSONObject.quote(encoded(p.frame, false))
                      + ","
                      + JSONObject.quote(p.eligible ? encoded(p.mask, true) : "")
                      + ","
                      + (p.eligible ? Double.toString(p.cover) : "null")
                      + ")");
            } catch (Exception e) {
              android.util.Log.e("Fieldbook", "Sample inference failed", e);
              message("Sample analysis failed.");
            }
          });
    }

    @JavascriptInterface
    public void setup() {
      runOnUiThread(() -> syncDialog());
    }
  }

  public void onRequestPermissionsResult(int req, String[] names, int[] results) {
    super.onRequestPermissionsResult(req, names, results);
    if (req == 9) {
      if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        if (active) begin();
        else resumeScan = true;
      } else message("Allow camera access to scan, or import a photograph.");
    }
  }

  void syncDialog() {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(1);
    box.setPadding(40, 16, 40, 12);
    EditText url = new EditText(this), key = new EditText(this);
    SharedPreferences prefs = getSharedPreferences("sync", 0);
    url.setHint("HTTPS dashboard URL");
    url.setText(prefs.getString("url", ""));
    key.setHint("Field team access token");
    key.setSingleLine();
    key.setInputType(129);
    key.setText(prefs.getString("key", ""));
    box.addView(url);
    box.addView(key);
    new AlertDialog.Builder(this)
        .setTitle("Connect field dashboard")
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Save",
            (d, w) -> {
              String endpoint = url.getText().toString().trim().replaceAll("/+$", "");
              Uri uri = Uri.parse(endpoint);
              if (!"https".equals(uri.getScheme())
                  || uri.getHost() == null
                  || uri.getUserInfo() != null
                  || key.length() == 0) {
                message("Enter an HTTPS address and team token.");
                return;
              }
              prefs
                  .edit()
                  .putString("url", endpoint)
                  .putString("key", key.getText().toString())
                  .apply();
              UploadJob.schedule(this);
              message("Sync scheduled when connected.");
            })
        .show();
  }

  void begin() {
    if (scanning || !active) return;
    listen();
    scanning = true;
    ScanView view =
        new ScanView(
            this,
            new ScanView.Actions() {
              public void ready(ScanView view, SurfaceTexture target) {
                startCamera(view, target);
              }

              public void cancel() {
                new Bridge().cancel();
              }

              public void lost(ScanView view, SurfaceTexture target) {
                work.post(
                    () -> {
                      try {
                        if (scanView == view) {
                          stopCamera();
                          js("home()");
                        }
                      } finally {
                        target.release();
                      }
                    });
              }
            });
    scanView = view;
    root.addView(view);
    web.setVisibility(android.view.View.INVISIBLE);
  }

  void startCamera(ScanView view, SurfaceTexture target) {
    work.post(
        () -> {
          try {
            if (scanView != view) return;
            if (!scanning || !active) {
              stopCamera();
              js("home()");
              return;
            }
            ensureModel();
            if (!referenceVisit.isEmpty()) {
              File file =
                  new File(
                      new File(
                          JournalFiles.directory(this), UUID.fromString(referenceVisit).toString()),
                      "photo.jpg");
              Bitmap ghost = BitmapFactory.decodeFile(file.getPath());
              runOnUiThread(
                  () -> {
                    if (scanView == view) view.ghost.setImageBitmap(ghost);
                  });
            }
            trial = new SweepTrial();
            best = null;
            preceding = null;
            matches = 0;
            inconsistent = 0;
            supportedFrames = streak = closeFrames = 0;
            supportedOverview = null;
            detailFrame = null;
            closeup = false;
            covers.clear();
            heights.clear();
            camera =
                new SweepCamera(
                    this,
                    work,
                    analysis,
                    target,
                    model,
                    new SweepCamera.Listener() {
                      public void ready(int w, int h) {
                        runOnUiThread(
                            () -> {
                              if (scanView != view) return;
                              view.aspect(w, h);
                            });
                      }

                      public void frame(Segmenter.Prediction p) {
                        receive(p);
                      }

                      public void error(String reason) {
                        fail(reason);
                      }
                    });
            camera.start();
            started = SystemClock.elapsedRealtime();
            work.postDelayed(timeout, 16000);
          } catch (Exception | LinkageError e) {
            android.util.Log.w("Fieldbook", "Camera startup failed", e);
            fail("Camera unavailable. Close other camera apps or import a photograph.");
          }
        });
  }

  final Runnable timeout =
      () -> {
        if (scanning) {
          if (best != null) finishScan(closeup && streak >= 3);
          else fail("No frames received. Try again with the camera unobstructed.");
        }
      };

  void ensureModel() throws Exception {
    if (model == null) model = new Segmenter(this);
  }

  void receive(Segmenter.Prediction p) {
    if (!scanning || !active) return;
    long elapsed = SystemClock.elapsedRealtime() - started;
    trial.add(p, elapsed, closeup);
    double overlap = preceding == null ? -1 : Segmenter.agreement(preceding, p);
    if (p.eligible) {
      streak = preceding != null && preceding.eligible && overlap >= .6 ? streak + 1 : 1;
    } else {
      streak = 0;
    }
    preceding = p;
    if (closeup) {
      closeFrames++;
      if (detailFrame == null
          || (p.eligible && !detailFrame.eligible)
          || (p.eligible == detailFrame.eligible && p.sharpness > detailFrame.sharpness))
        detailFrame = p;
    } else {
      covers.add(p.cover);
      if (p.height != null) heights.add(p.height);
      if (overlap >= 0) {
        matches++;
        if (overlap < .6) inconsistent++;
      }
      if (best == null || p.sharpness > best.sharpness) {
        best = p;
        bestLocation = fix;
        bestBearing = bearing;
      }
      if (p.eligible) supportedFrames++;
      if (streak >= 3 && betterOverview(p, supportedOverview)) {
        supportedOverview = p;
        supportedLocation = fix;
        supportedBearing = bearing;
      }
    }
    if (!closeup && elapsed >= 3000 && streak >= 3 && covers.size() >= 6 && hasOverview()) {
      finishScan(false);
      return;
    }
    if (!closeup && elapsed >= 6000 && covers.size() >= 4) {
      if (hasOverview()) {
        finishScan(false);
        return;
      }
      closeup = true;
      closeStarted = SystemClock.elapsedRealtime();
      preceding = null;
      streak = 0;
    }
    long closeElapsed = SystemClock.elapsedRealtime() - closeStarted;
    if (closeup && (closeElapsed >= 2000 && streak >= 3 || closeElapsed >= 5000)) {
      finishScan(streak >= 3);
      return;
    }
    String hint =
        closeup
            ? "Move closer to a leaf or flower cluster."
            : p.sharpness < 5
                ? "Hold still so the leaves come into focus."
                : p.eligible
                    ? "Possible Lantana. Hold steady while we check."
                    : "Keep the whole plant in view.";
    ScanView view = scanView;
    int percent =
        (int)
            (closeup
                ? 55 + Math.min(45, closeElapsed * 45 / 5000)
                : Math.min(55, elapsed * 55 / 6000));
    runOnUiThread(
        () -> {
          if (view == null || scanView != view) return;
          if (!hint.contentEquals(view.hint.getText())) view.hint.setText(hint);
          view.progress.setProgress(percent);
          view.overlay.setImageBitmap(p.eligible ? p.mask : null);
        });
  }

  static boolean betterOverview(Segmenter.Prediction candidate, Segmenter.Prediction previous) {
    if (previous == null) return true;
    if (candidate.depthSupported != previous.depthSupported) return candidate.depthSupported;
    return candidate.sharpness > previous.sharpness;
  }

  boolean hasOverview() {
    return supportedOverview != null && supportedFrames * 2 > covers.size();
  }

  void finishScan(boolean closeupMatch) {
    String identity = "unidentified";
    if (!closeup && hasOverview()) {
      identity = "likely_lantana";
      best = supportedOverview;
      bestBearing = supportedBearing;
      bestLocation = supportedLocation;
    } else if (closeupMatch) {
      identity = "closeup_match";
    }
    double avg = covers.stream().mapToDouble(d -> d).average().orElse(0);
    double spread =
        Math.sqrt(covers.stream().mapToDouble(d -> (d - avg) * (d - avg)).average().orElse(0));
    boolean poor =
        best.sharpness < 5
            || best.ambiguous > .35
            || spread > 12
            || covers.size() < 4
            || matches == 0
            || inconsistent * 2 > matches;
    if (best.height != null) {
      Collections.sort(heights);
      if (poor
          || heights.size() < 3
          || Math.abs(heights.get(heights.size() / 2) - best.height) > .25
          || heights.get(3 * heights.size() / 4) - heights.get(heights.size() / 4) > .35)
        best.height = null;
    }
    SweepTrial completed = trial;
    stopCamera();
    js("processingSweep()");
    save(best, "Camera sweep", poor, covers.size() + closeFrames, identity, completed);
  }

  static String encoded(Bitmap b, boolean mask) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    b.compress(mask ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 75, bytes);
    return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
  }

  void save(
      Segmenter.Prediction p,
      String source,
      boolean poor,
      int frames,
      String identity,
      SweepTrial completed) {
    try {
      boolean imported = "Imported image".equals(source);
      boolean measured = "likely_lantana".equals(identity);
      PhotoDepth.Result relative = null;
      DepthOutline outline = null;
      String version = model.version;
      if (measured && p.eligible) {
        try {
          relative = PhotoDepth.estimate(this, p.frame);
          outline = relative.refine(p);
          p.mask =
              Bitmap.createBitmap(
                  outline.overlay, outline.width, outline.height, Bitmap.Config.ARGB_8888);
          p.cover = outline.cover();
          version += DepthOutline.VERSION;
          if (outline.foreground == 0) {
            measured = false;
            identity = "unidentified";
          }
        } catch (Exception | LinkageError e) {
          android.util.Log.w("Fieldbook", "Keeping RGB outline: relative depth unavailable", e);
        }
      }
      if (!measured) {
        p.height = null;
        p.mask =
            Bitmap.createBitmap(p.mask.getWidth(), p.mask.getHeight(), Bitmap.Config.ARGB_8888);
        poor = true;
      }
      JSONObject v = new JSONObject();
      String id = UUID.randomUUID().toString();
      v.put("id", id)
          .put("patch", patch)
          .put("at", System.currentTimeMillis())
          .put("phase", phase)
          .put("source", source)
          .put("identity", identity)
          .put("cover", measured ? p.cover : JSONObject.NULL)
          .put("frames", frames)
          .put("model", version)
          .put("review", poor)
          .put("height", p.height == null ? JSONObject.NULL : p.height);
      if ("unidentified".equals(identity)) {
        String reason =
            p.sharpness < 5
                    && (completed == null || detailFrame == null || detailFrame.sharpness < 5)
                ? "blur"
                : completed != null
                        && (supportedFrames > 0 || (detailFrame != null && detailFrame.eligible))
                    ? "inconsistent"
                    : "no_match";
        v.put("identificationReason", reason);
      }
      Location loc = bestLocation;
      if (!imported
          && loc != null
          && SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos() < 120000000000L) {
        v.put("lat", loc.getLatitude())
            .put("lon", loc.getLongitude())
            .put("accuracy", loc.getAccuracy());
      }
      if (!imported && bestBearing != null) v.put("heading", bestBearing);
      if (!poor && !imported && p.height != null && outline == null) {
        JSONObject biomass = calibrated(p);
        if (biomass != null) v.put("biomass", biomass);
      }
      String closePhoto =
          completed != null && closeup && detailFrame != null
              ? encoded(detailFrame.frame, false)
              : null;
      if (closePhoto != null) v.put("hasCloseup", true);
      if (completed != null)
        v.put(
            "depth",
            completed.depthSummary(
                p.sweepIndex, measured, outline == null ? null : outline.metricMask));
      visits.save(v, encoded(p.frame, false), encoded(p.mask, true), closePhoto);
      try {
        File root = JournalFiles.directory(this);
        JournalFiles.write(root, visits, id, closePhoto != null);
        if (relative != null) PhotoDepth.saveMap(new File(root, id), relative);
      } catch (Exception copyError) {
        android.util.Log.w("Fieldbook", "USB copy pending", copyError);
        JournalFileJob.schedule(this);
      }
      if (completed != null) {
        try {
          File folder = new File(JournalFiles.directory(this), id);
          JSONObject r = completed.save(folder, p.sweepIndex, measured, v.getJSONObject("depth"));
          android.util.Log.i(
              "Fieldbook",
              "Sweep comparison: frames="
                  + completed.frames.size()
                  + ", depth frames="
                  + r.optInt("depthFrames")
                  + ", usable cells="
                  + v.getJSONObject("depth").getInt("validCells"));
        } catch (Exception error) {
          android.util.Log.w("Fieldbook", "Visit saved without sweep comparison", error);
          message("Visit saved. Sweep comparison could not be stored.");
        }
      }
      android.util.Log.i(
          "Fieldbook",
          "Saved "
              + v.getString("source")
              + ", frames="
              + frames
              + ", identity="
              + identity
              + ", review="
              + poor);
      UploadJob.schedule(this);
      js("detail(" + JSONObject.quote(id) + ")");
    } catch (Exception e) {
      fail("Visit could not be saved. Check free storage and retry.");
    }
  }

  JSONObject calibrated(Segmenter.Prediction p) throws Exception {
    try (InputStream in = getAssets().open("biomass.json")) {
      byte[] bytes = new byte[in.available()];
      new DataInputStream(in).readFully(bytes);
      JSONObject cal = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      JSONArray c = cal.getJSONArray("cover"),
          h = cal.getJSONArray("height"),
          w = cal.getJSONArray("coefficients");
      if (!model.version.equals(cal.getString("model"))
          || p.cover < c.getDouble(0)
          || p.cover > c.getDouble(1)
          || p.height < h.getDouble(0)
          || p.height > h.getDouble(1)) return null;
      double estimate =
          Math.max(
              0, w.getDouble(0) + p.cover / 100 * (w.getDouble(1) + p.height * w.getDouble(2)));
      double radius = cal.getDouble("radius");
      return new JSONObject()
          .put("estimate", estimate)
          .put("low", Math.max(0, estimate - radius))
          .put("high", estimate + radius);
    } catch (FileNotFoundException pending) {
      return null;
    }
  }

  void stopCamera() {
    trial = null;
    work.removeCallbacks(timeout);
    ScanView view = scanView;
    scanView = null;
    scanning = false;
    if (camera != null) {
      camera.close();
      camera = null;
    }
    if (view != null)
      runOnUiThread(
          () -> {
            root.removeView(view);
            if (scanView == null) web.setVisibility(android.view.View.VISIBLE);
          });
  }

  void fail(String reason) {
    stopCamera();
    js("home()");
    message(reason);
  }

  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req != 41 || result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    scanning = true;
    js("importing()");
    work.post(
        () -> {
          try {
            ensureModel();
            Bitmap b =
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, s) -> {
                      int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                      decoder.setTargetSampleSize(Math.max(1, (int) Math.ceil(max / 960.0)));
                      decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            patch = UUID.randomUUID().toString();
            phase = "Baseline";
            Segmenter.Prediction p = model.predict(b);
            save(
                p,
                "Imported image",
                p.sharpness < 5 || p.ambiguous > .35,
                1,
                p.eligible ? "likely_lantana" : "unidentified",
                null);
          } catch (Exception e) {
            fail("Unable to analyse this image.");
          } finally {
            scanning = false;
          }
        });
  }

  void listen() {
    if (!active) return;
    try {
      locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
    } catch (SecurityException | IllegalArgumentException ignored) {
    }
  }

  public void onLocationChanged(Location location) {
    fix = location;
  }

  public void onProviderDisabled(String p) {}

  public void onProviderEnabled(String p) {}

  public void onStatusChanged(String p, int s, Bundle b) {}

  public void onAccuracyChanged(Sensor s, int accuracy) {}

  public void onSensorChanged(SensorEvent e) {
    if (e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
      bearing = null;
      return;
    }
    float[] m = new float[9];
    SensorManager.getRotationMatrixFromVector(m, e.values);
    bearing =
        Math.hypot(m[2], m[5]) < .2 ? null : (Math.toDegrees(Math.atan2(-m[2], -m[5])) + 360) % 360;
  }

  protected void onResume() {
    super.onResume();
    active = true;
    listen();
    if (resumeScan) {
      resumeScan = false;
      begin();
    }
    UploadJob.schedule(this);
    Sensor s = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
  }

  protected void onPause() {
    active = false;
    locations.removeUpdates(this);
    sensors.unregisterListener(this);
    bearing = null;
    work.post(
        () -> {
          if (scanView != null) {
            stopCamera();
            js("home()");
          }
        });
    super.onPause();
  }

  public void onBackPressed() {
    if (scanning) new Bridge().cancel();
    else js("back()");
  }

  protected void onDestroy() {
    work.post(
        () -> {
          stopCamera();
          analysis.post(
              () ->
                  work.post(
                      () -> {
                        try {
                          if (model != null) model.close();
                        } catch (Exception ignored) {
                        }
                        visits.close();
                        thread.quitSafely();
                        analysisThread.quitSafely();
                      }));
        });
    super.onDestroy();
  }
}
