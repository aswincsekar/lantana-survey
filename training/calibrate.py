"""Fit a dry-biomass model from paired research and survey measurements."""

import argparse
import csv
import json
import math
from pathlib import Path
import numpy as np


def calibrate(rows, max_mae, max_width):
    # One independent observation per patch avoids repeated-visit leakage.
    patches = set()
    parts = {s: [] for s in ("fit", "calibration", "test")}
    versions = set()
    for r in rows:
        patch = r["patch"]
        if not patch or patch in patches:
            raise ValueError("Each patch must occur once")
        patches.add(patch)
        versions.add(r["model"])
        c, h, area, mass = map(
            float, (r["cover"], r["height_m"], r["area_m2"], r["dry_kg"])
        )
        if not all(map(math.isfinite, (c, h, area, mass))) or not (
            0 <= c <= 100 and h > 0 and area > 0 and mass >= 0
        ):
            raise ValueError("Check measurements and units")
        parts[r["split"]].append([c, h, mass / area])
    if len(versions) != 1 or "" in versions:
        raise ValueError("Use measurements from a single phone model version")
    fit, cal, test = [np.asarray(parts[s]) for s in ("fit", "calibration", "test")]
    if len(fit) < 3 or len(cal) < 9 or not len(test):
        raise ValueError(
            "Need independent fit, interval-calibration (at least 9 for 90%) and test samples"
        )

    def features(a):
        return np.column_stack(
            [np.ones(len(a)), a[:, 0] / 100, a[:, 0] / 100 * a[:, 1]]
        )

    w, _, rank, _ = np.linalg.lstsq(features(fit), fit[:, 2], rcond=None)
    if rank < 3:
        raise ValueError("Insufficient variation in cover and height")

    def predict(a):
        return np.maximum(0, features(a) @ w)

    residual = np.sort(abs(predict(cal) - cal[:, 2]))
    radius = float(residual[math.ceil(0.9 * (len(cal) + 1)) - 1])
    error = abs(predict(test) - test[:, 2])
    report = dict(
        test_patches=len(test),
        mae=float(error.mean()),
        coverage=float((error <= radius).mean()),
        interval_width=2 * radius,
    )
    print(json.dumps(report, indent=2))
    if (
        report["mae"] > max_mae
        or report["coverage"] < 0.9
        or report["interval_width"] > max_width
    ):
        raise ValueError("Held-out predictions missed the specified field criteria")
    return dict(
        model=versions.pop(),
        coefficients=w.tolist(),
        radius=radius,
        cover=[fit[:, 0].min(), fit[:, 0].max()],
        height=[fit[:, 1].min(), fit[:, 1].max()],
        evaluation=report,
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("csv", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--max-mae", type=float, required=True)
    p.add_argument("--max-width", type=float, required=True)
    a = p.parse_args()
    with a.csv.open() as f:
        result = calibrate(csv.DictReader(f), a.max_mae, a.max_width)
    a.output.write_text(json.dumps(result, indent=2) + "\n")
