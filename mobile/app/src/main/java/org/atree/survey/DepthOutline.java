package org.atree.survey;

/** Joint bilateral upsampling of a species-gated mask using the aligned RGB and relative depth. */
final class DepthOutline {
  static final String VERSION = "-rgbd1";
  final int[] overlay;
  final float[][] metricMask;
  final int width, height, foreground;

  double cover() {
    return 100.0 * foreground / overlay.length;
  }

  DepthOutline(float[][] scores, float threshold, int[] rgb, float[] depth, int w, int h) {
    int rows = scores.length, cols = scores[0].length;
    width = w;
    height = h;
    overlay = new int[w * h];
    metricMask = new float[rows][cols];
    int[][] counts = new int[rows][cols];
    int[] anchors = new int[rows * cols];
    for (int row = 0; row < rows; row++)
      for (int col = 0; col < cols; col++)
        anchors[row * cols + col] =
            Math.min(h - 1, (int) ((row + .5) * h / rows)) * w
                + Math.min(w - 1, (int) ((col + .5) * w / cols));
    int total = 0;
    for (int y = 0; y < h; y++) {
      float gy = Math.max(0, Math.min(rows - 1, (y + .5f) * rows / h - .5f));
      int y0 = (int) gy;
      int row = Math.min(rows - 1, (int) (gy + .5f));
      for (int x = 0; x < w; x++) {
        int i = y * w + x;
        float gx = Math.max(0, Math.min(cols - 1, (x + .5f) * cols / w - .5f));
        int x0 = (int) gx, col = Math.min(cols - 1, (int) (gx + .5f));
        counts[row][col]++;
        double yes = 0, weight = 0;
        for (int dy = 0; dy <= 1; dy++)
          for (int dx = 0; dx <= 1; dx++) {
            int sy = Math.min(rows - 1, y0 + dy), sx = Math.min(cols - 1, x0 + dx);
            double spatial =
                (dy == 0 ? 1 - (gy - y0) : gy - y0) * (dx == 0 ? 1 - (gx - x0) : gx - x0);
            int j = anchors[sy * cols + sx];
            float dz = (depth[i] - depth[j]) / .06f;
            int a = rgb[i], b = rgb[j];
            int dr = ((a >> 16) & 255) - ((b >> 16) & 255),
                dg = ((a >> 8) & 255) - ((b >> 8) & 255),
                db = (a & 255) - (b & 255);
            double bilateral =
                spatial / (1 + dz * dz) / (1 + (dr * dr + dg * dg + db * db) / (3.0 * 48 * 48));
            weight += bilateral;
            if (scores[sy][sx] >= threshold) yes += bilateral;
          }
        if (yes > 0 && 2 * yes >= weight) {
          overlay[i] = 0xaa4285f4;
          total++;
          metricMask[row][col]++;
        }
      }
    }
    foreground = total;
    for (int row = 0; row < rows; row++)
      for (int col = 0; col < cols; col++)
        metricMask[row][col] =
            counts[row][col] == 0
                ? scores[row][col]
                : metricMask[row][col] * 2 >= counts[row][col] ? 1 : 0;
  }
}
