package org.atree.survey;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A camera-owned texture. Only instructions and sampled overlays depend on analysis. */
final class ScanView extends LinearLayout implements TextureView.SurfaceTextureListener {
  interface Actions {
    void ready(ScanView view, SurfaceTexture texture);

    void lost(ScanView view, SurfaceTexture texture);

    void cancel();
  }

  final Actions actions;
  final TextureView video;
  final FrameLayout viewport;
  final ImageView overlay, ghost;
  final TextView hint;
  final ProgressBar progress;
  float aspect = .75f;
  long first, last;
  int frames, late;
  final long[] intervals = new long[1001];

  ScanView(Context c, Actions actions) {
    super(c);
    this.actions = actions;
    setOrientation(VERTICAL);
    setBackgroundColor(0xfff8faff);
    int padding = dp(16);
    setPadding(padding, padding, padding, padding);
    Typeface font = Typeface.createFromAsset(c.getAssets(), "google-sans.ttf");
    TextView title = new TextView(c);
    title.setText("Identifying plant…");
    title.setTextSize(25);
    title.setTextColor(0xff202124);
    title.setTypeface(font);
    addView(title);
    hint = new TextView(c);
    hint.setTypeface(font);
    hint.setText("Point the camera at one plant.");
    hint.setMinLines(2);
    addView(hint);
    FrameLayout space =
        new FrameLayout(c) {
          @Override
          protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) viewport.getLayoutParams();
            lp.width = Math.max(1, Math.min(w, Math.round(h * aspect)));
            lp.height = Math.max(1, Math.round(lp.width / aspect));
            super.onMeasure(ws, hs);
          }
        };
    addView(space, new LinearLayout.LayoutParams(-1, 0, 1));
    viewport = new FrameLayout(c);
    space.addView(viewport, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
    video = new TextureView(c);
    viewport.addView(video, new FrameLayout.LayoutParams(-1, -1));
    overlay = layer(c);
    ghost = layer(c);
    ghost.setAlpha(.25f);
    progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
    addView(progress);
    LinearLayout buttons = new LinearLayout(c);
    addView(buttons);
    Button cancel = new Button(c);
    cancel.setText("Cancel");
    cancel.setOnClickListener(v -> actions.cancel());
    buttons.addView(cancel);
    video.setSurfaceTextureListener(this);
  }

  int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  ImageView layer(Context c) {
    ImageView image = new ImageView(c);
    image.setScaleType(ImageView.ScaleType.FIT_XY);
    viewport.addView(image, new FrameLayout.LayoutParams(-1, -1));
    return image;
  }

  void aspect(int width, int height) {
    if (width > 0 && height > 0) {
      aspect = (float) width / height;
      ((View) viewport.getParent()).requestLayout();
    }
  }

  public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
    actions.ready(this, surface);
  }

  public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

  public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
    actions.lost(this, surface);
    if (frames > 1 && last > first) {
      int sum = 0, p95 = 0;
      for (; p95 < 1000; p95++) {
        sum += intervals[p95];
        if (sum >= Math.ceil((frames - 1) * .95)) break;
      }
      Log.i(
          "Fieldbook",
          String.format(
              Locale.ROOT,
              "Native preview %.1f fps, p95 interval %d ms, gaps over 100 ms %d",
              (frames - 1) * 1000.0 / (last - first),
              p95,
              late));
    }
    return false;
  }

  public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    long now = SystemClock.elapsedRealtime();
    if (frames == 0) first = now;
    else {
      long gap = now - last;
      intervals[(int) Math.min(1000, gap)]++;
      if (gap > 100) late++;
    }
    last = now;
    frames++;
  }
}
