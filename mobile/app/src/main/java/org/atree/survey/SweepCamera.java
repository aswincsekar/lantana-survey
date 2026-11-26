package org.atree.survey;

import android.content.Context;
import android.graphics.*;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.*;
import android.os.Handler;
import android.os.SystemClock;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;
import java.nio.*;
import java.util.*;

/** All camera, inference and GL calls are confined to the survey worker thread. */
@SuppressWarnings("deprecation")
final class SweepCamera implements AutoCloseable {
  interface Listener {
    void frame(Segmenter.Prediction p);

    void error(String reason);
  }

  final Context context;
  final Handler handler;
  final Segmenter model;
  final Listener listener;
  Session ar;
  Camera camera;
  SurfaceTexture surface;
  EGLDisplay display;
  EGLContext gl;
  EGLSurface pbuffer;
  int texture, rotation;
  boolean closed;
  long lastFrame;

  SweepCamera(Context context, Handler handler, Segmenter model, Listener listener) {
    this.context = context;
    this.handler = handler;
    this.model = model;
    this.listener = listener;
  }

  void start() throws Exception {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] count = new int[1];
    EGL14.eglInitialize(display, new int[1], 0, new int[1], 0);
    EGLConfig[] configs = new EGLConfig[1];
    int[] attrs = {
      EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
    };
    if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
      throw new Exception("Camera display unavailable");
    gl =
        EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
            0);
    pbuffer =
        EGL14.eglCreatePbufferSurface(
            display,
            configs[0],
            new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE},
            0);
    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, gl);
    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    texture = textures[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    try {
      ar = new Session(context);
      if (!ar.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) throw new IllegalStateException();
      Config config = new Config(ar);
      config.setDepthMode(Config.DepthMode.AUTOMATIC).setFocusMode(Config.FocusMode.AUTO);
      ar.configure(config);
      ar.setCameraTextureName(texture);
      CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      rotation =
          manager
              .getCameraCharacteristics(ar.getCameraConfig().getCameraId())
              .get(CameraCharacteristics.SENSOR_ORIENTATION);
      ar.resume();
      handler.post(this::arFrame);
      return;
    } catch (Exception | LinkageError unavailable) {
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
    camera.setParameters(params);
    surface = new SurfaceTexture(texture);
    camera.setPreviewTexture(surface);
    camera.setPreviewCallback(
        (bytes, c) -> {
          if (closed || SystemClock.elapsedRealtime() - lastFrame < 350) return;
          lastFrame = SystemClock.elapsedRealtime();
          try {
            listener.frame(
                model.predict(
                    orient(
                        convert(
                            size.width,
                            size.height,
                            (x, y) -> {
                              int uv = size.width * size.height + (y / 2) * size.width + (x & ~1);
                              return rgb(bytes[y * size.width + x], bytes[uv + 1], bytes[uv]);
                            }))));
          } catch (Exception e) {
            listener.error("Camera analysis interrupted");
          }
        });
    camera.startPreview();
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
      if (SystemClock.elapsedRealtime() - lastFrame >= 350) {
        try (Image im = f.acquireCameraImage()) {
          Image.Plane[] planes = im.getPlanes();
          ByteBuffer y = planes[0].getBuffer(),
              u = planes[1].getBuffer(),
              v = planes[2].getBuffer();
          Bitmap raw =
              convert(
                  im.getWidth(),
                  im.getHeight(),
                  (col, row) ->
                      rgb(
                          sample(planes[0], y, col, row),
                          sample(planes[1], u, col / 2, row / 2),
                          sample(planes[2], v, col / 2, row / 2)));
          Segmenter.Prediction pred = model.predict(orient(raw));
          pred.height = height(f, pred);
          listener.frame(pred);
          lastFrame = SystemClock.elapsedRealtime();
        }
      }
    } catch (NotYetAvailableException wait) {
    } catch (Exception | LinkageError e) {
      releaseAR();
      try {
        startRegular();
      } catch (Exception failure) {
        listener.error("Camera unavailable");
      }
      return;
    }
    if (!closed) handler.postDelayed(this::arFrame, 25);
  }

  Double height(Frame frame, Segmenter.Prediction pred) {
    if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) return null;
    Pose pose = frame.getCamera().getPose();
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
    try (Image depth = frame.acquireRawDepthImage16Bits();
        Image conf = frame.acquireRawDepthConfidenceImage()) {
      if (Math.abs(frame.getTimestamp() - depth.getTimestamp()) > 150000000L) return null;
      Image.Plane dp = depth.getPlanes()[0], cp = conf.getPlanes()[0];
      ByteBuffer db = dp.getBuffer().order(ByteOrder.LITTLE_ENDIAN), cb = cp.getBuffer();
      CameraIntrinsics ci = frame.getCamera().getImageIntrinsics();
      int[] dims = ci.getImageDimensions();
      float[] fl = ci.getFocalLength(), pp = ci.getPrincipalPoint();
      List<Double> heights = new ArrayList<>();
      for (int j = 0; j < depth.getHeight(); j += 2)
        for (int i = 0; i < depth.getWidth(); i += 2) {
          if ((sample(cp, cb, i, j) & 255) < 190) continue;
          float z = (db.getShort(j * dp.getRowStride() + i * dp.getPixelStride()) & 65535) / 1000f;
          if (z < .5 || z > 5) continue;
          float[] pixel = new float[2];
          frame.transformCoordinates2d(
              Coordinates2d.TEXTURE_NORMALIZED,
              new float[] {(i + .5f) / depth.getWidth(), (j + .5f) / depth.getHeight()},
              Coordinates2d.IMAGE_PIXELS,
              pixel);
          float x = pixel[0] / dims[0], y = pixel[1] / dims[1];
          if (x < 0 || y < 0 || x >= 1 || y >= 1) continue;
          float nx = rotation == 90 ? 1 - y : rotation == 270 ? y : rotation == 180 ? 1 - x : x;
          float ny = rotation == 90 ? x : rotation == 270 ? 1 - x : rotation == 180 ? 1 - y : y;
          float[][] probs = pred.probabilities;
          if (probs[Math.min(probs.length - 1, (int) (ny * probs.length))][
                  Math.min(probs[0].length - 1, (int) (nx * probs[0].length))]
              < .8) continue;
          float[] world =
              pose.transformPoint(
                  new float[] {
                    (pixel[0] - pp[0]) * z / fl[0], -(pixel[1] - pp[1]) * z / fl[1], -z
                  });
          for (Plane g : grounds) {
            float gy = g.getCenterPose().ty();
            if (g.isPoseInPolygon(Pose.makeTranslation(world[0], gy, world[2])) && world[1] > gy) {
              heights.add((double) (world[1] - gy));
              break;
            }
          }
        }
      if (heights.size() < 25) return null;
      Collections.sort(heights);
      return heights.get((int) (.95 * (heights.size() - 1)));
    } catch (NotYetAvailableException unavailable) {
      return null;
    }
  }

  public void close() {
    closed = true;
    if (camera != null) {
      camera.setPreviewCallback(null);
      camera.stopPreview();
      camera.release();
    }
    if (surface != null) surface.release();
    releaseAR();
    if (display != null) {
      EGL14.eglMakeCurrent(
          display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (pbuffer != null) EGL14.eglDestroySurface(display, pbuffer);
      if (gl != null) EGL14.eglDestroyContext(display, gl);
      EGL14.eglTerminate(display);
    }
  }
}
