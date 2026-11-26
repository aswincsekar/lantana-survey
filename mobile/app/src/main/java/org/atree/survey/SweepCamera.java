package org.atree.survey;

import android.content.Context;
import android.graphics.*;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;
import java.nio.*;
import java.util.*;

/** Camera and GL run on the survey worker. At most one RGB sample is in inference. */
@SuppressWarnings("deprecation")
final class SweepCamera implements AutoCloseable {
  interface Listener {
    void frame(Segmenter.Prediction p);

    void error(String reason);

    void ready(int width, int height);
  }

  final Context context;
  final Handler handler;
  final Segmenter model;
  final Listener listener;
  Session ar;
  Camera camera;
  final SurfaceTexture target;
  final Handler analysis;
  ArPreview preview;
  int rotation;
  volatile boolean closed;
  boolean pending;
  long lastFrame, lastDepth, drawn;
  Camera.Size regularSize;
  String depthStatus = "Waiting for camera tracking";

  SweepCamera(
      Context context,
      Handler handler,
      Handler analysis,
      SurfaceTexture target,
      Segmenter model,
      Listener listener) {
    this.context = context;
    this.handler = handler;
    this.analysis = analysis;
    this.target = target;
    this.model = model;
    this.listener = listener;
  }

  void start() throws Exception {
    try {
      ar = new Session(context);
      if (!ar.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
        throw new UnsupportedOperationException("Depth unsupported");
      Config config = new Config(ar);
      config.setDepthMode(Config.DepthMode.AUTOMATIC).setFocusMode(Config.FocusMode.AUTO);
      ar.configure(config);

      CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      rotation =
          manager
              .getCameraCharacteristics(ar.getCameraConfig().getCameraId())
              .get(CameraCharacteristics.SENSOR_ORIENTATION);
      android.util.Size size = ar.getCameraConfig().getImageSize();
      int w = size.getWidth(), h = size.getHeight();
      preview = new ArPreview();
      preview.configure(w, h, rotation);
      target.setDefaultBufferSize(preview.width, preview.height);
      preview.open(target);
      ar.setCameraTextureName(preview.texture);
      ar.setDisplayGeometry(Surface.ROTATION_0, preview.width, preview.height);
      ar.resume();
      listener.ready(preview.width, preview.height);
      handler.post(this::arFrame);
      return;
    } catch (Exception | LinkageError unavailable) {
      depthStatus =
          unavailable
                      instanceof
                      com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
                  || unavailable
                      instanceof com.google.ar.core.exceptions.UnavailableApkTooOldException
              ? "Depth unavailable. Set up Google Play Services for AR under Connection."
              : "Depth unavailable on this camera session.";
      android.util.Log.i(
          "Fieldbook", "Depth session unavailable: " + unavailable.getClass().getSimpleName());
      releaseAR();
    }
    startRegular();
  }

  private void releaseAR() {
    if (ar != null) {
      try {
        ar.pause();
      } catch (RuntimeException ignored) {
      }
      ar.close();
      ar = null;
    }
    if (preview != null) {
      preview.close();
      preview = null;
    }
  }

  private void startRegular() throws Exception {
    Camera.CameraInfo info = new Camera.CameraInfo();
    int id = 0;
    for (; id < Camera.getNumberOfCameras(); id++) {
      Camera.getCameraInfo(id, info);
      if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) break;
    }
    camera = Camera.open(id);
    rotation = info.orientation;
    Camera.Parameters params = camera.getParameters();
    Camera.Size size =
        Collections.min(
            params.getSupportedPreviewSizes(),
            Comparator.comparingInt(s -> Math.abs(s.width - 640) + Math.abs(s.height - 480)));
    params.setPreviewSize(size.width, size.height);
    params.setPreviewFormat(ImageFormat.NV21);
    if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
      params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
    // Use a supported 30 Hz range when the camera offers one.
    int[] preferred = null;
    for (int[] range : params.getSupportedPreviewFpsRange()) {
      if (range[1] == 30000 && (preferred == null || range[0] > preferred[0])) preferred = range;
    }
    if (preferred != null) params.setPreviewFpsRange(preferred[0], preferred[1]);
    camera.setParameters(params);
    regularSize = size;
    camera.setDisplayOrientation(rotation);
    camera.setPreviewTexture(target);
    camera.startPreview();
    listener.ready(
        rotation % 180 == 0 ? size.width : size.height,
        rotation % 180 == 0 ? size.height : size.width);
    requestRegularFrame();
  }

  void requestRegularFrame() {
    if (closed || camera == null || pending) return;
    camera.setOneShotPreviewCallback(
        (bytes, c) -> {
          if (closed || pending || bytes == null) return;
          int w = regularSize.width, h = regularSize.height;
          long time = SystemClock.elapsedRealtimeNanos();
          submit(
              () ->
                  convert(
                      w,
                      h,
                      (x, y) -> {
                        int uv = w * h + (y / 2) * w + (x & ~1);
                        return rgb(bytes[y * w + x], bytes[uv + 1], bytes[uv]);
                      }),
              time,
              null);
        });
  }

  interface ImageSource {
    Bitmap read();
  }

