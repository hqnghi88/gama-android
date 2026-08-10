#!/usr/bin/env python3
"""Discover (jar_path, experiment) pairs for all models in gama.library jar.

Extracts every .gaml from the jar, parses inline experiment declarations and
resolves 'import ...as' experiment imports from sibling .experiment files.
Writes a TSV: jar_path<TAB>experiment_name (one per experiment).
"""
import io
import sys
import re
import zipfile

JAR = "/Users/hqnghi/git/gama-android/native-app/app/libs/gama.library_0.0.0.202607310828.jar"
PREFIX = "models/"
GAML_RE = re.compile(r"^experiment\s+(\w+)", re.MULTILINE)
IMPORT_RE = re.compile(r'^import\s+"([^"]+)"\s+as\s+\w+\s*$', re.MULTILINE)

zf = zipfile.ZipFile(JAR)

def read(name):
    try:
        return zf.read(name).decode("utf-8", errors="replace")
    except KeyError:
        return ""

entries = {}
for info in zf.infolist():
    if info.filename.endswith(".gaml") and info.filename.startswith(PREFIX):
        entries[info.filename] = True

results = []
for path in sorted(entries):
    content = read(path)
    if not content:
        continue
    base = path.rsplit("/", 1)[0]
    names = [m.group(1) for m in GAML_RE.finditer(content)]
    # resolve experiment imports from .experiment files in the same dir
    for imp in IMPORT_RE.finditer(content):
        target = imp.group(1)
        tpath = f"{base}/{target}"
        if not tpath.endswith(".experiment"):
            tpath += ".experiment"
        tcontent = read(tpath)
        names += [m.group(1) for m in GAML_RE.finditer(tcontent)]
    seen = set()
    for n in names:
        if n not in seen:
            seen.add(n)
            results.append((path, n))

for path, exp in results:
    print(f"{path}\t{exp}")
