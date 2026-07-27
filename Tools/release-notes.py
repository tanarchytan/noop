#!/usr/bin/env python3
"""Build a release body from a version's notes file, in one shape for every release.

    release-notes.py --version 9.0.1-rc3-tan --appid com.noop.tan --min-sdk 26 --channel rc

Notes live in `docs/releases/`, ONE file per version. A candidate is a candidate FOR a version, so
`9.0.1-rc3-tan` reads `v9.0.1-tan.md` — the same file the stable publishes — and there is no
per-candidate copy to keep in step. A candidate-specific file still wins where one exists.

The YAML front-matter (`whatsnew:`, consumed by appchangelog-gen.py for the in-app card) is stripped;
the body below it is the release text. The download block is appended from a constant template so it
can never drift between the two workflows or go stale on an app-id rename.

Prints the body to stdout. Missing notes are a warning and a placeholder, never a failure: a release
must not be blocked on prose.
"""
import argparse
import pathlib
import re
import sys

RELEASES = pathlib.Path("docs/releases")

DOWNLOAD_HEADER = "\n---\n\n**Download** — sideload on Android (minSdk {min_sdk}).{note}\n"

# Per channel: the app line's trailing clause, and anything that follows the download block.
CHANNELS = {
    "stable": {
        "note": " Installs beside any other NOOP fork, with its own data:",
        "app": "the app.",
        "footer": (
            "\n**Paired with** [whoop-rs](https://github.com/tanarchytan/whoop-rs) — the Rust BLE "
            "client and protocol core that owns every score.\n"
        ),
    },
    "rc": {
        "note": "",
        "app": "debuggable, and installs over a release build keeping your data.",
        "footer": "\n*Release candidate: not yet verified on hardware.*\n",
    },
}

MOCK_LINE = (
    "- **NOOP Mock** — `NOOP-android-mock-v{version}.apk` (`{appid}.mock`) — preloaded with "
    "synthetic data, installs alongside. For trying it out with no strap.\n"
)


def notes_path(version: str) -> pathlib.Path | None:
    """This version's notes, else the version a candidate is FOR. None when neither exists."""
    exact = RELEASES / f"v{version}.md"
    if exact.is_file():
        return exact
    # 9.0.1-rc3-tan -> 9.0.1-tan
    target = re.sub(r"-rc\d+", "", version)
    if target != version:
        fallback = RELEASES / f"v{target}.md"
        if fallback.is_file():
            return fallback
    return None


def strip_front_matter(text: str) -> str:
    return re.sub(r"\A---\r?\n.*?\r?\n---\r?\n", "", text, flags=re.S)


def build(version: str, appid: str, min_sdk: str, channel: str, extra: str = "") -> str:
    spec = CHANNELS[channel]
    path = notes_path(version)
    if path is None:
        print(f"::warning::no notes in {RELEASES}/ for {version} — using a placeholder", file=sys.stderr)
        body = f"NOOP **{version}**.\n"
    else:
        print(f"release-notes: {version} <- {path}", file=sys.stderr)
        body = strip_front_matter(path.read_text(encoding="utf-8")).strip() + "\n"

    # Anything generated (the auto "What's Changed" list) sits between the prose and the
    # downloads, so the download block stays last where a reader looks for it.
    if extra.strip():
        body += "\n---\n\n" + extra.strip() + "\n"

    body += DOWNLOAD_HEADER.format(min_sdk=min_sdk, note=spec["note"])
    body += f"- **NOOP** — `NOOP-android-v{version}.apk` (`{appid}`) — {spec['app']}\n"
    body += MOCK_LINE.format(version=version, appid=appid)
    body += spec["footer"]
    return body


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--version", required=True)
    p.add_argument("--appid", required=True)
    p.add_argument("--min-sdk", required=True)
    p.add_argument("--channel", required=True, choices=sorted(CHANNELS))
    p.add_argument("--extra-file", help="markdown inserted between the notes and the downloads")
    a = p.parse_args()
    extra = pathlib.Path(a.extra_file).read_text(encoding="utf-8") if a.extra_file else ""
    sys.stdout.write(build(a.version, a.appid, a.min_sdk, a.channel, extra))


if __name__ == "__main__":
    main()
