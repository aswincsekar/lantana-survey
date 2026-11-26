# Vanarekha

Android field prototype for repeat visits to a Lantana patch. The phone runs a bundled convolutional segmentation model, keeps an offline journal and maps GPS fixes on a north-up metric grid. Capture, inference and reading past visits work without signing in or downloading a model.

[Download the Android pilot](https://aswincsekar.github.io/lantana-survey/) · [APK releases](https://github.com/aswincsekar/lantana-survey/releases)

| Directory | Contents |
| --- | --- |
| [mobile](mobile/) | Native Android capture, offline inference, visit storage and journal |
| [backend](backend/) | Visit upload API and monitoring dashboard |
| [cloud/gcp](cloud/gcp/) | Google Cloud Build, Cloud Run and Cloud Storage configuration |
| [docs](docs/) | Static GitHub Pages download page |
| [training](training/) | Model training, evaluation and biomass calibration |

## Try the pilot

Install the signed debug APK from `build/vanarekha-debug.apk` on Android 9 or newer. Under **Connection → Test model on sample photo**, the phone runs the actual model on a public training photograph without creating a visit. **Import photo** analyses a local photograph and saves it. Imported images have no location attached.

For a field observation tap **Scan a patch**, include the base and canopy, and move slowly sideways. The app samples frames for ten seconds, aligns adjacent thumbnails to check mask agreement, and saves the sharpest overview and its mask. The visible-cover percentage is the fraction of mask cells classified as Lantana in that view. The device stores GPS accuracy, magnetic bearing when available, measurement quality, and the model fingerprint. Location can take longer than a scan to acquire under canopy. Visits without a recent fix remain in the journal.

Open a visit and choose **Rescan this patch** to record a post-clearance or regrowth visit with the same patch ID and a translucent framing reference. The dashboard compares the visit with the earliest photograph for that patch. On phones with a compatible, already-installed ARCore service, the camera path uses confident raw depth samples over a tracked horizontal surface to estimate the upper visible canopy height. Missing or inconsistent geometry leaves height empty. Height still needs validation against pole measurements in forest conditions.

## Android build

Open the `mobile/` directory in Android Studio with SDK 35 and JDK 17 or newer. Gradle resolves ONNX Runtime Android 1.20 and optional ARCore 1.48. A standalone, signed debug build is also available using JDK 17+, SDK Platform 35 and SDK Build Tools 35.0.0:

```sh
python3 scripts/build_apk.py
```

Set `ANDROID_HOME` when the SDK is outside the default macOS location. The script caches the two official AARs in `build/tools/` and generates a local debug signing key. Keep the key to update the same installation. For a field release, use the project's Android release signing process.

## Segmentation and field data

The bundled model runs a frozen ImageNet SqueezeNet 1.1 feature extractor and a learned 1×1 convolutional classification head on 224×224 RGB inputs. Its output is a per-cell foreground probability. The app displays a thresholded 55×55 mask. Runtime inference uses ONNX Runtime on CPU. All weights are in the APK.

The baseline uses ten photographs in `data/photos`. Source-level species identifications and contributor credits are in [the data credits](data/CREDITS.md). Sparse foreground and background polygons in `data/regions.json` were selected visually for this prototype. They have not undergone botanical expert review. Capture series stay together during evaluation. [Grouped evaluation](training/evaluation.json) gives a confusion matrix per excluded group, with true background and foreground as rows and predicted background and foreground as columns. These are labelled mask cells from a small photo set. They are not independent plants or field plots. The BRT removal scene contributes only clearly identifiable background.

To reproduce training, run the following from the repository root with Python 3.12:

```sh
python3.12 -m venv .venv
.venv/bin/pip install -r training/requirements.txt
mkdir -p build/tools
curl -L --fail https://media.githubusercontent.com/media/onnx/models/main/validated/vision/classification/squeezenet/model/squeezenet1.1-7.onnx -o build/tools/squeezenet.onnx
.venv/bin/python training/train.py
```

The [ONNX Model Zoo SqueezeNet](https://github.com/onnx/models/tree/main/validated/vision/classification/squeezenet) backbone SHA-256 is `1eeff551a67ae8d565ca33b572fc4b66e3ef357b0eb2863bb9ff47a918cc4088`. Training exports the head and backbone together, runs the exported graph, checks it against the fitted classifier, and writes the evaluation file.

Add researcher photographs to `data/photos/<name>.jpg`. Give each a patch or capture-series group and positive/negative polygon lists on a 224×224 reference grid. Alternatively, supply a matching grayscale `data/masks/<name>.png` with 0 for known background, 1 for Lantana, and 255 for unknown. The reviewed PNG takes precedence. Keep independent pilot patches completely outside this training manifest for final field evaluation.

For authorised numbered JPEG video frames, [the SAM 2 helper](training/draft_video.py) accepts researcher prompts and outputs draft masks on a training workstation with [SAM 2](https://github.com/facebookresearch/sam2) and its checkpoint installed. Prompt records contain a frame index, original-pixel xy points and corresponding foreground/background labels. Review mask drift and mark ambiguous areas before adding frames to a training group. No research video or SAM checkpoint ships in the APK.

## Dashboard and delayed sync

The dashboard uses the same journal, map, and comparison views as Android. To run it locally, set a private `FIELD_TOKEN` environment variable and run `python3 backend/server.py`. Open `http://127.0.0.1:8080` and enter that token. The development server stores visits in SQLite. Set `FIELD_DB` to choose the database location.

The Dockerfile in `cloud/gcp/` packages the service for Cloud Run. Set `FIELD_BUCKET` to the project’s private Cloud Storage bucket, provide `FIELD_TOKEN` from a secret, and grant the runtime service account object create, get, and list access. The pilot uses one shared team token over HTTPS. Set the service URL and token under **Connection → Configure sync** on each device. The app queues unsent visits in SQLite and schedules a network-constrained Android job. A stable visit UUID and immutable object creation make retries safe. A conflicting reuse of an ID returns HTTP 409. This workspace does not include a live cloud deployment or credentials.

Run `python3 -m unittest backend.test_server` to check authentication, retry behaviour, conflict handling and dashboard reads against real image data.

## Biomass calibration and field evaluation

Dry biomass requires paired field measurements. The APK ships without calibration coefficients. [The calibration script](training/calibrate.py) takes CSV fields `patch,model,split,cover,height_m,area_m2,dry_kg`. Match each harvested plot to a scan from the same footprint, enter dry above-ground mass, and use one row per independent patch. Split patches into `fit`, `calibration`, and `test` before fitting. Use the model fingerprint recorded in the visit. Pass an acceptable held-out MAE and maximum interval width in kg/m² via `--max-mae` and `--max-width`.

The script fits cover and cover × height, calibrates a 90% split-conformal interval on independent calibration patches, and checks error, interval coverage and width on held-out patches. When the estimates meet the field team's criteria, export to `mobile/app/src/main/assets/biomass.json` and rebuild. The phone checks the model fingerprint, measurement quality, height and calibrated predictor ranges before showing an estimate.

For the pilot, measure false positives on local lookalikes, visible-cover error against complete expert masks, repeated-operator disagreement, height error against pole readings and the share of ten-second sweeps that yield usable measurements. Record non-flowering patches, mixed vegetation and post-clearance scenes separately.