  void submit(ImageSource source, long timestamp, SweepGeometry geometry) {
    pending = true;
    lastFrame = SystemClock.elapsedRealtime();
    analysis.post(
        () -> {
          if (closed) return;
          try {
            Segmenter.Prediction pred = model.predict(orient(source.read()));
            pred.jpeg = SweepTrial.jpeg(pred.frame);
            pred.cameraTime = timestamp;
            pred.geometry = geometry;
            handler.post(
                () -> {
                  if (closed) return;
                  pending = false;
                  try {
                    if (pred.eligible && geometry != null && geometry.valid >= 25) {
                      pred.depthSupported =
                          geometry
                              .measurements(pred.probabilities, true)
                              .has("observedProjectedAreaM2");
                      if (ar != null) pred.height = height(pred);
                    }
                    listener.frame(pred);
                    if (!closed && camera != null)
                      handler.postDelayed(
                          this::requestRegularFrame,
                          Math.max(0, 350 - (SystemClock.elapsedRealtime() - lastFrame)));
                  } catch (Exception e) {
                    listener.error("Could not analyse camera frame");
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (!closed) listener.error("Camera analysis interrupted");
                });
          }
        });
  }

  interface Pixel {
    int get(int x, int y);
  }

  static Bitmap convert(int w, int h, Pixel source) {
    int stride = Math.max(1, (int) Math.ceil(Math.max(w, h) / 640.0)),
        outW = w / stride,
        outH = h / stride;
    int[] pixels = new int[outW * outH];
    for (int y = 0; y < outH; y++)
      for (int x = 0; x < outW; x++) pixels[y * outW + x] = source.get(stride * x, stride * y);
    return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888);
  }

  static int clamp(int v) {
    return Math.min(255, Math.max(0, v));
  }

  static int rgb(byte luma, byte cb, byte cr) {
    int y = 298 * Math.max(0, (luma & 255) - 16), u = (cb & 255) - 128, v = (cr & 255) - 128;
    return Color.rgb(
        clamp((y + 409 * v + 128) >> 8),
        clamp((y - 100 * u - 208 * v + 128) >> 8),
        clamp((y + 516 * u + 128) >> 8));
  }

  Bitmap orient(Bitmap source) {
    Matrix matrix = new Matrix();
    matrix.postRotate(rotation);
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
  }

  static byte sample(Image.Plane p, ByteBuffer buf, int x, int y) {
    return buf.get(buf.position() + y * p.getRowStride() + x * p.getPixelStride());
  }

  void arFrame() {
    if (closed || ar == null) return;
    try {
      Frame f = ar.update();
      if (f.getTimestamp() != 0 && drawn != f.getTimestamp()) {
        preview.draw(f);
        drawn = f.getTimestamp();
      }
      if (!pending && SystemClock.elapsedRealtime() - lastFrame >= 350) {
        try (Image im = f.acquireCameraImage()) {
          int w = im.getWidth(), h = im.getHeight();
          PlaneCopy y = new PlaneCopy(im.getPlanes()[0]),
              u = new PlaneCopy(im.getPlanes()[1]),
              v = new PlaneCopy(im.getPlanes()[2]);
          SweepGeometry g =
              SweepGeometry.read(f, rotation, model.gridWidth, model.gridHeight, lastDepth);
          if (g != null && g.depthTime != 0) lastDepth = g.depthTime;
          depthStatus =
              g == null
                  ? "Move slowly sideways to establish tracking."
                  : g.valid == 0
                      ? "Move slowly sideways to collect depth."
                      : "Usable depth: "
                          + Math.round(100.0 * g.valid / g.mm.length)
                          + "% of this frame";
          submit(
              () ->
                  convert(
                      w,
                      h,
                      (x, row) -> rgb(y.get(x, row), u.get(x / 2, row / 2), v.get(x / 2, row / 2))),
              im.getTimestamp(),
              g);
        }
      }
    } catch (NotYetAvailableException wait) {
    } catch (Exception | LinkageError e) {
      depthStatus = "Depth tracking interrupted. Using the regular camera.";
      releaseAR();
      try {
        startRegular();
      } catch (Exception failure) {
        listener.error("Camera unavailable");
      }
      return;
    }
    if (!closed) handler.post(this::arFrame);
  }

  static final class PlaneCopy {
    final byte[] bytes;
    final int row, pixel;

    PlaneCopy(Image.Plane plane) {
      ByteBuffer data = plane.getBuffer().duplicate();
      bytes = new byte[data.remaining()];
      data.get(bytes);
      row = plane.getRowStride();
      pixel = plane.getPixelStride();
    }

    byte get(int x, int y) {
      return bytes[y * row + x * pixel];
    }
  }

  Double height(Segmenter.Prediction pred) {
    Pose pose = pred.geometry.pose;
    List<Plane> grounds = new ArrayList<>();
    for (Plane p : ar.getAllTrackables(Plane.class)) {
      float below = pose.ty() - p.getCenterPose().ty();
      if (p.getTrackingState() == TrackingState.TRACKING
          && p.getSubsumedBy() == null
          && p.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING
          && below > .55
          && below < 2.4) grounds.add(p);
    }
    if (grounds.isEmpty()) return null;
    SweepGeometry g = pred.geometry;
    List<Double> heights = new ArrayList<>();
    for (int i = 0; i < g.mm.length; i++) {
      if (g.mm[i] == 0 || pred.probabilities[i / g.width][i % g.width] < .8f) continue;
      float[] point = g.worldPoint(i);
      for (Plane ground : grounds) {
        float gy = ground.getCenterPose().ty();
        if (point[1] > gy && ground.isPoseInPolygon(Pose.makeTranslation(point[0], gy, point[2]))) {
          heights.add((double) (point[1] - gy));
          break;
        }
      }
    }
    if (heights.size() < 25) return null;
    Collections.sort(heights);
    return heights.get((int) (.95 * (heights.size() - 1)));
  }

  public void close() {
    closed = true;
    if (camera != null) {
      camera.setPreviewCallback(null);
      camera.stopPreview();
      camera.release();
    }
    releaseAR();
  }
}
