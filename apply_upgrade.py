#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

BUNDLE = Path(__file__).resolve().parent
REPLACEMENTS = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/kotlin/com/sari/astro/DngImporter.kt",
    "app/src/main/kotlin/com/sari/astro/ProjectRepository.kt",
    "app/src/main/kotlin/com/sari/astro/GalleryActivity.kt",
    "app/src/main/kotlin/com/sari/astro/ProjectActivity.kt",
    "app/src/main/kotlin/com/sari/astro/ProjectsActivity.kt",
    "app/src/main/kotlin/com/sari/astro/ProcessingActivity.kt",
    "app/src/main/kotlin/com/sari/astro/EditorActivity.kt",
    "app/src/main/kotlin/com/sari/astro/nativebridge/NativeCore.kt",
]


def replace_exact(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 anchor in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def backup_file(repo: Path, rel: str, backup_root: Path) -> None:
    src = repo / rel
    dst = backup_root / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.exists():
        shutil.copy2(src, dst)


def patch_native(repo: Path) -> None:
    script = BUNDLE / "native_patch.py"
    rc = subprocess.call([sys.executable, str(script), str(repo)])
    if rc != 0:
        raise RuntimeError(f"Native patch failed with exit code {rc}")


def maybe_build(repo: Path) -> int:
    gradlew = repo / "gradlew"
    if gradlew.exists():
        print("Running Gradle release build…")
        gradlew.chmod(gradlew.stat().st_mode | 0o111)
        return subprocess.call([str(gradlew), "--no-daemon", ":app:assembleRelease"], cwd=repo)
    gradle = shutil.which("gradle")
    if gradle:
        return subprocess.call([gradle, "--no-daemon", ":app:assembleRelease"], cwd=repo)
    print("Gradle wrapper/system Gradle not found; source upgrade applied but APK build was not run.", file=sys.stderr)
    return 2


def main() -> int:
    ap = argparse.ArgumentParser(description="Apply the SARI Astro professional UI/import/processing upgrade.")
    ap.add_argument("--repo", required=True, help="Path to the existing SARI-ASTRO repo")
    ap.add_argument("--build", action="store_true", help="Run :app:assembleRelease after patching")
    args = ap.parse_args()
    repo = Path(args.repo).expanduser().resolve()
    if not (repo / "app/build.gradle.kts").is_file() or not (repo / "native/CMakeLists.txt").is_file():
        print(f"Not a SARI-ASTRO repo: {repo}", file=sys.stderr)
        return 1

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = repo / ".sari_astro_upgrade_backup" / stamp
    for rel in REPLACEMENTS + [
        "native/quality/quality.h", "native/quality/quality.cpp",
        "native/pipeline/pipeline.h", "native/pipeline/pipeline.cpp",
        "native/export/export.h", "native/export/export.cpp",
        "native/jni/sari_jni.cpp",
    ]:
        backup_file(repo, rel, backup)

    for rel in REPLACEMENTS:
        src = BUNDLE / rel
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    patch_native(repo)
    print(f"Applied SARI Astro professional upgrade to {repo}")
    print(f"Backup: {backup}")
    if args.build:
        rc = maybe_build(repo)
        if rc == 0:
            print("Release build completed. Look for app/build/outputs/apk/release/app-release.apk")
        return rc
    print("Next build command: ./gradlew --no-daemon :app:assembleRelease")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
