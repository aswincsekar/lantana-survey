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
  Handler work;
  volatile boolean active;
  Segmenter model;
  SweepCamera camera;
  Visits visits;
  Segmenter.Prediction best;
  final ArrayList<Double> covers = new ArrayList<>(), heights = new ArrayList<>();
  Segmenter.Prediction preceding;
  int matches, inconsistent;
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
    visits = new Visits(this);
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
    setContentView(web);
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

  class Bridge {
    @JavascriptInterface
    public String visits() {
      try {
        JSONArray saved = visits.list();
        android.util.Log.i("Fieldbook", "Journal loaded, visits=" + saved.length());
        return saved.toString();
      } catch (Exception e) {
        return "[]";
      }
    }

    @JavascriptInterface
    public String visit(String id) {
      try {
        return visits.read(id).toString();
      } catch (Exception e) {
        return "null";
      }
    }

    @JavascriptInterface
    public void scan(String previous, String stage) {
      runOnUiThread(
          () -> {
            if (scanning) return;
            if (!Arrays.asList("Baseline", "After clearance", "Regrowth").contains(stage)) return;
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
                      + JSONObject.quote(encoded(p.mask, true))
                      + ","
                      + p.cover
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
    js("openScan()");
    work.post(
        () -> {
          try {
            if (!scanning || !active) {
              scanning = false;
              js("home()");
              return;
            }
            ensureModel();
            best = null;
            preceding = null;
            matches = 0;
            inconsistent = 0;
            covers.clear();
            heights.clear();
            camera =
                new SweepCamera(
                    this,
                    work,
                    model,
                    new SweepCamera.Listener() {
                      public void frame(Segmenter.Prediction p) {
                        receive(p);
                      }

                      public void error(String reason) {
                        fail(reason);
                      }
                    });
            camera.start();
            started = SystemClock.elapsedRealtime();
            work.postDelayed(timeout, 15000);
          } catch (Exception | LinkageError e) {
            fail("Camera unavailable. Close other camera apps or import a photograph.");
          }
        });
  }

  final Runnable timeout =
      () -> {
        if (scanning) {
          if (best != null) finishScan();
          else fail("No frames received. Try again with the camera unobstructed.");
        }
      };

  void ensureModel() throws Exception {
    if (model == null) model = new Segmenter(this);
  }

  void receive(Segmenter.Prediction p) {
    if (!scanning || !active) return;
    long elapsed = SystemClock.elapsedRealtime() - started;
    covers.add(p.cover);
    if (p.height != null) heights.add(p.height);
    if (preceding != null) {
      double overlap = Segmenter.agreement(preceding, p);
      if (overlap >= 0) {
        matches++;
        if (overlap < .6) inconsistent++;
      }
    }
    preceding = p;
    if (best == null || p.sharpness > best.sharpness) {
      best = p;
      bestLocation = fix;
      bestBearing = bearing;
    }
    String hint =
        p.sharpness < 5
            ? "Hold the phone steady"
            : p.ambiguous > .35 ? "Keep the shrub and its base in view" : "Move slowly sideways";
    js(
        "preview("
            + JSONObject.quote(encoded(p.frame, false))
            + ","
            + JSONObject.quote(encoded(p.mask, true))
            + ","
            + Math.min(100, elapsed / 100)
            + ","
            + JSONObject.quote(hint)
            + ")");
    if (elapsed >= 10000) finishScan();
  }

  void finishScan() {
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
    stopCamera();
    save(best, false, poor, covers.size());
  }

  static String encoded(Bitmap b, boolean mask) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    b.compress(mask ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 75, bytes);
    return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
  }

  void save(Segmenter.Prediction p, boolean imported, boolean poor, int frames) {
    try {
      JSONObject v = new JSONObject();
      String id = UUID.randomUUID().toString();
      v.put("id", id)
          .put("patch", patch)
          .put("at", System.currentTimeMillis())
          .put("phase", phase)
          .put("source", imported ? "Imported image" : "Camera sweep")
          .put("cover", p.cover)
          .put("frames", frames)
          .put("model", model.version)
          .put("review", poor)
          .put("height", p.height == null ? JSONObject.NULL : p.height);
      Location loc = bestLocation;
      if (!imported
          && loc != null
          && SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos() < 120000000000L) {
        v.put("lat", loc.getLatitude())
            .put("lon", loc.getLongitude())
            .put("accuracy", loc.getAccuracy());
      }
      if (!imported && bestBearing != null) v.put("heading", bestBearing);
      if (!poor && !imported && p.height != null) {
        JSONObject biomass = calibrated(p);
        if (biomass != null) v.put("biomass", biomass);
      }
      visits.save(v, encoded(p.frame, false), encoded(p.mask, true));
      android.util.Log.i(
          "Fieldbook",
          "Saved " + v.getString("source") + ", frames=" + frames + ", review=" + poor);
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
    work.removeCallbacks(timeout);
    scanning = false;
    if (camera != null) {
      camera.close();
      camera = null;
    }
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
            save(p, true, p.sharpness < 5 || p.ambiguous > .35, 1);
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
          if (camera != null) {
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
          try {
            if (model != null) model.close();
          } catch (Exception ignored) {
          }
          visits.close();
          thread.quitSafely();
        });
    super.onDestroy();
  }
}
