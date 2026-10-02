#!/usr/bin/env python3
"""POST a project zip to /build and get an APK back.

The response is chunked application/x-ndjson. Each line is an event.
Gradle progress, agent status, token use, and changed files stream as
they happen. The apk event names the byte length, and the raw APK
follows that line. Only files the agent changed are sent back.

Cloud Run's request body limit is 32MB. Dependencies are downloaded
during the Gradle run, so the zip should be source plus the wrapper.
"""

import io
import json
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

import agent

PORT = int(os.environ.get("PORT", "8080"))
TOKEN = os.environ.get("BUILD_TOKEN", "")
SDK = os.environ.get("ANDROID_HOME", "/opt/android-sdk")
MAX_ZIP = 32 * 1024 * 1024
MAX_UNCOMPRESSED = 400 * 1024 * 1024
MAX_FILES = 8000
TIMEOUT = int(os.environ.get("BUILD_TIMEOUT", "3000"))
FIXES = max(0, int(os.environ.get("BUILD_FIXES", "2")))
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


def worth_line(line: str) -> bool:
    text = line.strip()
    if not text:
        return False
    if text.startswith("> Task") or text.startswith("BUILD "):
        return True
    markers = (
        "FAILED",
        "FAILURE:",
        "error:",
        "e: ",
        "w: ",
        "Execution failed",
        "What went wrong",
    )
    return any(marker in text for marker in markers)


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
        streaming = False
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
            cfg = agent.config_from(self.headers)
            print(f"building {task} in {root.name} agent={cfg is not None}", flush=True)
            self._begin_stream()
            streaming = True
            summary = self._compile(root, task, cfg)
            if summary is None:
                return
            packed = self._package(root, task)
            if packed is None:
                self._emit({"event": "error", "text": "gradle finished but no APK was produced"})
                return
            name, body = packed
            self._emit({"event": "apk", "name": name, "bytes": len(body), "summary": summary})
            self._raw(body)
        except Exception as exc:
            print(f"build error {exc}", flush=True)
            if streaming:
                try:
                    self._emit({"event": "error", "text": str(exc)[:1500]})
                except Exception:
                    pass
            else:
                self._fail(500, str(exc)[:1500])
        finally:
            if streaming:
                try:
                    self._end_stream()
                except Exception:
                    pass
            if work is not None:
                shutil.rmtree(work, ignore_errors=True)
            BUILD_LOCK.release()

    def _compile(self, root: Path, task: str, cfg: dict | None) -> str | None:
        summary = ""
        totals = {"input": 0, "output": 0}
        attempts = FIXES + 1 if cfg else 1
        for attempt in range(attempts):
            label = f"gradle {task}" if attempt == 0 else f"gradle {task} (try {attempt + 1})"
            self._emit({"event": "log", "text": label})
            try:
                code, log = self._gradle(root, task)
            except TimeoutError:
                self._emit({"event": "error", "text": "build timed out"})
                return None
            print(log[-4000:], flush=True)
            if code == 0:
                self._emit({"event": "log", "text": "BUILD SUCCESSFUL"})
                return summary
            self._emit({"event": "log", "text": "gradle failed"})
            if cfg is None or attempt == attempts - 1:
                tail = (log[-2000:] or "gradle failed").strip()
                why = "still failing after auto-fix" if cfg else "gradle failed, and no Vibe API key was sent to auto-fix it"
                self._emit({"event": "error", "text": why + "\n" + tail})
                return None
            self._emit({"event": "agent", "text": f"asking {cfg['model']} to fix the compile error"})
            try:
                summary, edits, usage = agent.repair(root, log, cfg, self._emit)
            except Exception as exc:
                self._emit({"event": "error", "text": f"agent failed: {exc}"})
                return None
            if usage:
                totals["input"] += usage[0]
                totals["output"] += usage[1]
                self._emit(
                    {
                        "event": "tokens",
                        "input": usage[0],
                        "output": usage[1],
                        "total": usage[0] + usage[1],
                        "session": totals["input"] + totals["output"],
                    }
                )
            else:
                self._emit({"event": "agent", "text": "model finished without reporting token use"})
            if not edits:
                self._emit({"event": "error", "text": summary or "model did not change any files"})
                return None
            applied = 0
            for path, content in edits:
                try:
                    diff = agent.unified(root, path, content)
                    agent.write_rel(root, path, content)
                except ValueError as exc:
                    self._emit({"event": "log", "text": str(exc)})
                    continue
                applied += 1
                if diff:
                    self._emit({"event": "diff", "path": path, "text": diff})
                self._emit({"event": "change", "path": path, "content": content})
            if applied == 0:
                self._emit({"event": "error", "text": "model changes could not be applied"})
                return None
            self._emit({"event": "log", "text": f"applied {applied} file(s), rebuilding"})
        return None

    def _gradle(self, root: Path, task: str) -> tuple[int, str]:
        gradlew = root / "gradlew"
        proc = subprocess.Popen(
            [str(gradlew), "--no-daemon", "--no-watch-fs", task],
            cwd=root,
            env=os.environ.copy(),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        lines: list[str] = []
        failed: list[BaseException] = []

        def read_output() -> None:
            assert proc.stdout is not None
            streamed = 0
            size = 0
            try:
                for line in proc.stdout:
                    lines.append(line)
                    size += len(line)
                    while size > 200_000 and len(lines) > 1:
                        size -= len(lines.pop(0))
                    if streamed < 100 and worth_line(line):
                        streamed += 1
                        self._emit({"event": "log", "text": line.rstrip()[:400]})
            except BaseException as exc:
                failed.append(exc)
                proc.kill()

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            code = proc.wait(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            proc.kill()
            reader.join(timeout=5)
            raise TimeoutError("build timed out")
        reader.join(timeout=10)
        if failed:
            raise failed[0]
        return code, "".join(lines)

    def _package(self, root: Path, task: str) -> tuple[str, bytes] | None:
        apks = find_apks(root, task)
        app_apks = [path for path in apks if path.name in ("app-debug.apk", "app-release.apk")]
        if len(app_apks) == 1:
            apks = app_apks
        if not apks:
            return None
        if len(apks) == 1:
            return apks[0].name, apks[0].read_bytes()
        packed = io.BytesIO()
        with zipfile.ZipFile(packed, "w", zipfile.ZIP_DEFLATED) as out:
            for path in apks:
                out.write(path, path.name)
        return "apks.zip", packed.getvalue()

    def _begin_stream(self) -> None:
        self._write_lock = threading.Lock()
        self.close_connection = True
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

    def _emit(self, obj: dict) -> None:
        self._chunk((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    def _raw(self, data: bytes) -> None:
        step = 64 * 1024
        for start in range(0, len(data), step):
            self._chunk(data[start : start + step])

    def _chunk(self, data: bytes) -> None:
        if not data:
            return
        header = f"{len(data):X}\r\n".encode("ascii")
        with self._write_lock:
            self.wfile.write(header)
            self.wfile.write(data)
            self.wfile.write(b"\r\n")
            self.wfile.flush()

    def _end_stream(self) -> None:
        with self._write_lock:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()

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
