#!/usr/bin/env python3
"""POST a project zip to /build and get an APK back.

Cloud Run's request body limit is 32MB. Dependencies are downloaded
during the Gradle run, so the zip should be source plus the wrapper.
"""

import io
import os
import secrets
import shutil
import stat
import subprocess
import tempfile
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("PORT", "8080"))
TOKEN = os.environ.get("BUILD_TOKEN", "")
SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
MAX_ZIP = 32 * 1024 * 1024
MAX_UNCOMPRESSED = 400 * 1024 * 1024
MAX_FILES = 8000
TIMEOUT = int(os.environ.get("BUILD_TIMEOUT", "3000"))
BUILD_LOCK = threading.Lock()
SKIP_PARTS = {".gradle", ".git", "build", ".idea", "__MACOSX"}


def authorized(header: str) -> bool:
    if not TOKEN or not header.startswith("Bearer "):
        return False
    got = header[7:]
    if len(got) != len(TOKEN):
        return False
    return secrets.compare_digest(got, TOKEN)


def safe_task(raw: str) -> str:
    task = raw.strip() or "assembleDebug"
    if not task or len(task) > 80:
        raise ValueError("bad gradle task")
    for ch in task:
        if not (ch.isalnum() or ch in ":_-"):
            raise ValueError("bad gradle task")
    return task


def extract(data: bytes, dest: Path) -> None:
    total = 0
    count = 0
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            parts = [part for part in name.split("/") if part not in ("", ".")]
            if not parts or any(part == ".." for part in parts):
                raise ValueError("unsafe zip path")
            if any(part in SKIP_PARTS for part in parts):
                continue
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ValueError("symlink in zip")
            count += 1
            total += info.file_size
            if count > MAX_FILES or total > MAX_UNCOMPRESSED:
                raise ValueError("zip is too large to unpack")
            target = (dest / "/".join(parts)).resolve()
            if dest.resolve() != target and dest.resolve() not in target.parents:
                raise ValueError("unsafe zip path")
            if info.is_dir() or name.endswith("/"):
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)


def project_root(dest: Path) -> Path:
    markers = ("gradlew", "settings.gradle", "settings.gradle.kts")
    if any((dest / name).is_file() for name in markers):
        return dest
    kids = [path for path in dest.iterdir() if path.name not in SKIP_PARTS]
    if len(kids) == 1 and kids[0].is_dir():
        return project_root(kids[0])
    return dest


def find_apks(root: Path, task: str) -> list[Path]:
    found = []
    for path in root.rglob("*.apk"):
        parts = set(path.parts)
        if "outputs" not in parts or "androidTest" in parts:
            continue
        if any(part in SKIP_PARTS and part != "build" for part in path.parts):
            continue
        found.append(path)
    want = "release" if "release" in task.lower() else "debug"
    preferred = [path for path in found if want in path.name.lower()]
    return sorted(preferred or found)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        if urlparse(self.path).path == "/healthz":
            self._send(200, b"ok\n", "text/plain")
        else:
            self._send(404, b"not found\n", "text/plain")

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/build":
            self._send(404, b"not found\n", "text/plain")
            return
        if not authorized(self.headers.get("Authorization", "")):
            self._send(401, b"unauthorized\n", "text/plain")
            return
        try:
            task = safe_task(parse_qs(parsed.query).get("task", ["assembleDebug"])[0])
        except ValueError as exc:
            self._send(400, (str(exc) + "\n").encode(), "text/plain")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_ZIP:
            self._send(413, b"zip must be between 1 byte and 32MB\n", "text/plain")
            return
        if not BUILD_LOCK.acquire(blocking=False):
            self._send(429, b"a build is already running\n", "text/plain")
            return
        work = None
        try:
            data = self.rfile.read(length)
            if not zipfile.is_zipfile(io.BytesIO(data)):
                self._send(400, b"body must be a zip file\n", "text/plain")
                return
            work = Path(tempfile.mkdtemp(prefix="build-", dir="/home/builder/work"))
            try:
                extract(data, work)
            except (ValueError, zipfile.BadZipFile) as exc:
                self._send(400, (str(exc) + "\n").encode(), "text/plain")
                return
            root = project_root(work)
            gradlew = root / "gradlew"
            if not gradlew.is_file():
                self._send(400, b"zip has no gradlew\n", "text/plain")
                return
            gradlew.chmod(gradlew.stat().st_mode | 0o111)
            wrapper = gradlew.read_bytes()
            if b"\r\n" in wrapper[:300]:
                gradlew.write_bytes(wrapper.replace(b"\r\n", b"\n"))
            (root / "local.properties").write_text(f"sdk.dir={SDK}\n", encoding="utf-8")
            print(f"building {task} in {root.name}", flush=True)
            try:
                proc = subprocess.run(
                    [str(gradlew), "--no-daemon", "--no-watch-fs", task],
                    cwd=root,
                    env=os.environ.copy(),
                    capture_output=True,
                    text=True,
                    timeout=TIMEOUT,
                )
                log = (proc.stdout or "") + (proc.stderr or "")
            except subprocess.TimeoutExpired as exc:
                log = (exc.stdout or "") + (exc.stderr or "")
                self._fail(504, "build timed out\n" + log[-120_000:])
                return
            print(log[-8000:], flush=True)
            if proc.returncode != 0:
                self._fail(422, log[-120_000:] or "gradle failed\n")
                return
            apks = find_apks(root, task)
            app_apks = [path for path in apks if path.name in ("app-debug.apk", "app-release.apk")]
            if len(app_apks) == 1:
                apks = app_apks
            if not apks:
                self._fail(422, "gradle finished but no APK was produced\n" + log[-40_000:])
                return
            if len(apks) == 1:
                body = apks[0].read_bytes()
                self._send(
                    200,
                    body,
                    "application/vnd.android.package-archive",
                    extra={"Content-Disposition": f'attachment; filename="{apks[0].name}"'},
                )
                return
            packed = io.BytesIO()
            with zipfile.ZipFile(packed, "w", zipfile.ZIP_DEFLATED) as out:
                for apk in apks:
                    out.write(apk, apk.name)
            self._send(
                200,
                packed.getvalue(),
                "application/zip",
                extra={"Content-Disposition": 'attachment; filename="apks.zip"'},
            )
        finally:
            if work is not None:
                shutil.rmtree(work, ignore_errors=True)
            BUILD_LOCK.release()

    def _fail(self, code: int, text: str) -> None:
        self._send(code, text.encode("utf-8", errors="replace"), "text/plain")

    def _send(self, code: int, body: bytes, content_type: str, extra: dict | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        print(self.address_string(), fmt % args, flush=True)


def main() -> None:
    if not TOKEN:
        print("BUILD_TOKEN is empty. /build will reject every request.", flush=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"listening on {PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
