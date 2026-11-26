"""Fit a sparse-label segmentation head to frozen ImageNet convolutional features."""

import hashlib
import json
from pathlib import Path
import numpy as np
import onnx
import onnxruntime as ort
from onnx import helper, numpy_helper, TensorProto
from PIL import Image, ImageDraw
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "mobile/app/src/main/assets"
SIZE = 224
MEAN = np.array([0.485, 0.456, 0.406], dtype="f")
STD = np.array([0.229, 0.224, 0.225], dtype="f")


def fit(x, y):
    return make_pipeline(
        StandardScaler(),
        LogisticRegression(C=0.04, class_weight="balanced", max_iter=500),
    ).fit(x, y)


def train():
    work = ROOT / "build/tools"
    backbone = work / "squeezenet.onnx"
    features = "squeezenet0_concat1"
    original = onnx.load(backbone)
    original.ir_version = 7
    onnx.save(original, work / "backbone.onnx")
    onnx.utils.extract_model(
        str(work / "backbone.onnx"),
        str(work / "features.onnx"),
        ["data"],
        [features],
        check_model=False,
    )
    model = onnx.load(work / "features.onnx")
    session = ort.InferenceSession(
        model.SerializeToString(), providers=["CPUExecutionProvider"]
    )
    samples, labels, groups = [], [], []
    for name, region in json.loads((ROOT / "data/regions.json").read_text()).items():
        im = (
            Image.open(ROOT / f"data/photos/{name}.jpg")
            .convert("RGB")
            .resize((SIZE, SIZE), Image.Resampling.BILINEAR)
        )
        rgb = (np.asarray(im, dtype="f") / 255 - MEAN) / STD
        grid = session.run(None, {"data": rgb.transpose(2, 0, 1)[None]})[0][
            0
        ].transpose(1, 2, 0)
        mask = Image.new("L", im.size, 255)
        draw = ImageDraw.Draw(mask)
        for label, key in enumerate(["negative", "positive"]):
            for polygon in region[key]:
                draw.polygon(list(map(tuple, polygon)), fill=label)
        reviewed = ROOT / f"data/masks/{name}.png"
        if reviewed.exists():
            mask = Image.open(reviewed).convert("L")
            if not np.isin(np.unique(mask), [0, 1, 255]).all():
                raise ValueError(f"Invalid mask values: {reviewed}")
        target = np.asarray(mask.resize(grid.shape[:2][::-1], Image.Resampling.NEAREST))
        known = target != 255
        samples.append(grid[known])
        labels.extend(target[known])
        groups.extend([region["group"]] * int(known.sum()))
    x, y, group = np.concatenate(samples), np.asarray(labels), np.asarray(groups)
    evaluation = {}
    for site in sorted(set(groups)):
        test = group == site
        predictions = fit(x[~test], y[~test]).predict(x[test])
        evaluation[site] = confusion_matrix(
            y[test], predictions, labels=[0, 1]
        ).tolist()
    (ROOT / "training/evaluation.json").write_text(
        json.dumps(evaluation, indent=2) + "\n"
    )
    scale, reg = fit(x, y).steps
    scaler, head = scale[1], reg[1]
    weights = head.coef_[0] / scaler.scale_
    bias = head.intercept_ - weights @ scaler.mean_
    model.graph.initializer.extend(
        [
            numpy_helper.from_array(weights.astype("f").reshape(1, -1, 1, 1), "head_w"),
            numpy_helper.from_array(bias.astype("f"), "head_b"),
        ]
    )
    model.graph.node.extend(
        [
            helper.make_node(
                "Conv", [features, "head_w", "head_b"], ["logits"], kernel_shape=[1, 1]
            ),
            helper.make_node("Sigmoid", ["logits"], ["mask"]),
        ]
    )
    del model.graph.output[:]
    model.graph.output.append(
        helper.make_tensor_value_info(
            "mask", TensorProto.FLOAT, [1, 1, *grid.shape[:2]]
        )
    )
    onnx.checker.check_model(model)
    path = ASSETS / "lantana.onnx"
    onnx.save(model, path)
    output = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]).run(
        None, {"data": rgb.transpose(2, 0, 1)[None]}
    )[0]
    np.testing.assert_allclose(
        output.flatten(),
        head.predict_proba(scaler.transform(grid.reshape(-1, grid.shape[-1])))[:, 1],
        atol=1e-5,
    )
    print(path, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    train()
