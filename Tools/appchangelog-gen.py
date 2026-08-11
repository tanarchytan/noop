#!/usr/bin/env python3
"""Generate the in-app "What's New" entry (AppChangelog.kt) for Android from a release file's
front-matter, so the Kotlin entry + version bump are automatic. (noop-tan is Android-only.)

A per-version notes file docs/releases/v<VER>.md may carry a YAML front-matter block:

    ---
    whatsnew:
      title: "Short headline for the in-app card"
      date: "July 2026"
      items:
        - "**Bold lead.** One-line description."
        - "**Another.** ..."
    ---
    # NOOP v<VER>
    <the full release notes — the GitHub release body; the front-matter is stripped there>

Running `Tools/appchangelog-gen.py docs/releases/v8.2.2.md` prepends the generated Release entry to
`releases` in AppChangelog.kt and bumps CURRENT_VERSION to that version. Idempotent: if the version is
already the newest entry its block is regenerated in place, so editing the notes across rc cuts reaches
the app. The version comes from the filename (v8.2.2.md -> 8.2.2).
"""
import re
import sys
import pathlib

try:
    import yaml
except ImportError:
    sys.exit("appchangelog-gen: needs PyYAML (pip install pyyaml)")

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT = ROOT / "android/app/src/main/java/com/noop/ui/AppChangelog.kt"


def frontmatter(md: pathlib.Path) -> dict:
    m = re.match(r"^---\r?\n(.*?)\r?\n---\r?\n", md.read_text(encoding="utf-8"), re.S)
    if not m:
        sys.exit(f"appchangelog-gen: no YAML front-matter in {md}")
    wn = (yaml.safe_load(m.group(1)) or {}).get("whatsnew")
    if not (wn and wn.get("title") and wn.get("date") and wn.get("items")):
        sys.exit(f"appchangelog-gen: front-matter needs whatsnew.{{title,date,items}} in {md}")
    return wn


def esc_kt(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def kt_block(ver, wn):
    items = "\n".join(f'                "{esc_kt(i)}",' for i in wn["items"])
    return (
        "        Release(\n"
        f'            version = "{ver}",\n'
        f'            title = "{esc_kt(wn["title"])}",\n'
        f'            date = "{esc_kt(wn["date"])}",\n'
        "            items = listOf(\n"
        f"{items}\n"
        "            ),\n"
        "        ),\n"
    )


def apply(path, anchor, block, ver, const_re, const_new):
    text = path.read_text(encoding="utf-8")
    idx = text.index(anchor) + len(anchor)
    # A version already at the head is REPLACED, not skipped: the notes are edited across rc cuts, and
    # skipping left the sheet showing an older set of items than the notes file it is generated from.
    head = re.compile(
        r'        Release\(\n            version = "%s",\n.*?\n        \),\n' % re.escape(ver), re.S
    )
    m = head.match(text, idx)
    if m:
        verb = "replaced" if m.group(0) != block else "unchanged"
        text = text[:idx] + block + text[m.end():]
    else:
        verb = "inserted"
        text = text[:idx] + block + text[idx:]
    text, n = re.subn(const_re, const_new, text, count=1)
    if n != 1:
        sys.exit(f"appchangelog-gen: could not bump the version constant in {path.name}")
    path.write_text(text, encoding="utf-8")
    print(f"  {path.name}: v{ver} entry {verb}, constant set")


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: appchangelog-gen.py docs/releases/v<VER>.md")
    md = pathlib.Path(sys.argv[1])
    ver = md.stem.lstrip("vV")
    wn = frontmatter(md)
    print(f"appchangelog-gen: v{ver} — {wn['title']}")
    apply(KT, "val releases: List<Release> = listOf(\n", kt_block(ver, wn), ver,
          r'(const val CURRENT_VERSION = ")[^"]*(")', rf'\g<1>{ver}\g<2>')
    print("appchangelog-gen: done. Review the diff, then compile.")


if __name__ == "__main__":
    main()
