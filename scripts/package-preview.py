"""Package already-built APK and reproducible source inputs; exclude local tools and keys."""
from pathlib import Path
import hashlib
import os
import shutil
import zipfile
import re

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))[1]
PREFIX = f"shiguangjian-{VERSION}"
DIST = ROOT / "dist"
DIST.mkdir(exist_ok=True)
apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, "APK has damaged ZIP entries"
    assert "AndroidManifest.xml" in archive.namelist()
shutil.copy2(apk, DIST / f"{PREFIX}-debug.apk")
screenshots = ROOT / "docs/screenshots"
screenshots.mkdir(exist_ok=True)
shutil.copy2(ROOT / "app/build/reports/screenshots/timeline.png", screenshots / "timeline.png")
for name in ["all-records.png"]:
    shutil.copy2(ROOT / "app/build/reports/screenshots" / name, screenshots / name)

root_files = [".gitignore", ".gitattributes", "README.md", "build.ps1", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"]
inputs = [ROOT / name for name in root_files]
for folder in ["app", "docs", "gradle", "scripts"]:
    for base, directories, files in os.walk(ROOT / folder):
        directories[:] = [d for d in directories if d not in {"build", ".gradle", "__pycache__"}]
        for name in files:
            path = Path(base) / name
            if path.suffix.lower() not in {".jks", ".keystore", ".pyc"}:
                inputs.append(path)
source = DIST / f"{PREFIX}-source.zip"
with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(inputs):
        archive.write(path, path.relative_to(ROOT).as_posix())
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    assert not any(".tools/" in name or "local.properties" in name for name in archive.namelist())

with (DIST / "SHA256SUMS.txt").open("w", encoding="ascii", newline="\n") as output:
    for file in [DIST / f"{PREFIX}-debug.apk", source]:
        with file.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        output.write(f"{digest}  {file.name}\n")
        print(f"{file.name}: {file.stat().st_size} bytes; SHA-256 {digest}")
