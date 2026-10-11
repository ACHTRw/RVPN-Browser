#!/usr/bin/env python3
"""String-resource consistency checker for the RVPN Browser Android app.

Checks performed:
  1. every key of res/values/strings.xml also exists in res/values-en/strings.xml
     and vice versa (identical key sets, the default locale is Chinese);
  2. every @string/... reference found in res/**/*.xml exists;
  3. every R.string.... reference found in java/**/*.java exists;
  4. positional format arguments (%1$s, %2$d, ...) match between locales;
  5. reports strings that are never referenced (informational).

Exit code 0 = all hard checks passed.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
RES = os.path.join(ANDROID, "app", "src", "main", "res")
JAVA = os.path.join(ANDROID, "app", "src", "main", "java")

REQUIRED_LOCALES = ["values", "values-en"]
errors = []
warnings = []


def load_strings(path):
    if not os.path.isfile(path):
        errors.append("missing file: %s" % path)
        return {}
    root = ET.parse(path).getroot()
    out = {}
    for el in root:
        if el.tag != "string":
            continue
        name = el.get("name")
        if not name:
            errors.append("%s: <string> without name" % path)
            continue
        if name in out:
            errors.append("%s: duplicate string name %r" % (path, name))
        out[name] = "".join(el.itertext())
    return out


def placeholders(text):
    return sorted(set(re.findall(r"%(?:\d+\$)?[sdfx]", text)))


def main():
    per_locale = {}
    for loc in REQUIRED_LOCALES:
        per_locale[loc] = load_strings(os.path.join(RES, loc, "strings.xml"))

    base = per_locale["values"]
    if not base:
        print("FATAL: the default locale has no strings")
        return 1

    # --- 1. key parity -----------------------------------------------------
    for loc in REQUIRED_LOCALES[1:]:
        other = per_locale[loc]
        missing = sorted(set(base) - set(other))
        extra = sorted(set(other) - set(base))
        if missing:
            errors.append("%s: %d key(s) missing: %s" % (loc, len(missing), ", ".join(missing)))
        if extra:
            errors.append("%s: %d key(s) not present in values/: %s" % (loc, len(extra), ", ".join(extra)))
        # --- 4. placeholder parity -----------------------------------------
        for key in sorted(set(base) & set(other)):
            a, b = placeholders(base[key]), placeholders(other[key])
            if a != b:
                errors.append("%s: %s has placeholders %s but values/ has %s" % (loc, key, b, a))

    # --- 2. @string/... references in res/ ---------------------------------
    refs = set()
    ref_re = re.compile(r"@string/([A-Za-z0-9_.]+)")
    for root_dir, _dirs, files in os.walk(RES):
        for f in files:
            if not f.endswith(".xml"):
                continue
            p = os.path.join(root_dir, f)
            with open(p, encoding="utf-8") as fh:
                for m in ref_re.finditer(fh.read()):
                    refs.add(m.group(1))

    # --- 3. R.string.... references in java/ -------------------------------
    java_ref_re = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
    for root_dir, _dirs, files in os.walk(JAVA):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(root_dir, f)
            with open(p, encoding="utf-8") as fh:
                for m in java_ref_re.finditer(fh.read()):
                    refs.add(m.group(1))

    unknown = sorted(r for r in refs if r not in base)
    if unknown:
        errors.append("%d referenced string(s) are not defined: %s" % (len(unknown), ", ".join(unknown)))

    unused = sorted(set(base) - refs - {"app_name"})

    # --- report ------------------------------------------------------------
    print("locales checked          : %s" % ", ".join(REQUIRED_LOCALES))
    for loc in REQUIRED_LOCALES:
        print("  %-24s %d strings" % (loc, len(per_locale[loc])))
    print("referenced string keys   : %d" % len(refs))
    print("unused string keys       : %d%s" % (
        len(unused), (" -> " + ", ".join(unused)) if unused else ""))
    for w in warnings:
        print("WARNING: %s" % w)
    if errors:
        print("\n%d ERROR(S):" % len(errors))
        for e in errors:
            print("  - %s" % e)
        return 1
    print("\nOK: key sets are identical and every referenced string exists.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
