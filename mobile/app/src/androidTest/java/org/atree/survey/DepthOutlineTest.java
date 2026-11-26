package org.atree.survey;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class DepthOutlineTest {
  @Test
  public void depthDiscontinuityMovesTheBoundaryWithinACoarseCell() {
    float[] depth = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    float[][] coarse = {{.98f, .1f}};
    DepthOutline mask = new DepthOutline(coarse, .75f, new int[12], depth, 12, 1);
    assertEquals(5, mask.foreground);
    for (int x = 0; x < 12; x++) assertEquals(x < 5, mask.overlay[x] != 0);
    assertEquals(100.0 * 5 / 12, mask.cover(), 0.001);
    assertArrayEquals(new float[] {1, 0}, mask.metricMask[0], 0);
  }

  @Test
  public void nearObjectWithoutSpeciesPixelsHasNoOutline() {
    float[] depth = new float[16];
    Arrays.fill(depth, 3, 11, 1f);
    DepthOutline mask =
        new DepthOutline(new float[][] {{.1f, .74f}, {.2f, .7f}}, .75f, new int[16], depth, 4, 4);
    assertEquals(0, mask.foreground);
  }
}
