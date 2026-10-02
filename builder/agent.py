"""Fix a failed Gradle build with the caller's model. Stdlib only."""

import difflib
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path

MAX_FILES = 8
MAX_FILE_CHARS = 40_000
MAX_LOG_CHARS = 24_000
MAX_EDITS = 12
MAX_EDIT_CHARS = 500_000

BLOCK_NAMES = {"local.properties", "gradlew", "gradlew.bat"}
BLOCK_EXT = {
    ".jar", ".apk", ".so", ".aar", ".jks", ".keystore", ".png", ".jpg",
    ".jpeg", ".webp", ".gif", ".zip", ".class",
}

PROMPT = """
You fix an Android Gradle build that failed on a compile server. Reply with one JSON object and nothing else:
{"summary":"what you changed","files":[{"path":"relative/path","content":"the full new file"}]}

Paths are relative to the repo root. Use forward slashes. Never use .. or absolute paths. Include the complete contents of every file you change or create. Omit files you do not change. Change as little as possible. Do not modify gradlew, local.properties, or signing files. Do not wrap the JSON in markdown.
""".strip()

FILE_RE = re.compile(
    r"""(?P<path>(?:[A-Za-z]:)?[^\s:'\"`()]+?\.(?:kt|kts|java|xml|gradle|toml|pro|properties))(?::(?P<line>\d+))?"""
)


def config_from(headers) -> dict | None:
    key = (headers.get("X-Agent-Key") or os.environ.get("AGENT_API_KEY") or "").strip()
    model = (headers.get("X-Agent-Model") or os.environ.get("AGENT_MODEL") or "").strip()
    if not key or not model or any(ch.isspace() for ch in model):
        return None
    provider = (headers.get("X-Agent-Provider") or os.environ.get("AGENT_PROVIDER") or "openai").strip().lower()
    base = (headers.get("X-Agent-Base") or os.environ.get("AGENT_BASE") or "").strip()
    return {"provider": provider, "key": key, "model": model, "base": base}


def repair(root: Path, log: str, cfg: dict, emit) -> tuple[str, list[tuple[str, str]], tuple[int, int] | None]:
    files, omitted = pick_files(root, log)
    user = user_prompt(log, files, omitted)
    watch = SummaryWatch(emit)
    raw, usage = complete(cfg, user, watch)
    summary, edits = parse_edits(raw)
    if not watch.sent and summary:
        emit({"event": "agent", "text": summary[:400]})
    return summary, edits, usage


def unified(root: Path, rel: str, content: str) -> str:
    target = resolve_rel(root, rel)
    old = ""
    if target.is_file():
        old = target.read_text(encoding="utf-8", errors="replace")
    if old == content:
        return ""
    lines = list(
        difflib.unified_diff(
            old.splitlines(),
            content.splitlines(),
            fromfile=f"a/{rel}",
            tofile=f"b/{rel}",
            lineterm="",
        )
    )
    if len(lines) > 80:
        lines = lines[:80] + ["…"]
    return "\n".join(lines)


def write_rel(root: Path, rel: str, content: str) -> None:
    if len(content) > MAX_EDIT_CHARS:
        raise ValueError(f"refusing a file over 500KB: {rel}")
    if "\x00" in content:
        raise ValueError(f"refusing binary content: {rel}")
    target = resolve_rel(root, rel)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def resolve_rel(root: Path, rel: str) -> Path:
    text = rel.strip().replace("\\", "/").lstrip("/")
    parts = [part for part in text.split("/") if part not in ("", ".")]
    if not parts or any(part == ".." or part == ".git" for part in parts):
        raise ValueError(f"bad path: {rel}")
    name = parts[-1]
    suffix = Path(name).suffix.lower()
    if name in BLOCK_NAMES or suffix in BLOCK_EXT:
        raise ValueError(f"refusing {rel}")
    target = (root.joinpath(*parts)).resolve()
    base = root.resolve()
    if target != base and base not in target.parents:
        raise ValueError(f"outside project: {rel}")
    return target


