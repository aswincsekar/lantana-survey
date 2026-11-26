"""Propagate reviewer-selected Lantana points with SAM 2 for later mask review."""

import argparse
import json
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "frames", type=Path, help="Directory of numbered JPEG frames in capture order"
    )
    p.add_argument(
        "prompts",
        type=Path,
        help="JSON array of {frame,points,labels}; coordinates in original pixels",
    )
    p.add_argument("checkpoint")
    p.add_argument("config")
    p.add_argument("output", type=Path)
    p.add_argument("--device", default="cpu")
    a = p.parse_args()
    import torch
    import numpy as np
    from PIL import Image
    from sam2.build_sam import build_sam2_video_predictor

    predictor = build_sam2_video_predictor(a.config, a.checkpoint, device=a.device)
    a.output.mkdir(parents=True, exist_ok=False)
    with torch.inference_mode():
        state = predictor.init_state(video_path=str(a.frames))
        for prompt in json.loads(a.prompts.read_text()):
            predictor.add_new_points_or_box(
                inference_state=state,
                frame_idx=prompt["frame"],
                obj_id=1,
                points=np.asarray(prompt["points"], dtype=np.float32),
                labels=np.asarray(prompt["labels"], dtype=np.int32),
            )
        for idx, ids, logits in predictor.propagate_in_video(state):
            mask = (logits[ids.index(1), 0] > 0).cpu().numpy().astype("uint8")
            Image.fromarray(mask).save(a.output / f"{idx:06}.png")


if __name__ == "__main__":
    main()
