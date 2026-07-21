#!/usr/bin/env python3
"""
Pin playlists compatibility detector for YouTube Music (authoritative project-root storage build).

Phase 1 goals:
- Locate the newest YouTube Music APK automatically.
- Locate or install JADX automatically.
- Bootstrap a structural baseline from the currently working patch/version.
- Compare future APKs against that baseline without relying only on names.
- Propose additive required-symbol updates without guessing optional move methods.
- Modify nothing unless --apply is explicitly supplied.
- Build and perform a real patcher verification when --verify is supplied.
- Never commit, push, tag, or publish.

The detector intentionally fails closed when a result is ambiguous.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence


APP_PACKAGE = "com.google.android.apps.youtube.music"
DEFAULT_REPO = Path(r"C:\Projects\GitHub\Seobject's-Patches")
DEFAULT_STATE_ROOT = Path(
    r"C:\Projects\Patch Updaters\Pin Playlists"
)
DEFAULT_JADX_ROOT = Path(r"C:\Projects\JADX")
DEFAULT_DECOMPILE_ROOT = Path(
    r"C:\Projects\YouTube Music\Decompiled"
)
PIN_SOURCE_RELATIVE = Path(
    "extensions/music/src/main/java/app/morphe/extension/music/"
    "patches/pinplaylist/PinPlaylistPatch.java"
)

CLASS_ARRAY_ROLES = {
    "menu_item_helper": "MENU_ITEM_HELPER_CLASSES",
    "icon_enum": "ICON_ENUM_CLASSES",
    "text_helper": "TEXT_HELPER_CLASSES",
    "library_adapter": "LIBRARY_ADAPTER_CLASSES",
}

METHOD_ARRAY_ROLES = {
    "adapter_move_notify": {
        "array": "ADAPTER_MOVE_NOTIFY_METHODS",
        "arity": 2,
        "primitive_params": ["int", "int"],
    },
    "adapter_full_notify": {
        "array": "ADAPTER_FULL_NOTIFY_METHODS",
        "arity": 0,
        "primitive_params": [],
    },
}

ROLE_LABELS = {
    "menu_item_helper": "native menu-item helper class",
    "icon_enum": "native icon enum class",
    "text_helper": "native text-message helper class",
    "library_adapter": "Library adapter class",
    "adapter_move_notify": "adapter move-notify method",
    "adapter_full_notify": "adapter full-refresh method",
}

# Direct item-move notifications are an optimization. The runtime preserves
# the completed adapter permutation and falls back to a full adapter refresh
# when a release renames the move method. Never guess among structurally
# indistinguishable two-int RecyclerView notification methods.
OPTIONAL_ROLES = {"adapter_move_notify"}

CLASS_SCORE_THRESHOLD = 0.54
CLASS_MARGIN_THRESHOLD = 0.055
METHOD_SCORE_THRESHOLD = 0.49
METHOD_MARGIN_THRESHOLD = 0.065
KNOWN_SYMBOL_SCORE_THRESHOLD = 0.42
MAX_CLASS_CANDIDATES_REPORTED = 8
MAX_METHOD_CANDIDATES_REPORTED = 8

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for",
    "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "true", "false",
    "null", "record", "sealed", "permits", "non-sealed", "var", "yield",
}

STABLE_IDENTIFIERS = JAVA_KEYWORDS | {
    "Object", "String", "CharSequence", "Class", "Throwable", "Exception",
    "RuntimeException", "IllegalArgumentException", "IllegalStateException",
    "List", "ArrayList", "Map", "Set", "Collection", "Iterable", "Iterator",
    "HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet",
    "IdentityHashMap", "Collections", "Arrays", "Optional",
    "Context", "View", "ViewGroup", "TextView", "ImageView", "Drawable",
    "RecyclerView", "Handler", "Looper", "SystemClock", "Log",
    "ByteString", "Builder", "Enum", "Number", "Integer", "Long",
    "Boolean", "Float", "Double", "Short", "Byte", "Character",
    "Math", "System", "Thread", "Runnable", "Comparator",
    "getClass", "toString", "hashCode", "equals", "clone",
    "size", "isEmpty", "get", "set", "add", "remove", "clear",
    "put", "contains", "containsKey", "iterator", "hasNext", "next",
    "length", "valueOf", "ordinal", "name", "values",
}

KEYWORD_VECTOR_KEYS = (
    "if", "else", "for", "while", "switch", "case", "try", "catch",
    "throw", "return", "new", "instanceof", "synchronized", "static",
    "final", "public", "private", "protected", "void", "int", "long",
    "boolean", "class", "enum", "interface",
)

TOKEN_RE = re.compile(
    r'"(?:\\.|[^"\\])*"'          # string
    r"|'(?:\\.|[^'\\])*'"        # char
    r"|[A-Za-z_$][A-Za-z0-9_$]*" # identifier
    r"|0[xX][0-9A-Fa-f]+(?:[lL])?"
    r"|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[fFdDlL]?"
    r"|>>>|>>|<<|==|!=|<=|>=|&&|\|\||\+\+|--|->|::"
    r"|[{}()\[\];,.?:~!%^&*+\-/|<>=]"
)

STRING_RE = re.compile(r'"(?:\\.|[^"\\])*"')
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\r\n]*", re.DOTALL)
VERSION_RE = re.compile(r"(?<!\d)(\d+\.\d+\.\d+)(?!\d)")
SHORT_OBFUSCATED_NAME_RE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]{0,9}$")

METHOD_DECL_RE = re.compile(
    r"(?m)^[ \t]*"
    r"(?:(?:public|protected|private|static|final|synchronized|native|"
    r"abstract|strictfp|default)\s+)*"
    r"(?:<[^>{};]+>\s+)?"
    r"(?P<return>[A-Za-z_$][A-Za-z0-9_$.<>\[\]?, ]*)\s+"
    r"(?P<name>[A-Za-z_$][A-Za-z0-9_$]*)\s*"
    r"\((?P<params>[^(){};]*)\)\s*"
    r"(?:throws\s+[^{;]+)?\{"
)

CLASS_EXTENDS_RE = re.compile(
    r"\bclass\s+[A-Za-z_$][A-Za-z0-9_$]*(?:\s*<[^>{}]+>)?"
    r"\s+extends\s+([A-Za-z_$][A-Za-z0-9_$.]*)"
)


@dataclasses.dataclass(frozen=True)
class MethodBlock:
    name: str
    return_type: str
    params: str
    arity: int
    primitive_param_types: tuple[str, ...]
    source: str
    start: int
    end: int


@dataclasses.dataclass
class MatchCandidate:
    symbol: str
    score: float
    path: str | None = None
    details: dict[str, float] = dataclasses.field(default_factory=dict)


@dataclasses.dataclass
class RoleResolution:
    role: str
    selected: str | None
    status: str
    score: float
    margin: float
    candidates: list[MatchCandidate]
    reason: str

    @property
    def confident(self) -> bool:
        return self.status in {
            "existing",
            "discovered",
            "optional",
        }


class CompatibilityError(RuntimeError):
    pass


def utc_now_text() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def local_app_data() -> Path:
    raw = os.environ.get("LOCALAPPDATA")
    if raw:
        return Path(raw)
    return Path.home() / "AppData" / "Local"


def default_state_root() -> Path:
    return DEFAULT_STATE_ROOT


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def version_tuple(value: str) -> tuple[int, ...]:
    return tuple(int(piece) for piece in value.split("."))


def extract_version_from_name(path: Path) -> str | None:
    matches = VERSION_RE.findall(path.name)
    if not matches:
        return None
    return max(matches, key=version_tuple)


def run_process(
    command: Sequence[str | os.PathLike[str]],
    *,
    cwd: Path | None = None,
    capture: bool = False,
    check: bool = True,
    log_path: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    printable = " ".join(f'"{item}"' if " " in str(item) else str(item) for item in command)
    print(f"\n> {printable}")

    stdout_target: Any
    stderr_target: Any
    log_stream = None

    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_stream = log_path.open("w", encoding="utf-8", newline="\n")
        stdout_target = log_stream
        stderr_target = subprocess.STDOUT
    elif capture:
        stdout_target = subprocess.PIPE
        stderr_target = subprocess.STDOUT
    else:
        stdout_target = None
        stderr_target = None

    try:
        result = subprocess.run(
            [str(item) for item in command],
            cwd=str(cwd) if cwd else None,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=stdout_target,
            stderr=stderr_target,
            check=False,
        )
    finally:
        if log_stream is not None:
            log_stream.close()

    if check and result.returncode != 0:
        message = f"Command failed with exit code {result.returncode}: {printable}"
        if capture and result.stdout:
            message += "\n" + result.stdout[-6000:]
        if log_path:
            message += f"\nFull output: {log_path}"
        raise CompatibilityError(message)

    return result


def find_repo(explicit: Path | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(explicit)
    env_repo = os.environ.get("SEOBJECT_PATCHES_REPO")
    if env_repo:
        candidates.append(Path(env_repo))
    candidates.append(DEFAULT_REPO)

    current = Path.cwd()
    for parent in (current, *current.parents):
        candidates.append(parent)

    seen: set[str] = set()
    for candidate in candidates:
        candidate = candidate.expanduser()
        key = str(candidate).lower()
        if key in seen:
            continue
        seen.add(key)

        if (
            (candidate / ".git").exists()
            and (candidate / PIN_SOURCE_RELATIVE).is_file()
            and (candidate / "gradlew.bat").is_file()
        ):
            return candidate.resolve()

    raise CompatibilityError(
        "Could not locate the Seobject-patches repository. "
        "Pass --repo or set SEOBJECT_PATCHES_REPO."
    )


def find_newest_apk(explicit: Path | None) -> tuple[Path, str]:
    if explicit:
        apk = explicit.expanduser().resolve()
        if not apk.is_file():
            raise CompatibilityError(f"APK not found: {apk}")
        version = extract_version_from_name(apk)
        if version is None:
            raise CompatibilityError(
                "Could not infer a three-part version from the APK filename. "
                "Rename it to include x.xx.xx or pass an APKMirror-style filename."
            )
        return apk, version

    search_roots = [
        Path.home() / "Downloads",
        Path.home() / "Desktop",
    ]
    candidates: list[tuple[tuple[int, ...], float, Path, str]] = []

    for root in search_roots:
        if not root.exists():
            continue
        try:
            iterator = root.rglob("*.apk")
        except OSError:
            continue

        for apk in iterator:
            lowered = apk.name.lower()
            if (
                "youtube.music" not in lowered
                and APP_PACKAGE not in lowered
                and "youtube_music" not in lowered
            ):
                continue

            version = extract_version_from_name(apk)
            if version is None:
                continue

            try:
                modified = apk.stat().st_mtime
            except OSError:
                continue

            candidates.append((version_tuple(version), modified, apk, version))

    if not candidates:
        raise CompatibilityError(
            "No YouTube Music APK with an x.xx.xx version in its filename was "
            "found under Downloads or Desktop. Pass --apk explicitly."
        )

    candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
    _, _, apk, version = candidates[0]
    return apk.resolve(), version


def safe_extract_zip(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()

    with zipfile.ZipFile(archive) as zf:
        for member in zf.infolist():
            target = (destination / member.filename).resolve()
            try:
                target.relative_to(root)
            except ValueError as exc:
                raise CompatibilityError(
                    f"Unsafe path in JADX archive: {member.filename}"
                ) from exc
        zf.extractall(destination)


def download_latest_jadx(jadx_root: Path) -> Path:
    api_url = "https://api.github.com/repos/skylot/jadx/releases/latest"
    request = urllib.request.Request(
        api_url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "Seobject-Pin-Playlists-Compatibility",
        },
    )

    print("\nJADX was not found locally. Looking up the latest official release...")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            release = json.load(response)
    except Exception as exc:
        raise CompatibilityError(
            "JADX is required and could not be downloaded. Install JADX and "
            "put jadx.bat on PATH, or pass --jadx."
        ) from exc

    assets = release.get("assets") or []
    selected: dict[str, Any] | None = None

    for asset in assets:
        name = str(asset.get("name", ""))
        lowered = name.lower()
        if (
            lowered.startswith("jadx-")
            and lowered.endswith(".zip")
            and "gui" not in lowered
            and "with-jre" not in lowered
        ):
            selected = asset
            break

    if selected is None:
        for asset in assets:
            name = str(asset.get("name", ""))
            if name.lower().endswith(".zip") and "jadx" in name.lower():
                selected = asset
                break

    if selected is None:
        raise CompatibilityError("The latest JADX release has no usable ZIP asset.")

    tag = str(release.get("tag_name") or "latest")
    install_root = jadx_root / tag
    archive = install_root / str(selected["name"])
    extracted = install_root / "extracted"
    jadx_bat = extracted / "bin" / "jadx.bat"
    jadx_unix = extracted / "bin" / "jadx"

    if not archive.exists():
        install_root.mkdir(parents=True, exist_ok=True)
        download_url = str(selected["browser_download_url"])
        print(f"Downloading {download_url}")
        try:
            urllib.request.urlretrieve(download_url, archive)
        except Exception as exc:
            raise CompatibilityError(f"Failed to download JADX: {download_url}") from exc

    if not extracted.exists():
        safe_extract_zip(archive, extracted)

    if jadx_bat.is_file():
        return jadx_bat
    if jadx_unix.is_file():
        return jadx_unix

    nested = list(extracted.rglob("jadx.bat")) + list(extracted.rglob("jadx"))
    nested = [path for path in nested if path.is_file()]
    if nested:
        return min(nested, key=lambda path: len(path.parts))

    raise CompatibilityError("JADX downloaded, but its command-line executable was not found.")


def find_jadx(
    explicit: Path | None,
    jadx_root: Path,
) -> Path:
    if explicit is not None:
        candidate = explicit.expanduser().resolve()
        if not candidate.is_file():
            raise CompatibilityError(
                f"The explicit JADX executable was not found: {candidate}"
            )
        return candidate

    candidates: list[Path] = []

    if jadx_root.exists():
        candidates.extend(jadx_root.rglob("jadx.bat"))
        candidates.extend(jadx_root.rglob("jadx"))

    candidates = [
        candidate
        for candidate in candidates
        if candidate.is_file()
    ]

    if candidates:
        return max(
            candidates,
            key=lambda path: path.stat().st_mtime,
        ).resolve()

    return download_latest_jadx(jadx_root)



def decompile_apk(
    apk: Path,
    version: str,
    jadx: Path,
    state_root: Path,
    decompile_root: Path,
    *,
    force: bool,
) -> Path:
    apk_hash = sha256_file(apk)
    decompile_root.mkdir(parents=True, exist_ok=True)

    output = (
        decompile_root
        / f"YouTubeMusic_{version}"
    )
    output_marker = (
        output
        / ".pin-playlists-compat.json"
    )
    log_path = output / "jadx.log"

    index_root = (
        state_root
        / "decompiled-index"
        / f"{version}-{apk_hash[:12]}"
    )
    index_root.mkdir(parents=True, exist_ok=True)
    index_marker = index_root / "complete.json"

    def load_marker(path: Path) -> dict[str, Any] | None:
        if not path.is_file():
            return None
        try:
            value = json.loads(
                path.read_text(encoding="utf-8")
            )
        except (
            OSError,
            json.JSONDecodeError,
            TypeError,
        ):
            return None
        return value if isinstance(value, dict) else None

    def has_usable_sources(path: Path) -> bool:
        sources = path / "sources"
        return (
            sources.is_dir()
            and any(sources.rglob("*.java"))
        )

    def marker_matches(
        metadata: dict[str, Any] | None,
    ) -> bool:
        return bool(
            metadata
            and metadata.get("apk_sha256") == apk_hash
            and str(metadata.get("version")) == version
        )

    if not force and has_usable_sources(output):
        output_metadata = load_marker(output_marker)
        index_metadata = load_marker(index_marker)

        if (
            marker_matches(output_metadata)
            or marker_matches(index_metadata)
        ):
            metadata = {
                "created_at": (
                    (output_metadata or index_metadata or {})
                    .get("created_at", utc_now_text())
                ),
                "apk": str(apk),
                "apk_sha256": apk_hash,
                "version": version,
                "jadx": str(jadx),
                "output": str(output),
            }
            save_json(output_marker, metadata)
            save_json(index_marker, metadata)
            print(f"\nUsing cached JADX output: {output}")
            return output

        raise CompatibilityError(
            "The requested decompilation folder already contains Java "
            "sources but does not have a matching APK marker:\n"
            f"{output}\n"
            "Use --force-decompile only when you intentionally want to "
            "replace that folder."
        )

    if output.exists():
        last_error: OSError | None = None

        for attempt in range(12):
            try:
                shutil.rmtree(output)
                last_error = None
                break
            except OSError as error:
                last_error = error
                time.sleep(0.4 * (attempt + 1))

        if last_error is not None:
            raise CompatibilityError(
                "Could not clear the versioned decompilation folder. "
                "Close Explorer windows or programs using files beneath:\n"
                f"{output}\n"
                f"Windows error: {last_error}"
            )

    output.mkdir(parents=True, exist_ok=True)

    command = [
        str(jadx),
        "--no-res",
        "--comments-level",
        "none",
        "-d",
        str(output),
        str(apk),
    ]

    result = run_process(
        command,
        log_path=log_path,
        check=False,
    )

    produced_sources = has_usable_sources(output)

    if result.returncode != 0 and produced_sources:
        print(
            "\nJADX completed with recoverable decompilation errors "
            f"(exit code {result.returncode})."
        )
        print(
            "Usable Java sources were produced, so the compatibility "
            "scan will continue."
        )
        print(f"JADX log: {log_path}")

    if result.returncode != 0 and not produced_sources:
        # Retry without --comments-level only when the first invocation
        # produced no usable Java sources.
        fallback = [
            str(jadx),
            "--no-res",
            "-d",
            str(output),
            str(apk),
        ]
        fallback_result = run_process(
            fallback,
            log_path=log_path,
            check=False,
        )
        produced_sources = has_usable_sources(output)

        if (
            fallback_result.returncode != 0
            and produced_sources
        ):
            print(
                "\nJADX fallback completed with recoverable "
                f"decompilation errors (exit code "
                f"{fallback_result.returncode})."
            )
            print(
                "Usable Java sources were produced, so the "
                "compatibility scan will continue."
            )
            print(f"JADX log: {log_path}")

    if not produced_sources:
        raise CompatibilityError(
            f"JADX produced no Java sources. See {log_path}"
        )

    metadata = {
        "created_at": utc_now_text(),
        "apk": str(apk),
        "apk_sha256": apk_hash,
        "version": version,
        "jadx": str(jadx),
        "output": str(output),
    }
    save_json(output_marker, metadata)
    save_json(index_marker, metadata)
    return output



def parse_string_array(source: str, array_name: str) -> list[str]:
    pattern = re.compile(
        rf"private\s+static\s+final\s+String\[\]\s+"
        rf"{re.escape(array_name)}\s*=\s*\{{(?P<body>.*?)\}}\s*;",
        re.DOTALL,
    )
    match = pattern.search(source)
    if not match:
        raise CompatibilityError(f"Could not find source array {array_name}.")
    return re.findall(r'"([^"]+)"', match.group("body"))


def build_java_index(sources_root: Path) -> dict[str, list[Path]]:
    index: dict[str, list[Path]] = {}
    for path in sources_root.rglob("*.java"):
        index.setdefault(path.stem, []).append(path)
    for values in index.values():
        values.sort(key=lambda path: (len(path.parts), len(str(path))))
    return index


def choose_class_path(index: dict[str, list[Path]], symbol: str) -> Path | None:
    paths = index.get(symbol)
    if not paths:
        return None
    return paths[0]


def strip_comments(source: str) -> str:
    return COMMENT_RE.sub(" ", source)


def decode_java_string_literal(literal: str) -> str:
    if len(literal) < 2:
        return literal
    content = literal[1:-1]
    # Exact decoding is unnecessary; this handles common escapes while retaining
    # stable content for hashes.
    try:
        return bytes(content, "utf-8").decode("unicode_escape")
    except Exception:
        return content


def normalized_tokens(source: str) -> tuple[list[str], set[str], Counter[str]]:
    cleaned = strip_comments(source)
    tokens: list[str] = []
    strings: set[str] = set()
    keyword_counts: Counter[str] = Counter()

    for match in TOKEN_RE.finditer(cleaned):
        token = match.group(0)

        if token.startswith('"'):
            value = decode_java_string_literal(token)
            digest = hashlib.sha1(value.encode("utf-8", errors="replace")).hexdigest()[:12]
            tokens.append(f"STR_{digest}")
            if 2 <= len(value) <= 240:
                strings.add(digest)
            continue

        if token.startswith("'"):
            tokens.append("CHAR")
            continue

        if re.fullmatch(
            r"(?:0[xX][0-9A-Fa-f]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)[fFdDlL]?",
            token,
        ):
            tokens.append("NUM")
            continue

        if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", token):
            if token in STABLE_IDENTIFIERS:
                tokens.append(token)
                if token in KEYWORD_VECTOR_KEYS:
                    keyword_counts[token] += 1
            else:
                tokens.append("ID")
            continue

        tokens.append(token)

    return tokens, strings, keyword_counts


def fingerprint_source(source: str, shingle_size: int = 7) -> dict[str, Any]:
    tokens, strings, keyword_counts = normalized_tokens(source)
    shingles: set[str] = set()

    if len(tokens) >= shingle_size:
        for index in range(0, len(tokens) - shingle_size + 1):
            payload = "\x1f".join(tokens[index:index + shingle_size])
            shingles.add(hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16])
    elif tokens:
        payload = "\x1f".join(tokens)
        shingles.add(hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16])

    return {
        "token_count": len(tokens),
        "source_length": len(source),
        "line_count": source.count("\n") + 1,
        "shingles": sorted(shingles),
        "strings": sorted(strings),
        "keywords": {key: keyword_counts.get(key, 0) for key in KEYWORD_VECTOR_KEYS},
    }


def jaccard(left: Iterable[str], right: Iterable[str]) -> float:
    a = set(left)
    b = set(right)
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def cosine_dict(left: dict[str, int], right: dict[str, int]) -> float:
    keys = set(left) | set(right)
    dot = sum(left.get(key, 0) * right.get(key, 0) for key in keys)
    norm_left = math.sqrt(sum(value * value for value in left.values()))
    norm_right = math.sqrt(sum(value * value for value in right.values()))
    if norm_left == 0 and norm_right == 0:
        return 1.0
    if norm_left == 0 or norm_right == 0:
        return 0.0
    return dot / (norm_left * norm_right)


def ratio_similarity(left: int, right: int) -> float:
    if left <= 0 and right <= 0:
        return 1.0
    if left <= 0 or right <= 0:
        return 0.0
    ratio = min(left, right) / max(left, right)
    return ratio


def fingerprint_similarity(
    baseline: dict[str, Any],
    candidate: dict[str, Any],
) -> tuple[float, dict[str, float]]:
    shingle_score = jaccard(baseline["shingles"], candidate["shingles"])
    string_score = jaccard(baseline["strings"], candidate["strings"])
    keyword_score = cosine_dict(baseline["keywords"], candidate["keywords"])
    token_score = ratio_similarity(
        int(baseline["token_count"]),
        int(candidate["token_count"]),
    )
    line_score = ratio_similarity(
        int(baseline["line_count"]),
        int(candidate["line_count"]),
    )
    size_score = (token_score + line_score) / 2.0

    total = (
        shingle_score * 0.66
        + string_score * 0.14
        + keyword_score * 0.14
        + size_score * 0.06
    )
    return total, {
        "shingles": shingle_score,
        "strings": string_score,
        "keywords": keyword_score,
        "size": size_score,
    }


def split_params(params: str) -> list[str]:
    params = params.strip()
    if not params:
        return []

    values: list[str] = []
    start = 0
    depth_angle = 0
    depth_square = 0

    for index, character in enumerate(params):
        if character == "<":
            depth_angle += 1
        elif character == ">":
            depth_angle = max(0, depth_angle - 1)
        elif character == "[":
            depth_square += 1
        elif character == "]":
            depth_square = max(0, depth_square - 1)
        elif character == "," and depth_angle == 0 and depth_square == 0:
            values.append(params[start:index].strip())
            start = index + 1

    values.append(params[start:].strip())
    return [value for value in values if value]


def primitive_type_from_param(param: str) -> str:
    cleaned = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", param)
    tokens = cleaned.replace("...", "[]").split()
    for primitive in ("int", "long", "boolean", "byte", "short", "char", "float", "double"):
        if primitive in tokens:
            return primitive
    return "reference"


def find_matching_brace(source: str, opening: int) -> int | None:
    depth = 0
    index = opening
    state = "code"

    while index < len(source):
        character = source[index]
        next_character = source[index + 1] if index + 1 < len(source) else ""

        if state == "code":
            if character == "/" and next_character == "/":
                state = "line_comment"
                index += 2
                continue
            if character == "/" and next_character == "*":
                state = "block_comment"
                index += 2
                continue
            if character == '"':
                state = "string"
                index += 1
                continue
            if character == "'":
                state = "char"
                index += 1
                continue
            if character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    return index
        elif state == "line_comment":
            if character in "\r\n":
                state = "code"
        elif state == "block_comment":
            if character == "*" and next_character == "/":
                state = "code"
                index += 2
                continue
        elif state in {"string", "char"}:
            if character == "\\":
                index += 2
                continue
            if (state == "string" and character == '"') or (
                state == "char" and character == "'"
            ):
                state = "code"

        index += 1

    return None


def extract_methods(source: str) -> list[MethodBlock]:
    methods: list[MethodBlock] = []

    for match in METHOD_DECL_RE.finditer(source):
        name = match.group("name")
        if name in {"if", "for", "while", "switch", "catch", "synchronized"}:
            continue

        opening = source.find("{", match.start(), match.end() + 1)
        if opening < 0:
            continue
        closing = find_matching_brace(source, opening)
        if closing is None:
            continue

        params_text = match.group("params")
        params = split_params(params_text)
        primitive_params = tuple(primitive_type_from_param(param) for param in params)
        methods.append(
            MethodBlock(
                name=name,
                return_type=" ".join(match.group("return").split()),
                params=params_text,
                arity=len(params),
                primitive_param_types=primitive_params,
                source=source[match.start():closing + 1],
                start=match.start(),
                end=closing + 1,
            )
        )

    return methods


def select_method_by_name(
    methods: Sequence[MethodBlock],
    name: str,
    expected_arity: int,
    primitive_params: Sequence[str],
) -> MethodBlock | None:
    matches = [
        method
        for method in methods
        if method.name == name and method.arity == expected_arity
    ]
    if primitive_params:
        exact = [
            method
            for method in matches
            if list(method.primitive_param_types) == list(primitive_params)
        ]
        if exact:
            matches = exact
    if not matches:
        return None
    return max(matches, key=lambda method: len(method.source))



def extract_parent_symbol(source: str) -> str | None:
    match = CLASS_EXTENDS_RE.search(strip_comments(source))
    if not match:
        return None
    return match.group(1).split(".")[-1]


def collect_class_hierarchy(
    index: dict[str, list[Path]],
    start_symbol: str,
    *,
    max_depth: int = 12,
) -> list[tuple[str, Path, str]]:
    hierarchy: list[tuple[str, Path, str]] = []
    seen: set[str] = set()
    symbol: str | None = start_symbol

    while symbol and symbol not in seen and len(hierarchy) < max_depth:
        seen.add(symbol)
        path = choose_class_path(index, symbol)
        if path is None:
            break
        source = path.read_text(encoding="utf-8", errors="replace")
        hierarchy.append((symbol, path, source))
        symbol = extract_parent_symbol(source)

    return hierarchy

def class_candidate_paths(
    sources_root: Path,
    baseline_fp: dict[str, Any],
) -> Iterator[Path]:
    baseline_tokens = int(baseline_fp["token_count"])
    min_tokens = max(8, int(baseline_tokens * 0.32))
    max_tokens = max(min_tokens + 1, int(baseline_tokens * 3.10))

    for path in sources_root.rglob("*.java"):
        stem = path.stem
        if "$" in stem or not SHORT_OBFUSCATED_NAME_RE.fullmatch(stem):
            continue
        if stem in {"R", "BuildConfig", "Manifest"}:
            continue

        try:
            size = path.stat().st_size
        except OSError:
            continue

        # A rough byte prefilter avoids tokenizing obviously unrelated files.
        baseline_length = int(baseline_fp["source_length"])
        if baseline_length > 0:
            if size < baseline_length * 0.22 or size > baseline_length * 4.5:
                continue

        # token count is checked after parsing.
        yield path


def fingerprint_path(path: Path) -> tuple[Path, dict[str, Any]] | None:
    try:
        source = path.read_text(encoding="utf-8", errors="replace")
        return path, fingerprint_source(source)
    except OSError:
        return None


def rank_class_candidates(
    sources_root: Path,
    baseline_fp: dict[str, Any],
    *,
    workers: int,
) -> list[MatchCandidate]:
    paths = list(class_candidate_paths(sources_root, baseline_fp))
    ranked: list[MatchCandidate] = []
    baseline_tokens = int(baseline_fp["token_count"])
    min_tokens = max(8, int(baseline_tokens * 0.32))
    max_tokens = max(min_tokens + 1, int(baseline_tokens * 3.10))

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        for item in executor.map(fingerprint_path, paths, chunksize=20):
            if item is None:
                continue
            path, candidate_fp = item
            token_count = int(candidate_fp["token_count"])
            if token_count < min_tokens or token_count > max_tokens:
                continue

            score, details = fingerprint_similarity(baseline_fp, candidate_fp)
            ranked.append(
                MatchCandidate(
                    symbol=path.stem,
                    score=score,
                    path=str(path),
                    details=details,
                )
            )

    ranked.sort(key=lambda candidate: candidate.score, reverse=True)
    return ranked



def split_call_arguments(arguments: str) -> list[str]:
    values: list[str] = []
    start = 0
    round_depth = 0
    square_depth = 0
    angle_depth = 0
    state = "code"
    index = 0

    while index < len(arguments):
        character = arguments[index]

        if state == "code":
            if character == '"':
                state = "string"
            elif character == "'":
                state = "char"
            elif character == "(":
                round_depth += 1
            elif character == ")":
                round_depth = max(0, round_depth - 1)
            elif character == "[":
                square_depth += 1
            elif character == "]":
                square_depth = max(0, square_depth - 1)
            elif character == "<":
                angle_depth += 1
            elif character == ">":
                angle_depth = max(0, angle_depth - 1)
            elif (
                character == ","
                and round_depth == 0
                and square_depth == 0
                and angle_depth == 0
            ):
                values.append(arguments[start:index].strip())
                start = index + 1
        elif state in {"string", "char"}:
            if character == "\\":
                index += 1
            elif (
                state == "string"
                and character == '"'
            ) or (
                state == "char"
                and character == "'"
            ):
                state = "code"

        index += 1

    final = arguments[start:].strip()
    if final:
        values.append(final)
    return values


def find_named_calls(source: str, method_name: str) -> list[list[str]]:
    cleaned = strip_comments(source)
    pattern = re.compile(
        rf"(?<![A-Za-z0-9_$]){re.escape(method_name)}\s*\("
    )
    calls: list[list[str]] = []

    for match in pattern.finditer(cleaned):
        opening = cleaned.find("(", match.start(), match.end() + 1)
        if opening < 0:
            continue

        depth = 0
        state = "code"
        index = opening
        closing = None

        while index < len(cleaned):
            character = cleaned[index]

            if state == "code":
                if character == '"':
                    state = "string"
                elif character == "'":
                    state = "char"
                elif character == "(":
                    depth += 1
                elif character == ")":
                    depth -= 1
                    if depth == 0:
                        closing = index
                        break
            elif state in {"string", "char"}:
                if character == "\\":
                    index += 1
                elif (
                    state == "string"
                    and character == '"'
                ) or (
                    state == "char"
                    and character == "'"
                ):
                    state = "code"

            index += 1

        if closing is None:
            continue

        calls.append(
            split_call_arguments(cleaned[opening + 1:closing])
        )

    return calls


def normalized_integer_literal(value: str) -> str:
    return re.sub(r"[lL]$", "", value.strip())


def method_context_features(
    owner_source: str,
    candidate: MethodBlock,
) -> dict[str, Any]:
    methods = extract_methods(owner_source)
    one_item_wrappers = 0
    incoming_calls = 0
    incoming_arity_counts: Counter[int] = Counter()
    same_name_arities: Counter[int] = Counter()

    for method in methods:
        if method.name == candidate.name:
            same_name_arities[method.arity] += 1

        if (
            method.start == candidate.start
            and method.end == candidate.end
        ):
            continue

        calls = find_named_calls(method.source, candidate.name)

        for arguments in calls:
            incoming_calls += 1
            incoming_arity_counts[len(arguments)] += 1

            if (
                method.arity == 1
                and method.primitive_param_types == ("int",)
                and len(arguments) == 2
            ):
                literals = [
                    normalized_integer_literal(argument)
                    for argument in arguments
                ]
                if literals.count("1") == 1:
                    one_item_wrappers += 1

    same_arity_methods = [
        method
        for method in methods
        if (
            method.arity == candidate.arity
            and method.primitive_param_types
            == candidate.primitive_param_types
        )
    ]
    declaration_index = next(
        (
            index
            for index, method in enumerate(same_arity_methods)
            if (
                method.start == candidate.start
                and method.end == candidate.end
            )
        ),
        -1,
    )

    has_payload_overload = any(
        method.name == candidate.name
        and method.arity == 3
        and tuple(method.primitive_param_types[:2])
        == ("int", "int")
        for method in methods
    )

    return {
        "one_item_wrappers": one_item_wrappers,
        "incoming_calls": incoming_calls,
        "incoming_arity_counts": {
            str(key): value
            for key, value in sorted(incoming_arity_counts.items())
        },
        "same_name_arities": {
            str(key): value
            for key, value in sorted(same_name_arities.items())
        },
        "has_payload_overload": has_payload_overload,
        "declaration_index_same_shape": declaration_index,
        "same_shape_count": len(same_arity_methods),
    }


def counter_dict_similarity(
    left: dict[str, int],
    right: dict[str, int],
) -> float:
    keys = set(left) | set(right)
    if not keys:
        return 1.0

    numerator = sum(
        min(int(left.get(key, 0)), int(right.get(key, 0)))
        for key in keys
    )
    denominator = sum(
        max(int(left.get(key, 0)), int(right.get(key, 0)))
        for key in keys
    )
    return 1.0 if denominator == 0 else numerator / denominator


def method_context_similarity(
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    *,
    role: str,
) -> float:
    wrapper_score = ratio_similarity(
        int(baseline.get("one_item_wrappers", 0)) + 1,
        int(candidate.get("one_item_wrappers", 0)) + 1,
    )
    payload_score = (
        1.0
        if bool(baseline.get("has_payload_overload", False))
        == bool(candidate.get("has_payload_overload", False))
        else 0.0
    )
    incoming_arity_score = counter_dict_similarity(
        {
            str(key): int(value)
            for key, value in dict(
                baseline.get("incoming_arity_counts", {})
            ).items()
        },
        {
            str(key): int(value)
            for key, value in dict(
                candidate.get("incoming_arity_counts", {})
            ).items()
        },
    )
    same_name_score = counter_dict_similarity(
        {
            str(key): int(value)
            for key, value in dict(
                baseline.get("same_name_arities", {})
            ).items()
        },
        {
            str(key): int(value)
            for key, value in dict(
                candidate.get("same_name_arities", {})
            ).items()
        },
    )

    if role == "adapter_move_notify":
        baseline_is_single_move = (
            int(baseline.get("one_item_wrappers", 0)) == 0
            and not bool(
                baseline.get("has_payload_overload", False)
            )
        )
        candidate_is_single_move = (
            int(candidate.get("one_item_wrappers", 0)) == 0
            and not bool(
                candidate.get("has_payload_overload", False)
            )
        )
        semantic_score = (
            1.0
            if baseline_is_single_move == candidate_is_single_move
            else 0.0
        )
        return (
            semantic_score * 0.48
            + wrapper_score * 0.27
            + payload_score * 0.15
            + incoming_arity_score * 0.06
            + same_name_score * 0.04
        )

    return (
        wrapper_score * 0.30
        + payload_score * 0.20
        + incoming_arity_score * 0.25
        + same_name_score * 0.25
    )


def enrich_baseline_method_context(
    baseline: dict[str, Any],
    *,
    hierarchy: Sequence[tuple[str, Path, str]],
    state_root: Path,
    target_version: str,
) -> dict[str, Any]:
    needs_update = False

    for role, spec in METHOD_ARRAY_ROLES.items():
        role_data = baseline["roles"][role]

        if (
            "owner_fingerprint" in role_data
            and "owner_depth" in role_data
            and "context" in role_data
        ):
            continue

        if str(baseline.get("version")) != str(target_version):
            raise CompatibilityError(
                "The baseline predates contextual method fingerprints. "
                "Run this detector once against the baseline APK version "
                f"{baseline.get('version')} before scanning a newer APK."
            )

        expected_owner = role_data.get("owner_symbol")
        selected_owner_symbol = None
        selected_owner_source = None
        selected_method = None
        selected_depth = None

        for depth, (
            owner_symbol,
            _owner_path,
            owner_source,
        ) in enumerate(hierarchy):
            if (
                expected_owner is not None
                and owner_symbol != expected_owner
            ):
                continue

            selected = select_method_by_name(
                extract_methods(owner_source),
                str(role_data["symbol"]),
                int(spec["arity"]),
                list(spec["primitive_params"]),
            )
            if selected is None:
                continue

            selected_owner_symbol = owner_symbol
            selected_owner_source = owner_source
            selected_method = selected
            selected_depth = depth
            break

        if (
            selected_owner_symbol is None
            or selected_owner_source is None
            or selected_method is None
            or selected_depth is None
        ):
            raise CompatibilityError(
                "Could not enrich the baseline context for "
                f"{ROLE_LABELS[role]}."
            )

        role_data["owner_symbol"] = selected_owner_symbol
        role_data["owner_depth"] = selected_depth
        role_data["owner_fingerprint"] = fingerprint_source(
            selected_owner_source
        )
        role_data["context"] = method_context_features(
            selected_owner_source,
            selected_method,
        )
        needs_update = True

    if needs_update:
        baseline["context_enriched_at"] = utc_now_text()
        save_json(baseline_path(state_root), baseline)
        print(
            "\nBaseline upgraded with superclass and method "
            "call-graph fingerprints."
        )

    return baseline


def rank_methods_in_hierarchy(
    hierarchy: Sequence[tuple[str, Path, str]],
    baseline_role: dict[str, Any],
    *,
    role: str,
    expected_arity: int,
    primitive_params: Sequence[str],
) -> list[MatchCandidate]:
    ranked: list[MatchCandidate] = []
    baseline_fp = baseline_role["fingerprint"]
    baseline_owner_fp = baseline_role.get("owner_fingerprint")
    baseline_context = baseline_role.get("context", {})
    baseline_owner_depth = int(
        baseline_role.get("owner_depth", -1)
    )

    for owner_depth, (
        owner_symbol,
        owner_path,
        owner_source,
    ) in enumerate(hierarchy):
        owner_score = 0.0

        if baseline_owner_fp:
            owner_fp = fingerprint_source(owner_source)
            owner_score, _owner_details = fingerprint_similarity(
                baseline_owner_fp,
                owner_fp,
            )

        depth_score = (
            1.0
            if owner_depth == baseline_owner_depth
            else 1.0 / (
                1.0
                + abs(owner_depth - baseline_owner_depth)
            )
        )

        for method in extract_methods(owner_source):
            if method.arity != expected_arity:
                continue
            if (
                primitive_params
                and list(method.primitive_param_types)
                != list(primitive_params)
            ):
                continue

            candidate_fp = fingerprint_source(
                method.source,
                shingle_size=5,
            )
            body_score, body_details = fingerprint_similarity(
                baseline_fp,
                candidate_fp,
            )
            context = method_context_features(
                owner_source,
                method,
            )
            context_score = method_context_similarity(
                baseline_context,
                context,
                role=role,
            )

            if role == "adapter_move_notify":
                score = (
                    body_score * 0.48
                    + owner_score * 0.24
                    + context_score * 0.24
                    + depth_score * 0.04
                )
            else:
                score = (
                    body_score * 0.68
                    + owner_score * 0.18
                    + context_score * 0.10
                    + depth_score * 0.04
                )

            ranked.append(
                MatchCandidate(
                    symbol=method.name,
                    score=score,
                    path=str(owner_path),
                    details={
                        **body_details,
                        "body_score": body_score,
                        "owner_score": owner_score,
                        "context_score": context_score,
                        "owner_depth": float(owner_depth),
                        "depth_score": depth_score,
                        "one_item_wrappers": float(
                            context["one_item_wrappers"]
                        ),
                        "incoming_calls": float(
                            context["incoming_calls"]
                        ),
                        "has_payload_overload": (
                            1.0
                            if context["has_payload_overload"]
                            else 0.0
                        ),
                        "same_shape_count": float(
                            context["same_shape_count"]
                        ),
                    },
                )
            )

    ranked.sort(
        key=lambda candidate: candidate.score,
        reverse=True,
    )
    return ranked

def load_patch_arrays(source_path: Path) -> dict[str, list[str]]:
    source = source_path.read_text(encoding="utf-8", errors="strict")
    arrays: dict[str, list[str]] = {}
    for array_name in CLASS_ARRAY_ROLES.values():
        arrays[array_name] = parse_string_array(source, array_name)
    for spec in METHOD_ARRAY_ROLES.values():
        array_name = str(spec["array"])
        arrays[array_name] = parse_string_array(source, array_name)
    return arrays


def baseline_path(state_root: Path) -> Path:
    return state_root / "baseline.json"


def save_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    temporary.replace(path)


def load_baseline(state_root: Path) -> dict[str, Any] | None:
    path = baseline_path(state_root)
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CompatibilityError(f"Baseline is unreadable: {path}") from exc
    if payload.get("schema") != 1:
        raise CompatibilityError(
            f"Unsupported baseline schema in {path}: {payload.get('schema')}"
        )
    return payload


def bootstrap_baseline(
    *,
    repo: Path,
    apk: Path,
    version: str,
    decompiled: Path,
    state_root: Path,
) -> dict[str, Any]:
    source_path = repo / PIN_SOURCE_RELATIVE
    arrays = load_patch_arrays(source_path)
    sources_root = decompiled / "sources"
    index = build_java_index(sources_root)

    roles: dict[str, Any] = {}

    for role, array_name in CLASS_ARRAY_ROLES.items():
        symbols = arrays[array_name]
        selected_path = None
        selected_symbol = None

        for symbol in symbols:
            path = choose_class_path(index, symbol)
            if path is not None:
                selected_symbol = symbol
                selected_path = path
                break

        if selected_path is None or selected_symbol is None:
            raise CompatibilityError(
                f"Cannot bootstrap {ROLE_LABELS[role]}: none of {symbols} "
                f"exists in the decompiled {version} APK."
            )

        source = selected_path.read_text(encoding="utf-8", errors="replace")
        roles[role] = {
            "symbol": selected_symbol,
            "array": array_name,
            "relative_path": str(selected_path.relative_to(sources_root)),
            "fingerprint": fingerprint_source(source),
        }

    adapter_symbol = roles["library_adapter"]["symbol"]
    adapter_path = choose_class_path(index, adapter_symbol)
    if adapter_path is None:
        raise CompatibilityError("Bootstrapped Library adapter source disappeared.")
    adapter_hierarchy = collect_class_hierarchy(index, adapter_symbol)
    if not adapter_hierarchy:
        raise CompatibilityError("Could not inspect the Library adapter hierarchy.")

    for role, spec in METHOD_ARRAY_ROLES.items():
        array_name = str(spec["array"])
        symbols = arrays[array_name]
        selected: MethodBlock | None = None
        selected_symbol: str | None = None
        selected_owner: str | None = None

        for symbol in symbols:
            for owner_symbol, _owner_path, owner_source in adapter_hierarchy:
                method = select_method_by_name(
                    extract_methods(owner_source),
                    symbol,
                    int(spec["arity"]),
                    list(spec["primitive_params"]),
                )
                if method is not None:
                    selected = method
                    selected_symbol = symbol
                    selected_owner = owner_symbol
                    break
            if selected is not None:
                break

        if selected is None or selected_symbol is None:
            available = sorted(
                {
                    method.name
                    for _owner_symbol, _owner_path, owner_source in adapter_hierarchy
                    for method in extract_methods(owner_source)
                    if method.arity == int(spec["arity"])
                }
            )
            raise CompatibilityError(
                f"Cannot bootstrap {ROLE_LABELS[role]}; none of {symbols} "
                f"matches the expected method shape in the adapter hierarchy. "
                f"Available same-arity methods include: {available[:40]}"
            )

        owner_depth = next(
            index
            for index, (
                owner_symbol,
                _owner_path,
                _owner_source,
            ) in enumerate(adapter_hierarchy)
            if owner_symbol == selected_owner
        )
        owner_source = adapter_hierarchy[owner_depth][2]

        roles[role] = {
            "symbol": selected_symbol,
            "array": array_name,
            "adapter_symbol": adapter_symbol,
            "owner_symbol": selected_owner,
            "owner_depth": owner_depth,
            "owner_fingerprint": fingerprint_source(
                owner_source
            ),
            "context": method_context_features(
                owner_source,
                selected,
            ),
            "arity": int(spec["arity"]),
            "primitive_params": list(spec["primitive_params"]),
            "fingerprint": fingerprint_source(
                selected.source,
                shingle_size=5,
            ),
        }

    payload = {
        "schema": 1,
        "created_at": utc_now_text(),
        "version": version,
        "apk": str(apk),
        "apk_sha256": sha256_file(apk),
        "repo": str(repo),
        "pin_source": str(PIN_SOURCE_RELATIVE).replace("\\", "/"),
        "roles": roles,
    }
    save_json(baseline_path(state_root), payload)
    return payload


def resolve_class_role(
    *,
    role: str,
    baseline_role: dict[str, Any],
    known_symbols: list[str],
    sources_root: Path,
    index: dict[str, list[Path]],
    workers: int,
) -> RoleResolution:
    baseline_fp = baseline_role["fingerprint"]
    existing_ranked: list[MatchCandidate] = []

    for symbol in known_symbols:
        path = choose_class_path(index, symbol)
        if path is None:
            continue
        try:
            candidate_fp = fingerprint_source(
                path.read_text(encoding="utf-8", errors="replace")
            )
        except OSError:
            continue

        score, details = fingerprint_similarity(baseline_fp, candidate_fp)
        existing_ranked.append(
            MatchCandidate(
                symbol=symbol,
                score=score,
                path=str(path),
                details=details,
            )
        )

    existing_ranked.sort(key=lambda candidate: candidate.score, reverse=True)
    if (
        existing_ranked
        and existing_ranked[0].score >= KNOWN_SYMBOL_SCORE_THRESHOLD
    ):
        top = existing_ranked[0]
        second_score = existing_ranked[1].score if len(existing_ranked) > 1 else 0.0
        return RoleResolution(
            role=role,
            selected=top.symbol,
            status="existing",
            score=top.score,
            margin=top.score - second_score,
            candidates=existing_ranked[:MAX_CLASS_CANDIDATES_REPORTED],
            reason="A symbol already present in the additive array matches the baseline.",
        )

    ranked = rank_class_candidates(
        sources_root,
        baseline_fp,
        workers=workers,
    )
    top = ranked[0] if ranked else None
    second_score = ranked[1].score if len(ranked) > 1 else 0.0
    margin = top.score - second_score if top else 0.0

    if (
        top is not None
        and top.score >= CLASS_SCORE_THRESHOLD
        and margin >= CLASS_MARGIN_THRESHOLD
    ):
        return RoleResolution(
            role=role,
            selected=top.symbol,
            status="discovered",
            score=top.score,
            margin=margin,
            candidates=ranked[:MAX_CLASS_CANDIDATES_REPORTED],
            reason="Structural class match exceeded the score and uniqueness thresholds.",
        )

    return RoleResolution(
        role=role,
        selected=top.symbol if top else None,
        status="ambiguous",
        score=top.score if top else 0.0,
        margin=margin,
        candidates=ranked[:MAX_CLASS_CANDIDATES_REPORTED],
        reason=(
            "No unique structural class match exceeded the fail-safe thresholds. "
            "Source will not be modified."
        ),
    )


def resolve_method_role(
    *,
    role: str,
    baseline_role: dict[str, Any],
    known_symbols: list[str],
    hierarchy: Sequence[tuple[str, Path, str]],
) -> RoleResolution:
    spec = METHOD_ARRAY_ROLES[role]
    baseline_fp = baseline_role["fingerprint"]
    existing_ranked: list[MatchCandidate] = []

    for symbol in known_symbols:
        for _owner_symbol, owner_path, owner_source in hierarchy:
            method = select_method_by_name(
                extract_methods(owner_source),
                symbol,
                int(spec["arity"]),
                list(spec["primitive_params"]),
            )
            if method is None:
                continue
            candidate_fp = fingerprint_source(method.source, shingle_size=5)
            score, details = fingerprint_similarity(baseline_fp, candidate_fp)
            existing_ranked.append(
                MatchCandidate(
                    symbol=symbol,
                    score=score,
                    path=str(owner_path),
                    details=details,
                )
            )

    existing_ranked.sort(key=lambda candidate: candidate.score, reverse=True)
    if (
        existing_ranked
        and existing_ranked[0].score >= KNOWN_SYMBOL_SCORE_THRESHOLD
    ):
        top = existing_ranked[0]
        second_score = existing_ranked[1].score if len(existing_ranked) > 1 else 0.0
        return RoleResolution(
            role=role,
            selected=top.symbol,
            status="existing",
            score=top.score,
            margin=top.score - second_score,
            candidates=existing_ranked[:MAX_METHOD_CANDIDATES_REPORTED],
            reason=(
                "A method already present in the additive array matches the "
                "baseline somewhere in the Library adapter hierarchy."
            ),
        )

    ranked = rank_methods_in_hierarchy(
        hierarchy,
        baseline_role,
        role=role,
        expected_arity=int(spec["arity"]),
        primitive_params=list(spec["primitive_params"]),
    )
    top = ranked[0] if ranked else None
    second_score = ranked[1].score if len(ranked) > 1 else 0.0
    margin = top.score - second_score if top else 0.0

    if (
        top is not None
        and top.score >= METHOD_SCORE_THRESHOLD
        and margin >= METHOD_MARGIN_THRESHOLD
    ):
        return RoleResolution(
            role=role,
            selected=top.symbol,
            status="discovered",
            score=top.score,
            margin=margin,
            candidates=ranked[:MAX_METHOD_CANDIDATES_REPORTED],
            reason=(
                "A structural method match in the Library adapter hierarchy "
                "exceeded the score and uniqueness thresholds."
            ),
        )

    return RoleResolution(
        role=role,
        selected=top.symbol if top else None,
        status="ambiguous",
        score=top.score if top else 0.0,
        margin=margin,
        candidates=ranked[:MAX_METHOD_CANDIDATES_REPORTED],
        reason=(
            "No unique structural method match in the adapter hierarchy "
            "exceeded the fail-safe thresholds. Source will not be modified."
        ),
    )

def scan_version(
    *,
    repo: Path,
    apk: Path,
    version: str,
    decompiled: Path,
    baseline: dict[str, Any],
    state_root: Path,
    workers: int,
    simulate_rename: bool = False,
) -> list[RoleResolution]:
    source_path = repo / PIN_SOURCE_RELATIVE
    arrays = load_patch_arrays(source_path)
    sources_root = decompiled / "sources"
    index = build_java_index(sources_root)
    resolutions: list[RoleResolution] = []

    for role, array_name in CLASS_ARRAY_ROLES.items():
        print(f"\nScanning {ROLE_LABELS[role]}...")
        resolution = resolve_class_role(
            role=role,
            baseline_role=baseline["roles"][role],
            known_symbols=(
                []
                if simulate_rename
                else arrays[array_name]
            ),
            sources_root=sources_root,
            index=index,
            workers=workers,
        )
        resolutions.append(resolution)
        print(
            f"  {resolution.status}: {resolution.selected} "
            f"(score={resolution.score:.3f}, margin={resolution.margin:.3f})"
        )

    adapter_resolution = next(
        resolution for resolution in resolutions if resolution.role == "library_adapter"
    )

    if not adapter_resolution.confident or not adapter_resolution.selected:
        for role in METHOD_ARRAY_ROLES:
            resolutions.append(
                RoleResolution(
                    role=role,
                    selected=None,
                    status="blocked",
                    score=0.0,
                    margin=0.0,
                    candidates=[],
                    reason="Library adapter resolution was ambiguous.",
                )
            )
        return resolutions

    adapter_path = choose_class_path(index, adapter_resolution.selected)
    if adapter_path is None:
        raise CompatibilityError(
            f"Resolved adapter class has no source file: {adapter_resolution.selected}"
        )
    adapter_hierarchy = collect_class_hierarchy(
        index,
        adapter_resolution.selected,
    )
    if not adapter_hierarchy:
        raise CompatibilityError(
            f"Could not inspect hierarchy for adapter: {adapter_resolution.selected}"
        )

    baseline = enrich_baseline_method_context(
        baseline,
        hierarchy=adapter_hierarchy,
        state_root=state_root,
        target_version=version,
    )

    method_resolutions: list[RoleResolution] = []

    for role, spec in METHOD_ARRAY_ROLES.items():
        array_name = str(spec["array"])
        resolution = resolve_method_role(
            role=role,
            baseline_role=baseline["roles"][role],
            known_symbols=(
                []
                if simulate_rename
                else arrays[array_name]
            ),
            hierarchy=adapter_hierarchy,
        )
        method_resolutions.append(resolution)

    full_refresh_resolution = next(
        resolution
        for resolution in method_resolutions
        if resolution.role == "adapter_full_notify"
    )
    move_resolution_index = next(
        index
        for index, resolution in enumerate(method_resolutions)
        if resolution.role == "adapter_move_notify"
    )
    move_resolution = method_resolutions[move_resolution_index]

    if (
        not move_resolution.confident
        and full_refresh_resolution.confident
    ):
        method_resolutions[move_resolution_index] = RoleResolution(
            role=move_resolution.role,
            selected=None,
            status="optional",
            score=move_resolution.score,
            margin=move_resolution.margin,
            candidates=move_resolution.candidates,
            reason=(
                "Direct move notification is an optional optimization. "
                "The candidates are structurally indistinguishable, so no "
                "method name will be guessed or added. The runtime preserves "
                "the completed position map and uses the confidently resolved "
                "full-refresh notifier instead."
            ),
        )

    for resolution in method_resolutions:
        print(f"\nScanning {ROLE_LABELS[resolution.role]}...")
        print(
            f"  {resolution.status}: {resolution.selected} "
            f"(score={resolution.score:.3f}, "
            f"margin={resolution.margin:.3f})"
        )
        if resolution.status == "optional":
            print(
                "  Full-refresh fallback accepted; "
                "the move-method array will remain unchanged."
            )

    resolutions.extend(method_resolutions)
    return resolutions


def candidate_to_json(candidate: MatchCandidate, sources_root: Path | None) -> dict[str, Any]:
    path = candidate.path
    if path and sources_root:
        try:
            path = str(Path(path).relative_to(sources_root))
        except ValueError:
            pass
    return {
        "symbol": candidate.symbol,
        "score": round(candidate.score, 6),
        "path": path,
        "details": {key: round(value, 6) for key, value in candidate.details.items()},
    }


def write_report(
    *,
    state_root: Path,
    apk: Path,
    version: str,
    baseline: dict[str, Any],
    resolutions: list[RoleResolution],
    decompiled: Path,
) -> tuple[Path, Path]:
    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    report_dir = state_root / "reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    json_path = report_dir / f"ytmusic-{version}-{timestamp}.json"
    text_path = report_dir / f"ytmusic-{version}-{timestamp}.txt"
    sources_root = decompiled / "sources"

    payload = {
        "schema": 1,
        "created_at": utc_now_text(),
        "target_version": version,
        "target_apk": str(apk),
        "target_apk_sha256": sha256_file(apk),
        "baseline_version": baseline["version"],
        "all_confident": all(resolution.confident for resolution in resolutions),
        "resolutions": [
            {
                "role": resolution.role,
                "label": ROLE_LABELS[resolution.role],
                "selected": resolution.selected,
                "status": resolution.status,
                "score": round(resolution.score, 6),
                "margin": round(resolution.margin, 6),
                "reason": resolution.reason,
                "candidates": [
                    candidate_to_json(candidate, sources_root)
                    for candidate in resolution.candidates
                ],
            }
            for resolution in resolutions
        ],
    }
    save_json(json_path, payload)

    lines = [
        "Pin playlists compatibility scan",
        "=" * 36,
        f"Created:          {payload['created_at']}",
        f"Target APK:       {apk}",
        f"Target version:   {version}",
        f"Baseline version: {baseline['version']}",
        f"All confident:    {payload['all_confident']}",
        "",
    ]

    for resolution in resolutions:
        lines.extend([
            f"{ROLE_LABELS[resolution.role]}",
            f"  status:   {resolution.status}",
            f"  selected: {resolution.selected}",
            f"  score:    {resolution.score:.3f}",
            f"  margin:   {resolution.margin:.3f}",
            f"  reason:   {resolution.reason}",
            "  candidates:",
        ])
        if not resolution.candidates:
            lines.append("    (none)")
        else:
            for candidate in resolution.candidates:
                relative_path = candidate.path
                if relative_path:
                    try:
                        relative_path = str(Path(relative_path).relative_to(sources_root))
                    except ValueError:
                        pass
                lines.append(
                    f"    {candidate.symbol:<14} "
                    f"score={candidate.score:.3f} "
                    f"path={relative_path or '-'}"
                )
        lines.append("")

    text_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return text_path, json_path


def replace_array_prepend(source: str, array_name: str, symbol: str) -> tuple[str, bool]:
    pattern = re.compile(
        rf"(?P<prefix>private\s+static\s+final\s+String\[\]\s+"
        rf"{re.escape(array_name)}\s*=\s*\{{)"
        rf"(?P<body>.*?)"
        rf"(?P<suffix>\}}\s*;)",
        re.DOTALL,
    )
    match = pattern.search(source)
    if not match:
        raise CompatibilityError(f"Could not update array {array_name}.")

    existing = re.findall(r'"([^"]+)"', match.group("body"))
    if symbol in existing:
        return source, False

    updated_symbols = [symbol, *existing]
    indentation_match = re.search(r"\n([ \t]*)\{", match.group(0))
    # The repository currently keeps short arrays on one line. Preserve that form.
    body = ", ".join(json.dumps(value) for value in updated_symbols)
    replacement = match.group("prefix") + body + match.group("suffix")
    return source[:match.start()] + replacement + source[match.end():], True


def update_build_id(source: str, version: str) -> str:
    compact = version.replace(".", "")
    value = f"auto-ytm{compact}-symbols"
    pattern = re.compile(
        r'private\s+static\s+final\s+String\s+BUILD_ID\s*=\s*"[^"]*"\s*;'
    )
    if not pattern.search(source):
        raise CompatibilityError("Could not find BUILD_ID in PinPlaylistPatch.java.")
    return pattern.sub(
        f'private static final String BUILD_ID = "{value}";',
        source,
        count=1,
    )


def apply_resolutions(
    *,
    repo: Path,
    version: str,
    resolutions: list[RoleResolution],
) -> tuple[Path, Path | None, list[str]]:
    if not all(
        resolution.confident
        and (
            resolution.selected is not None
            or resolution.status == "optional"
        )
        for resolution in resolutions
    ):
        raise CompatibilityError(
            "--apply was requested, but at least one required role is "
            "ambiguous or blocked."
        )

    source_path = repo / PIN_SOURCE_RELATIVE
    original = source_path.read_text(encoding="utf-8")
    updated = original
    changes: list[str] = []

    resolution_by_role = {resolution.role: resolution for resolution in resolutions}

    for role, array_name in CLASS_ARRAY_ROLES.items():
        symbol = resolution_by_role[role].selected
        assert symbol is not None
        updated, changed = replace_array_prepend(updated, array_name, symbol)
        if changed:
            changes.append(f"{array_name}: prepended {symbol}")

    for role, spec in METHOD_ARRAY_ROLES.items():
        resolution = resolution_by_role[role]
        array_name = str(spec["array"])

        if resolution.status == "optional":
            changes.append(
                f"{array_name}: unchanged; using full-refresh fallback"
            )
            continue

        symbol = resolution.selected
        assert symbol is not None
        updated, changed = replace_array_prepend(
            updated,
            array_name,
            symbol,
        )
        if changed:
            changes.append(f"{array_name}: prepended {symbol}")

    symbol_arrays_changed = updated != original

    if symbol_arrays_changed:
        updated = update_build_id(updated, version)
        backup = Path(tempfile.gettempdir()) / (
            "PinPlaylistPatch-before-auto-"
            + dt.datetime.now().strftime("%Y%m%d-%H%M%S")
            + ".java"
        )
        backup.write_text(
            original,
            encoding="utf-8",
            newline="\n",
        )
        source_path.write_text(
            updated,
            encoding="utf-8",
            newline="\n",
        )
        return source_path, backup, changes

    changes.append(
        "No required symbol arrays changed; source left untouched."
    )
    return source_path, None, changes


def git_tracked_changes(repo: Path) -> list[str]:
    result = run_process(
        ["git", "-C", str(repo), "status", "--porcelain=v1", "--untracked-files=no"],
        capture=True,
    )
    return [line for line in (result.stdout or "").splitlines() if line.strip()]


def find_official_mpp(explicit: Path | None) -> Path:
    if explicit:
        path = explicit.expanduser().resolve()
        if not path.is_file():
            raise CompatibilityError(f"Official MPP not found: {path}")
        return path

    candidates = sorted(
        (Path.home() / "Desktop" / "Tools").glob("patches-*.mpp"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    candidates = [
        path for path in candidates
        if "sources" not in path.name and "javadoc" not in path.name
    ]
    if not candidates:
        raise CompatibilityError("No official Morphe MPP was found under Desktop\\Tools.")
    return candidates[0].resolve()


def find_patcher_jar(explicit: Path | None) -> Path:
    if explicit:
        path = explicit.expanduser().resolve()
        if not path.is_file():
            raise CompatibilityError(f"Morphe patcher JAR not found: {path}")
        return path

    tools = Path.home() / "Desktop" / "Tools"
    candidates = sorted(
        list(tools.glob("morphe-desktop-*-all.jar"))
        + list(tools.glob("morphe-cli-*-all.jar")),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise CompatibilityError("No Morphe CLI-capable JAR was found under Desktop\\Tools.")
    return candidates[0].resolve()


def build_and_verify(
    *,
    repo: Path,
    apk: Path,
    version: str,
    official_mpp: Path | None,
    patcher_jar: Path | None,
) -> Path:
    tracked = git_tracked_changes(repo)
    allowed_suffix = str(PIN_SOURCE_RELATIVE).replace("\\", "/")
    unexpected = [
        line for line in tracked
        if not line[3:].replace("\\", "/").endswith(allowed_suffix)
    ]
    if unexpected:
        raise CompatibilityError(
            "Tracked changes outside PinPlaylistPatch.java prevent verification:\n"
            + "\n".join(unexpected)
        )

    run_process(
        [
            str(repo / "gradlew.bat"),
            "--no-build-cache",
            ":extensions:music:clean",
            ":patches:clean",
            ":patches:build",
            "-x",
            ":patches:test",
        ],
        cwd=repo,
    )

    mpps = sorted(
        (repo / "patches" / "build" / "libs").glob("patches-*.mpp"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    mpps = [
        path for path in mpps
        if "sources" not in path.name and "javadoc" not in path.name
    ]
    if not mpps:
        raise CompatibilityError("Gradle succeeded but no custom MPP was produced.")
    custom_mpp = mpps[0]

    official = find_official_mpp(official_mpp)
    patcher = find_patcher_jar(patcher_jar)
    output_dir = repo / "build" / "compat-verification"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_apk = output_dir / f"youtube-music-{version}-pin-compat.apk"
    if output_apk.exists():
        output_apk.unlink()

    run_process(
        [
            "java",
            "-jar",
            str(patcher),
            "patch",
            "-p",
            str(official),
            "-e",
            "GmsCore support",
            "-e",
            "Hide ads",
            "-e",
            "Spoof video streams",
            "-p",
            str(custom_mpp),
            "-e",
            "Pin playlists",
            "--exclusive",
            "-f",
            "-o",
            str(output_apk),
            str(apk),
        ],
        cwd=repo,
    )

    if not output_apk.is_file():
        raise CompatibilityError("Patcher reported success but produced no verification APK.")
    return output_apk


def print_resolution_summary(resolutions: Sequence[RoleResolution]) -> None:
    print("\nResolution summary")
    print("=" * 72)
    for resolution in resolutions:
        print(
            f"{ROLE_LABELS[resolution.role]:35} "
            f"{resolution.status:10} "
            f"{str(resolution.selected):12} "
            f"score={resolution.score:.3f} "
            f"margin={resolution.margin:.3f}"
        )
    print("=" * 72)


def promote_baseline(
    *,
    repo: Path,
    apk: Path,
    version: str,
    decompiled: Path,
    state_root: Path,
) -> dict[str, Any]:
    previous = baseline_path(state_root)
    if previous.exists():
        archive_dir = state_root / "baseline-history"
        archive_dir.mkdir(parents=True, exist_ok=True)
        archive = archive_dir / (
            f"baseline-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        )
        shutil.copy2(previous, archive)
    return bootstrap_baseline(
        repo=repo,
        apk=apk,
        version=version,
        decompiled=decompiled,
        state_root=state_root,
    )


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Detect renamed YouTube Music symbols used by the Pin playlists patch. "
            "The default mode is read-only."
        )
    )
    parser.add_argument("--repo", type=Path, help="Path to Seobject-patches.")
    parser.add_argument("--apk", type=Path, help="Target YouTube Music APK.")
    parser.add_argument("--jadx", type=Path, help="Path to jadx.bat/jadx.")
    parser.add_argument(
        "--jadx-root",
        type=Path,
        default=DEFAULT_JADX_ROOT,
        help=(
            "Directory where downloaded JADX releases are installed."
        ),
    )
    parser.add_argument(
        "--state-root",
        type=Path,
        default=default_state_root(),
        help=(
            "Directory for the baseline, reports, baseline history, "
            "and small decompilation index."
        ),
    )
    parser.add_argument(
        "--decompile-root",
        type=Path,
        default=DEFAULT_DECOMPILE_ROOT,
        help=(
            "Root directory for versioned JADX output. Each APK is "
            "written to YouTubeMusic_x.xx.xx."
        ),
    )
    parser.add_argument(
        "--force-decompile",
        action="store_true",
        help="Ignore cached JADX output.",
    )
    parser.add_argument(
        "--bootstrap",
        action="store_true",
        help="Replace/create the structural baseline from current known symbols.",
    )
    parser.add_argument(
        "--promote-baseline",
        action="store_true",
        help=(
            "After manual runtime testing, replace the baseline with the current "
            "target and source symbols."
        ),
    )
    parser.add_argument(
        "--simulate-rename",
        action="store_true",
        help=(
            "Ignore all known additive symbols and force the structural "
            "discovery path. This is a read-only regression test for the "
            "current APK."
        ),
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Prepend all confidently discovered symbols to source arrays.",
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="After --apply, build the MPP and patch-test the APK.",
    )
    parser.add_argument("--official-mpp", type=Path)
    parser.add_argument("--patcher-jar", type=Path)
    parser.add_argument(
        "--workers",
        type=int,
        default=max(2, min(8, os.cpu_count() or 4)),
        help="Parallel fingerprint workers.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])

    if args.simulate_rename and (args.apply or args.verify):
        raise CompatibilityError(
            "--simulate-rename is read-only and cannot be combined with "
            "--apply or --verify."
        )

    state_root = args.state_root.expanduser().resolve()
    state_root.mkdir(parents=True, exist_ok=True)
    jadx_root = args.jadx_root.expanduser().resolve()
    jadx_root.mkdir(parents=True, exist_ok=True)
    decompile_root = (
        args.decompile_root
        .expanduser()
        .resolve()
    )

    repo = find_repo(args.repo)
    apk, version = find_newest_apk(args.apk)
    jadx = find_jadx(args.jadx, jadx_root)

    print("Pin playlists compatibility detector")
    print("=" * 36)
    print(f"Repository:   {repo}")
    print(f"APK:          {apk}")
    print(f"Version:      {version}")
    print(f"JADX:         {jadx}")
    print(f"JADX root:    {jadx_root}")
    print(f"State root:   {state_root}")
    print(f"Decompile to: {decompile_root / f'YouTubeMusic_{version}'}")
    print("Mode:         " + (
        "simulated rename discovery"
        if args.simulate_rename
        else "apply + verify" if args.apply and args.verify
        else "apply" if args.apply
        else "read-only"
    ))

    decompiled = decompile_apk(
        apk,
        version,
        jadx,
        state_root,
        decompile_root,
        force=args.force_decompile,
    )

    if args.bootstrap:
        payload = promote_baseline(
            repo=repo,
            apk=apk,
            version=version,
            decompiled=decompiled,
            state_root=state_root,
        )
        print(f"\nBaseline created for {payload['version']}: {baseline_path(state_root)}")
        return 0

    baseline = load_baseline(state_root)
    if baseline is None:
        payload = bootstrap_baseline(
            repo=repo,
            apk=apk,
            version=version,
            decompiled=decompiled,
            state_root=state_root,
        )
        print(
            f"\nNo baseline existed, so a bootstrap baseline was created for "
            f"{payload['version']}."
        )
        print(f"Baseline: {baseline_path(state_root)}")
        print("Run the same command again to perform a read-only self-check.")
        return 0

    resolutions = scan_version(
        repo=repo,
        apk=apk,
        version=version,
        decompiled=decompiled,
        baseline=baseline,
        state_root=state_root,
        workers=max(1, args.workers),
        simulate_rename=args.simulate_rename,
    )
    print_resolution_summary(resolutions)
    text_report, json_report = write_report(
        state_root=state_root,
        apk=apk,
        version=version,
        baseline=baseline,
        resolutions=resolutions,
        decompiled=decompiled,
    )
    print(f"\nText report: {text_report}")
    print(f"JSON report: {json_report}")

    all_confident = all(resolution.confident for resolution in resolutions)
    if not all_confident:
        print(
            "\nRESULT: AMBIGUOUS. Nothing was modified. Attach the text report "
            "for manual review."
        )
        return 2

    if not args.apply:
        if args.simulate_rename:
            expected = {
                role: str(data["symbol"])
                for role, data in baseline["roles"].items()
            }
            mismatches = []

            for resolution in resolutions:
                if resolution.role in OPTIONAL_ROLES:
                    if resolution.status not in {
                        "discovered",
                        "optional",
                    }:
                        mismatches.append(resolution)
                    continue

                if (
                    resolution.status != "discovered"
                    or resolution.selected
                    != expected.get(resolution.role)
                ):
                    mismatches.append(resolution)

            if mismatches:
                print(
                    "\nRESULT: SIMULATED RENAME FAILED. The structural "
                    "discovery path did not recover every required symbol, "
                    "or a required result remained ambiguous. Nothing was "
                    "modified."
                )
                return 3

            print(
                "\nRESULT: SIMULATED RENAME PASSED. With all known symbols "
                "hidden, structural discovery recovered every required "
                "9.28.51 class and method. The indistinguishable move "
                "notifier was safely left optional because the runtime has "
                "a verified full-refresh fallback. Nothing was modified."
            )
            return 0

        discovered = [
            resolution
            for resolution in resolutions
            if resolution.status == "discovered"
        ]
        optional = [
            resolution
            for resolution in resolutions
            if resolution.status == "optional"
        ]

        if discovered:
            suffix = (
                " The move notifier will remain unchanged and the "
                "runtime will use full refresh."
                if optional
                else ""
            )
            print(
                "\nRESULT: CONFIDENT PROPOSAL. Source was not modified "
                "because --apply was not supplied."
                + suffix
            )
        elif optional:
            print(
                "\nRESULT: COMPATIBLE WITH FULL-REFRESH FALLBACK. "
                "All required symbols are available; the optional move "
                "notifier was not guessed."
            )
        else:
            print(
                "\nRESULT: COMPATIBLE. All required symbols already exist "
                "in the additive arrays."
            )
        return 0

    source_path = repo / PIN_SOURCE_RELATIVE
    original = source_path.read_text(encoding="utf-8")
    backup: Path | None = None

    try:
        source_path, backup, changes = apply_resolutions(
            repo=repo,
            version=version,
            resolutions=resolutions,
        )
        if backup is not None:
            print(f"\nSource updated: {source_path}")
            print(f"Backup:         {backup}")
        else:
            print(f"\nSource unchanged: {source_path}")

        for change in changes:
            print(f"  - {change}")

        run_process(
            [
                "git",
                "-C",
                str(repo),
                "diff",
                "--check",
                "--",
                str(PIN_SOURCE_RELATIVE),
            ]
        )

        if args.verify:
            output_apk = build_and_verify(
                repo=repo,
                apk=apk,
                version=version,
                official_mpp=args.official_mpp,
                patcher_jar=args.patcher_jar,
            )
            print(f"\nVerification APK: {output_apk}")
            print(
                "Install and runtime-test this APK. After it passes, run with "
                "--promote-baseline to make this version the next comparison base."
            )
        else:
            if backup is not None:
                print(
                    "\nSource changed but was not built. Re-run with "
                    "--apply --verify to perform the build and APK "
                    "patch test."
                )
            else:
                print(
                    "\nNo source update was required. Use --apply "
                    "--verify to patch-test the current source against "
                    "this APK."
                )
    except Exception:
        if backup is not None:
            source_path.write_text(
                original,
                encoding="utf-8",
                newline="\n",
            )
            print(
                "\nSource restored after failure from the in-memory "
                f"backup. File backup: {backup}"
            )
        raise

    if args.promote_baseline:
        payload = promote_baseline(
            repo=repo,
            apk=apk,
            version=version,
            decompiled=decompiled,
            state_root=state_root,
        )
        print(f"\nBaseline promoted to {payload['version']}.")

    print("\nNo commit, push, tag, or release was performed.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        raise SystemExit(130)
    except CompatibilityError as error:
        print(f"\nERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