def pick_files(root: Path, log: str) -> tuple[list[tuple[str, str]], list[str]]:
    scored: dict[str, int] = {}
    for match in FILE_RE.finditer(log):
        rel = relative_to_root(root, match.group("path"))
        if rel is None:
            continue
        scored[rel] = scored.get(rel, 0) + (5 if match.group("line") else 1)
    ordered = sorted(scored, key=lambda path: (-scored[path], path))
    if not ordered:
        for extra in (
            "settings.gradle.kts",
            "settings.gradle",
            "app/build.gradle.kts",
            "app/build.gradle",
            "build.gradle.kts",
            "build.gradle",
        ):
            if (root / extra).is_file():
                ordered.append(extra)
    chosen = []
    omitted = []
    for rel in ordered:
        if len(chosen) >= MAX_FILES:
            break
        try:
            target = resolve_rel(root, rel)
        except ValueError:
            continue
        if not target.is_file():
            continue
        if target.stat().st_size > MAX_FILE_CHARS:
            omitted.append(rel)
            continue
        text = target.read_text(encoding="utf-8", errors="replace")
        chosen.append((rel, text))
    return chosen, omitted


def relative_to_root(root: Path, raw: str) -> str | None:
    text = raw.replace("\\", "/")
    if text.startswith("file://"):
        text = text[7:]
    if "://" in text:
        return None
    path = Path(text)
    try:
        if path.is_absolute():
            rel = path.resolve().relative_to(root.resolve())
        else:
            candidate = (root / path).resolve()
            rel = candidate.relative_to(root.resolve())
    except (ValueError, OSError):
        return None
    rel_s = rel.as_posix()
    if not rel_s or rel_s == ".":
        return None
    return rel_s


def user_prompt(log: str, files: list[tuple[str, str]], omitted: list[str]) -> str:
    parts = ["Gradle failed.\n\n", log[-MAX_LOG_CHARS:], "\n\n"]
    if omitted:
        parts.append("These files were too large to include. Do not rewrite them:\n")
        parts.append("\n".join(omitted))
        parts.append("\n\n")
    if not files:
        parts.append("No source files could be matched to the error.\n")
    else:
        parts.append("Current files:\n")
        for path, content in files:
            parts.append(f"----- FILE {path} -----\n")
            parts.append(content)
            if not content.endswith("\n"):
                parts.append("\n")
            parts.append("----- END FILE -----\n")
    parts.append("\nFix the build. Return JSON only.")
    return "".join(parts)


class SummaryWatch:
    def __init__(self, emit):
        self.emit = emit
        self.buf: list[str] = []
        self.sent = False
        self.started = False

    def add(self, text: str) -> None:
        if not text:
            return
        if not self.started:
            self.started = True
            self.emit({"event": "agent", "text": "model is writing a fix"})
        self.buf.append(text)
        if self.sent:
            return
        raw = "".join(self.buf)
        match = re.search(r'"summary"\s*:\s*"((?:\\.|[^"\\])*)"', raw)
        if not match:
            return
        try:
            summary = json.loads('"' + match.group(1) + '"')
        except json.JSONDecodeError:
            return
        summary = str(summary).strip()
        if not summary:
            return
        self.emit({"event": "agent", "text": summary[:400]})
        self.sent = True


def complete(cfg: dict, user: str, watch: SummaryWatch) -> tuple[str, tuple[int, int] | None]:
    provider = cfg["provider"]
    if provider == "anthropic":
        return anthropic(cfg, user, watch)
    if provider == "gemini":
        return gemini(cfg, user, watch)
    return openai(cfg, user, watch)


def openai(cfg: dict, user: str, watch: SummaryWatch) -> tuple[str, tuple[int, int] | None]:
    last = "model request failed"
    stream_ok = True
    for stream in (True, False):
        if stream and not stream_ok:
            continue
        for field in ("max_tokens", "max_completion_tokens"):
            try:
                return openai_call(cfg, user, watch, stream, field)
            except urllib.error.HTTPError as exc:
                body = exc.read().decode("utf-8", errors="replace")[:800]
                last = f"HTTP {exc.code}: {short_error(body)}"
                if exc.code in (401, 403):
                    raise RuntimeError(last) from exc
                if stream and "stream" in body.lower():
                    stream_ok = False
                    break
                if "max_tokens" in body or "max_completion_tokens" in body:
                    continue
                raise RuntimeError(last) from exc
    raise RuntimeError(last)


