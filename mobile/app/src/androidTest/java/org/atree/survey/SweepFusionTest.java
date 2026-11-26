package org.atree.survey;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import com.google.ar.core.Pose;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

public class SweepFusionTest {
  private static final int SIDE = 15, CENTER = 7 * SIDE + 7;

  private static Segmenter.Prediction view(boolean hole, boolean hasDepth, int centerDepth) {
    Segmenter.Prediction p = new Segmenter.Prediction();
    p.frame = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
    p.eligible = true;
    p.gray = new int[SIDE * SIDE];
    p.probabilities = new float[SIDE][SIDE];
    if (hasDepth) {
      p.geometry =
          new SweepGeometry(
              Pose.IDENTITY,
              new float[] {15, 15},
              new float[] {7.5f, 7.5f},
              new int[] {15, 15},
              90,
              SIDE,
              SIDE);
      Arrays.fill(p.geometry.mm, (short) 1000);
      p.geometry.mm[CENTER] = (short) centerDepth;
    }
    for (int y = 0; y < SIDE; y++)
      for (int x = 0; x < SIDE; x++) {
        p.gray[y * SIDE + x] = (x * 47 + y * 29) % 180;
        if (x >= 4 && x <= 10 && y >= 4 && y <= 10) p.probabilities[y][x] = 1;
      }
    if (hole) p.probabilities[7][7] = 0;
    return p;
  }

  @Test
  public void twoOtherViewsCanRepairAnInteriorHole() throws Exception {
    SweepTrial trial = new SweepTrial();
    trial.add(view(true, false, 0), 0, false);
    trial.add(view(false, false, 0), 400, false);
    assertFalse(trial.fuse(0, new JSONObject())[CENTER]);
    trial.add(view(false, false, 0), 800, false);
    assertTrue(trial.fuse(0, new JSONObject())[CENTER]);
  }

  @Test
  public void fartherSurfaceKeepsARealGapOpen() throws Exception {
    SweepTrial trial = new SweepTrial();
    for (int i = 0; i < 3; i++) trial.add(view(i == 0, true, 2400), i * 400, false);
    JSONObject result = new JSONObject();
    assertFalse(trial.fuse(0, result)[CENTER]);
    assertEquals(1, result.getInt("depthVetoes"));
    assertTrue(result.getInt("geometricCorrespondences") > 0);
  }

  @Test
  public void occludedCloseupAndUnidentifiedFramesCannotVote() throws Exception {
    SweepTrial trial = new SweepTrial();
    trial.add(view(true, true, 1000), 0, false);
    trial.add(view(false, true, 2000), 400, false);
    trial.add(view(false, true, 1000), 800, true);
    Segmenter.Prediction unknown = view(false, true, 1000);
    unknown.eligible = false;
    trial.add(unknown, 1200, false);
    trial.add(view(false, true, 1000), 1600, false);
    assertFalse(trial.fuse(0, new JSONObject())[CENTER]);
  }

  @Test
  public void projectedAreaUsesOnlyObservedMaskCells() throws Exception {
    Segmenter.Prediction p = view(false, true, 1000);
    // 49 foreground cells; each projects to 1/225 m² at a 1 metre axial depth.
    JSONObject atOne = p.geometry.measurements(p.probabilities, true);
    assertEquals(49.0 / 225, atOne.getDouble("observedProjectedAreaM2"), 1e-6);
    Arrays.fill(p.geometry.mm, (short) 2000);
    p.geometry.mm[CENTER] = 0;
    JSONObject atTwo = p.geometry.measurements(p.probabilities, true);
    assertEquals(48 * 4.0 / 225, atTwo.getDouble("observedProjectedAreaM2"), 1e-6);
    assertEquals(2.0, atTwo.getDouble("sceneMedianM"), 0);
    assertEquals(48, atTwo.getInt("supportedMaskCells"));
    assertFalse(p.geometry.measurements(p.probabilities, false).has("observedProjectedAreaM2"));
    Arrays.fill(p.geometry.mm, (short) 0);
    p.geometry.mm[CENTER] = 1000;
    JSONObject sparse = p.geometry.measurements(p.probabilities, true);
    assertFalse(sparse.has("observedProjectedAreaM2"));
    assertFalse(sparse.has("sceneMedianM"));
  }

  @Test
  public void sparseOverviewCanShowAnotherSceneWithoutTransferringShrubArea() throws Exception {
    SweepTrial t = new SweepTrial();
    t.add(view(false, false, 0), 0, false);
    Segmenter.Prediction other = view(false, true, 1000);
    other.geometry.valid = 225;
    t.add(other, 400, false);
    JSONObject stats = t.depthSummary(0, true);
    assertEquals(1, stats.getInt("frame"));
    assertEquals(1.0, stats.getDouble("sceneMedianM"), 0);
    assertFalse(stats.has("observedProjectedAreaM2"));
  }
}
