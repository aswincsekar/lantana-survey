package org.atree.survey;

import android.graphics.Bitmap;
import android.media.Image;
import com.google.ar.core.*;
import com.google.ar.core.exceptions.NotYetAvailableException;
import java.nio.*;
import java.util.Arrays;
import org.json.*;

/** Camera calibration and sparse, confidence-filtered depth aligned to the upright mask grid. */
final class SweepGeometry {
  final Pose pose;
  final float fx, fy, cx, cy;
  final int rotation, width, height;
  final short[] mm;
  final byte[] confidence;
  long depthTime;
  int valid;

  SweepGeometry(Pose pose, float[] focal, float[] center, int[] size, int rotation, int w, int h) {
    this.pose = pose;
    fx = focal[0] / size[0];
    fy = focal[1] / size[1];
    cx = center[0] / size[0];
    cy = center[1] / size[1];
    this.rotation = rotation;
    width = w;
    height = h;
    mm = new short[w * h];
    confidence = new byte[w * h];
  }

  static SweepGeometry read(Frame frame, int rotation, int w, int h, long lastDepth) {
    Camera camera = frame.getCamera();
    if (camera.getTrackingState() != TrackingState.TRACKING) return null;
    CameraIntrinsics k = camera.getImageIntrinsics();
    int[] size = k.getImageDimensions();
    SweepGeometry g =
        new SweepGeometry(
            camera.getPose(), k.getFocalLength(), k.getPrincipalPoint(), size, rotation, w, h);
    try (Image d = frame.acquireRawDepthImage16Bits();
        Image c = frame.acquireRawDepthConfidenceImage()) {
      if (d.getTimestamp() == lastDepth
          || d.getTimestamp() != c.getTimestamp()
          || Math.abs(frame.getTimestamp() - d.getTimestamp()) > 150000000L) return g;
      g.depthTime = d.getTimestamp();
      float[] image = new float[2 * w * h], uv = new float[image.length];
      for (int i = 0; i < w * h; i++) {
        float x = (i % w + .5f) / w, y = (i / w + .5f) / h;
        image[2 * i] = g.sensorX(x, y) * size[0];
        image[2 * i + 1] = g.sensorY(x, y) * size[1];
      }
      frame.transformCoordinates2d(
          Coordinates2d.IMAGE_PIXELS, image, Coordinates2d.TEXTURE_NORMALIZED, uv);
      Image.Plane dp = d.getPlanes()[0], cp = c.getPlanes()[0];
      ByteBuffer bytes = dp.getBuffer().order(ByteOrder.LITTLE_ENDIAN), quality = cp.getBuffer();
      for (int i = 0; i < w * h; i++) {
        float u = uv[2 * i], v = uv[2 * i + 1];
        if (!(u >= 0 && u < 1 && v >= 0 && v < 1)) continue;
        int x = (int) (u * d.getWidth()), y = (int) (v * d.getHeight());
        int conf = SweepCamera.sample(cp, quality, x, y) & 255;
        int z =
            bytes.getShort(bytes.position() + y * dp.getRowStride() + x * dp.getPixelStride())
                & 65535;
        g.confidence[i] = (byte) conf;
        if (conf >= 190 && z >= 500 && z <= 5000) {
          g.mm[i] = (short) z;
          g.valid++;
        }
      }
    } catch (NotYetAvailableException unavailable) {
      /* A pose can still be saved. */
    }
    return g;
  }

  float sensorX(float x, float y) {
    return rotation == 90 ? y : rotation == 180 ? 1 - x : rotation == 270 ? 1 - y : x;
  }

  float sensorY(float x, float y) {
    return rotation == 90 ? 1 - x : rotation == 180 ? 1 - y : rotation == 270 ? x : y;
  }

  // Returns -2 for occlusion, -1 when there is insufficient depth evidence.
  int project(int i, SweepGeometry other, Pose transform) {
    if (mm[i] == 0) return -1;
    float z = mm[i] / 1000f, x = (i % width + .5f) / width, y = (i / width + .5f) / height;
    float[] pt =
        transform.transformPoint(
            new float[] {(sensorX(x, y) - cx) * z / fx, -(sensorY(x, y) - cy) * z / fy, -z});
    float range = -pt[2];
    if (range <= 0) return -2;
    float u = other.cx + other.fx * pt[0] / range, v = other.cy - other.fy * pt[1] / range;
    int r = other.rotation;
    float ox = r == 90 ? 1 - v : r == 180 ? 1 - u : r == 270 ? v : u,
        oy = r == 90 ? u : r == 180 ? 1 - v : r == 270 ? 1 - u : v;
    if (!(ox >= 0 && ox < 1 && oy >= 0 && oy < 1)) return -2;
    int j = (int) (oy * other.height) * other.width + (int) (ox * other.width);
    if (other.mm[j] == 0) return -1;
    return Math.abs(other.mm[j] - range * 1000) > Math.max(120, range * 60) ? -2 : j;
  }

  // Axial depth in metres. Unknown cells remain transparent on top of their own camera frame.
  static Bitmap heatmap(short[] depth, int width, int height) {
    int[] colors = new int[depth.length];
    for (int i = 0; i < colors.length; i++) {
      if (depth[i] <= 0) continue;
      float t = Math.max(0f, Math.min(1f, (depth[i] - 500) / 4500f));
      int red = Math.round(255 * (1 - t) + 24 * t),
          green = Math.round(183 * (1 - t) + 67 * t),
          blue = Math.round(64 * (1 - t) + 198 * t);
      colors[i] = 0xe0000000 | (red << 16) | (green << 8) | blue;
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
  }

  float[] worldPoint(int i) {
    float x = (i % width + .5f) / width, y = (i / width + .5f) / height, z = mm[i] / 1000f;
    return pose.transformPoint(
        new float[] {(sensorX(x, y) - cx) * z / fx, -(sensorY(x, y) - cy) * z / fy, -z});
  }

  JSONObject measurements(float[][] scores, boolean measureShrub) throws JSONException {
    short[] sorted = new short[mm.length];
    int n = 0, mask = 0, supported = 0;
    double area = 0;
    for (int i = 0; i < mm.length; i++) {
      if (mm[i] > 0) sorted[n++] = mm[i];
      if (!measureShrub || scores[i / width][i % width] < Segmenter.MASK_THRESHOLD) continue;
      mask++;
      if (mm[i] == 0) continue;
      supported++;
      double z = mm[i] / 1000.0;
      area += z * z / (width * height * (double) fx * fy);
    }
    JSONObject result = new JSONObject().put("validCells", n).put("totalCells", mm.length);
    if (n >= 25) {
      Arrays.sort(sorted, 0, n);
      result.put("sceneMedianM", (sorted[(n - 1) / 2] + sorted[n / 2]) / 2000.0);
    }
    if (measureShrub) {
      result.put("maskCells", mask).put("supportedMaskCells", supported);
      if (supported >= 25 && 2 * supported >= mask && Double.isFinite(area) && area > 0)
        result.put("observedProjectedAreaM2", area);
    }
    return result;
  }

  JSONObject metadata() throws JSONException {
    return new JSONObject()
        .put("cameraToWorldXYZ", new JSONArray(pose.getTranslation()))
        .put("cameraToWorldQuaternionXYZW", new JSONArray(pose.getRotationQuaternion()))
        .put("normalizedFxFyCxCy", new JSONArray(new float[] {fx, fy, cx, cy}))
        .put("rotationDegrees", rotation)
        .put("depthTimestampNs", depthTime)
        .put("validDepthCells", valid);
  }
}