def openai_call(cfg, user, watch, stream: bool, field: str) -> tuple[str, tuple[int, int] | None]:
    url = chat_url(cfg["base"] or "https://api.openai.com/v1")
    body = {
        "model": cfg["model"],
        "messages": [
            {"role": "system", "content": PROMPT},
            {"role": "user", "content": user},
        ],
        field: 8192,
        "stream": stream,
    }
    if stream:
        body["stream_options"] = {"include_usage": True}
    payload = post(url, {"Authorization": f"Bearer {cfg['key']}"}, body, stream=stream)
    if not stream:
        text, usage = openai_text(json.loads(payload))
        watch.add(text)
        return text, usage
    chunks: list[str] = []
    usage = None
    for event in sse_json(payload):
        got = usage_pair(event.get("usage"))
        if got:
            usage = got
        choices = event.get("choices") or []
        if not choices:
            continue
        delta = (choices[0].get("delta") or {}).get("content")
        if isinstance(delta, str) and delta:
            chunks.append(delta)
            watch.add(delta)
    text = "".join(chunks).strip()
    if not text:
        raise RuntimeError("empty response")
    return text, usage


def anthropic(cfg: dict, user: str, watch: SummaryWatch) -> tuple[str, tuple[int, int] | None]:
    url = anthropic_url(cfg["base"] or "https://api.anthropic.com")
    body = {
        "model": cfg["model"],
        "max_tokens": 8192,
        "system": PROMPT,
        "messages": [{"role": "user", "content": user}],
        "stream": True,
    }
    try:
        payload = post(
            url,
            {"x-api-key": cfg["key"], "anthropic-version": "2023-06-01"},
            body,
            stream=True,
        )
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:800]
        raise RuntimeError(f"HTTP {exc.code}: {short_error(detail)}") from exc
    chunks: list[str] = []
    usage_in = 0
    usage_out = 0
    saw = False
    for event in sse_json(payload):
        kind = event.get("type")
        if kind == "message_start":
            got = usage_pair((event.get("message") or {}).get("usage"))
            if got:
                usage_in, usage_out = got
                saw = True
        elif kind == "message_delta":
            got = usage_pair(event.get("usage"))
            if got:
                if got[0]:
                    usage_in = got[0]
                if got[1]:
                    usage_out = got[1]
                saw = True
        elif kind == "content_block_delta":
            text = (event.get("delta") or {}).get("text")
            if isinstance(text, str) and text:
                chunks.append(text)
                watch.add(text)
    text = "".join(chunks).strip()
    if not text:
        raise RuntimeError("empty response")
    return text, (usage_in, usage_out) if saw else None


