"""Build a debug APK with the installed Android SDK and cached official AARs."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
TOOLS = SDK / "build-tools/35.0.0"
ANDROID = SDK / "platforms/android-35/android.jar"
MAIN = ROOT / "mobile/app/src/main"
CACHE = OUT / "tools"
ARTIFACTS = {
    "runtime": "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.20.0/onnxruntime-android-1.20.0.aar",
    "arcore": "https://dl.google.com/android/maven2/com/google/ar/core/1.48.0/core-1.48.0.aar",
}


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def build():
    CACHE.mkdir(parents=True, exist_ok=True)
    stage = OUT / "apk-stage"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    jars = []
    for name, url in ARTIFACTS.items():
        aar = CACHE / (name + ".aar")
        if not aar.exists():
            run("curl", "-L", "--fail", url, "-o", aar)
        with zipfile.ZipFile(aar) as archive:
            archive.extractall(stage / name)
        jars.append(stage / name / "classes.jar")
    run(TOOLS / "aapt2", "compile", "--dir", MAIN / "res", "-o", stage / "app-res.zip")
    # Compile the ARCore library resources under its original Java package.
    run(
        TOOLS / "aapt2",
        "compile",
        "--dir",
        stage / "arcore/res",
        "-o",
        stage / "ar.zip",
    )
    run(
        TOOLS / "aapt2",
        "link",
        "-o",
        stage / "base.apk",
        "-I",
        ANDROID,
        "--min-sdk-version",
        "28",
        "--target-sdk-version",
        "32",
        "--version-code",
        "7",
        "--version-name",
        "0.7",
        "--manifest",
        MAIN / "AndroidManifest.xml",
        "--java",
        stage / "generated",
        "--extra-packages",
        "com.google.ar.core",
        "-A",
        MAIN / "assets",
        stage / "ar.zip",
        stage / "app-res.zip",
    )
    classes = stage / "classes"
    classes.mkdir()
    run(
        "javac",
        "--release",
        "11",
        "-classpath",
        os.pathsep.join(map(str, [ANDROID, *jars])),
        "-d",
        classes,
        *sorted((MAIN / "java").rglob("*.java")),
        *sorted((stage / "generated").rglob("*.java")),
    )
    run("jar", "cf", stage / "app.jar", "-C", classes, ".")
    run(
        TOOLS / "d8",
        "--min-api",
        "28",
        "--lib",
        ANDROID,
        "--output",
        stage / "dex.zip",
        stage / "app.jar",
        *jars,
    )
    with (
        zipfile.ZipFile(stage / "base.apk", "a") as apk,
        zipfile.ZipFile(stage / "dex.zip") as dex,
    ):
        for name in dex.namelist():
            apk.writestr(name, dex.read(name))
        for name in ARTIFACTS:
            for lib in (stage / name / "jni").rglob("*.so"):
                apk.write(
                    lib,
                    "lib/" + lib.parent.name + "/" + lib.name,
                    compress_type=zipfile.ZIP_DEFLATED,
                )
    run(TOOLS / "zipalign", "-f", "4", stage / "base.apk", stage / "aligned.apk")
    key = CACHE / "debug.keystore"
    if not key.exists():
        run(
            "keytool",
            "-genkeypair",
            "-keystore",
            key,
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "androiddebugkey",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "10000",
            "-dname",
            "CN=Android Debug",
        )
    result = OUT / "vanarekha-debug.apk"
    run(
        TOOLS / "apksigner",
        "sign",
        "--ks",
        key,
        "--ks-pass",
        "pass:android",
        "--out",
        result,
        stage / "aligned.apk",
    )
    run(TOOLS / "apksigner", "verify", result)
    print(result, hashlib.sha256(result.read_bytes()).hexdigest())


if __name__ == "__main__":
    build()