def gemini(cfg: dict, user: str, watch: SummaryWatch) -> tuple[str, tuple[int, int] | None]:
    model = cfg["model"].removeprefix("models/").strip()
    base = (cfg["base"] or "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
    if ":streamGenerateContent" in base or ":generateContent" in base:
        url = base
    else:
        url = f"{base}/models/{model}:streamGenerateContent?alt=sse"
    body = {
        "systemInstruction": {"parts": [{"text": PROMPT}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "generationConfig": {"maxOutputTokens": 8192},
    }
    try:
        payload = post(url, {"x-goog-api-key": cfg["key"]}, body, stream=True)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:800]
        raise RuntimeError(f"HTTP {exc.code}: {short_error(detail)}") from exc
    chunks: list[str] = []
    usage = None
    for event in sse_json(payload):
        got = usage_pair(event.get("usageMetadata"))
        if got:
            usage = got
        candidates = event.get("candidates") or []
        if not candidates:
            continue
        parts = ((candidates[0].get("content") or {}).get("parts")) or []
        for part in parts:
            text = part.get("text") if isinstance(part, dict) else None
            if isinstance(text, str) and text:
                chunks.append(text)
                watch.add(text)
    text = "".join(chunks).strip()
    if not text:
        raise RuntimeError("empty response")
    return text, usage


def post(url: str, headers: dict, body: dict, stream: bool) -> str | object:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    req.add_header("Accept", "text/event-stream" if stream else "application/json")
    req.add_header("User-Agent", "AndVibe")
    for name, value in headers.items():
        req.add_header(name, value)
    try:
        resp = urllib.request.urlopen(req, timeout=180)
    except urllib.error.HTTPError:
        raise
    except Exception as exc:
        raise RuntimeError(f"network: {exc}") from exc
    if stream:
        return resp
    with resp:
        return resp.read().decode("utf-8", errors="replace")


def sse_json(resp):
    with resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if not data or data == "[DONE]":
                if data == "[DONE]":
                    break
                continue
            try:
                event = json.loads(data)
            except json.JSONDecodeError:
                continue
            if isinstance(event, dict):
                yield event


def openai_text(payload: dict) -> tuple[str, tuple[int, int] | None]:
    choices = payload.get("choices") or []
    if not choices:
        raise RuntimeError("empty response")
    message = choices[0].get("message") or {}
    content = message.get("content")
    if content is None:
        refusal = message.get("refusal") or "empty response"
        raise RuntimeError(str(refusal)[:400])
    if isinstance(content, list):
        text = "".join(part.get("text", "") for part in content if isinstance(part, dict))
    else:
        text = str(content)
    text = text.strip()
    if not text:
        raise RuntimeError("empty response")
    return text, usage_pair(payload.get("usage"))


def usage_pair(obj) -> tuple[int, int] | None:
    if not isinstance(obj, dict):
        return None
    if "prompt_tokens" in obj or "completion_tokens" in obj:
        return int(obj.get("prompt_tokens") or 0), int(obj.get("completion_tokens") or 0)
    if "promptTokenCount" in obj or "candidatesTokenCount" in obj:
        return int(obj.get("promptTokenCount") or 0), int(obj.get("candidatesTokenCount") or 0)
    if "input_tokens" in obj or "output_tokens" in obj:
        return int(obj.get("input_tokens") or 0), int(obj.get("output_tokens") or 0)
    return None


def parse_edits(raw: str) -> tuple[str, list[tuple[str, str]]]:
    text = raw.strip().lstrip("\ufeff")
    if text.startswith("```"):
        text = text.split("\n", 1)[-1]
        if text.rstrip().endswith("```"):
            text = text.rstrip()[:-3]
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise RuntimeError("model did not return JSON")
    try:
        payload = json.loads(text[start : end + 1])
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"could not parse JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise RuntimeError("model JSON was not an object")
    summary = str(payload.get("summary") or "").strip()
    files = payload.get("files") or []
    if isinstance(files, dict):
        files = [{"path": key, "content": value} for key, value in files.items()]
    if not isinstance(files, list):
        raise RuntimeError("files must be a list")
    if len(files) > MAX_EDITS:
        raise RuntimeError("model tried to change too many files")
    edits = []
    for item in files:
        if not isinstance(item, dict):
            raise RuntimeError("bad files entry")
        path = str(item.get("path") or item.get("file") or item.get("name") or "").strip()
        if not path:
            raise RuntimeError("file entry missing path")
        if "content" in item and item["content"] is not None:
            content = item["content"]
        elif "contents" in item and item["contents"] is not None:
            content = item["contents"]
        else:
            raise RuntimeError(f"missing content for {path}")
        if not isinstance(content, str):
            raise RuntimeError(f"content for {path} must be text")
        edits.append((path, content))
    return summary, edits


def chat_url(base: str) -> str:
    trimmed = base.strip().rstrip("/")
    if trimmed.endswith("/chat/completions"):
        return trimmed
    return trimmed + "/chat/completions"


def anthropic_url(base: str) -> str:
    trimmed = base.strip().rstrip("/")
    if trimmed.endswith("/v1/messages"):
        return trimmed
    if trimmed.endswith("/v1"):
        return trimmed + "/messages"
    return trimmed + "/v1/messages"


def short_error(body: str) -> str:
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        return body[:400]
    err = payload.get("error")
    if isinstance(err, dict) and err.get("message"):
        return str(err["message"])[:400]
    if payload.get("message"):
        return str(payload["message"])[:400]
    return body[:400]
