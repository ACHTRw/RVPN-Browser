#!/usr/bin/env python3
"""Apply the RVPN Browser integration on top of a Thorium-patched Chromium tree.

The script is idempotent and strict: every edit is verified, and a missing
anchor aborts the run instead of silently corrupting the source tree.

Stages
  1 branding        product name, executable name, user data dir, desktop entry
  2 icons           every product_logo_*.png rendered from icon.svg
  3 extension       bundle browser/extension as a built-in component extension
  4 android         app name/icon + RVPN UI (only with --android)

Usage
  browser/scripts/apply-rvpn-patches.py --chromium-src ~/chromium/src
  browser/scripts/apply-rvpn-patches.py --chromium-src ~/chromium/src --check
  browser/scripts/apply-rvpn-patches.py --chromium-src ~/chromium/src --android
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

PRODUCT_NAME = "RVPN Browser"
PRODUCT_SHORT = "RVPN Browser"
COMPANY = "RVPN Browser Project"
DATA_DIR_BASENAME = "rvpn-browser"
EXECUTABLE_NAME = "rvpn-browser"
DESKTOP_NAME = "rvpn-browser.desktop"
MAC_BUNDLE_ID = "org.rvpnbrowser.RVPNBrowser"
EXTENSION_DIR_NAME = "rvpn_browser_extension"

# Chromium's own (unpatched) and Thorium's values are both accepted so the
# script works before or after the Thorium patch series is applied.
PRODUCT_VARIANTS = ["Thorium", "Chromium"]
LOWER_VARIANTS = ["thorium", "chromium"]


class PatchError(RuntimeError):
    pass


class Patcher:
    def __init__(self, src: Path, check_only: bool = False, verbose: bool = True):
        self.src = src
        self.check_only = check_only
        self.verbose = verbose
        self.changed: list[str] = []
        self.skipped: list[str] = []
        self._icon_cache: dict[int, bytes] = {}

    # ---------------------------------------------------------------- helpers
    def path(self, rel: str) -> Path:
        p = self.src / rel
        if not p.exists():
            raise PatchError(f"file not found in the Chromium tree: {rel}")
        return p

    def log(self, msg: str) -> None:
        if self.verbose:
            print("    " + msg)

    def _save(self, path: Path, text: str) -> None:
        rel = str(path.relative_to(self.src))
        if self.check_only:
            self.changed.append(rel + " (would change)")
            return
        path.write_text(text, encoding="utf-8")
        self.changed.append(rel)

    def replace(self, rel: str, old: str, new: str, *, count: int = 1) -> bool:
        """Replace `old` with `new`; returns False when `old` is absent."""
        path = self.path(rel)
        text = path.read_text(encoding="utf-8")
        if old not in text:
            return False
        if new in text and old not in text.replace(new, "", 1):
            return False
        occurrences = text.count(old)
        if count and occurrences > count:
            raise PatchError(f"{rel}: anchor {old!r} appears {occurrences} times, expected <= {count}")
        text = text.replace(old, new, count if count else -1)
        self._save(path, text)
        self.log(f"{rel}: {old!r} -> {new!r}")
        return True

    def replace_any(self, rel: str, pairs: list[tuple[str, str]], *, required: bool = True) -> bool:
        """Try a list of (old, new) alternatives, first match wins."""
        path = self.path(rel)
        text = path.read_text(encoding="utf-8")
        for old, new in pairs:
            if old in text:
                if new in text:
                    self.skipped.append(f"{rel} (already patched)")
                    return True
                text = text.replace(old, new, 1)
                self._save(path, text)
                self.log(f"{rel}: {old!r} -> {new!r}")
                return True
        if required:
            anchors = " | ".join(repr(o) for o, _ in pairs)
            raise PatchError(f"{rel}: none of the expected anchors found: {anchors}")
        self.skipped.append(f"{rel} (anchor not found, optional)")
        return False

    def write_file(self, rel: str, content: str) -> None:
        path = self.src / rel
        if path.exists() and path.read_text(encoding="utf-8") == content:
            self.skipped.append(f"{rel} (unchanged)")
            return
        if self.check_only:
            self.changed.append(rel + " (would write)")
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        self.changed.append(rel)
        self.log(f"{rel}: written")

    def copy_tree(self, source: Path, dest_rel: str) -> None:
        dest = self.src / dest_rel
        if not source.exists():
            raise PatchError(f"source directory missing: {source}")
        if self.check_only:
            self.changed.append(dest_rel + " (would copy)")
            return
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(source, dest)
        self.changed.append(dest_rel)
        self.log(f"{dest_rel}: copied from {source}")

    # ------------------------------------------------------------------ icons
    def render_icon(self, size: int) -> bytes:
        if size in self._icon_cache:
            return self._icon_cache[size]
        svg = REPO_ROOT / "icon.svg"
        if not svg.exists():
            raise PatchError(f"icon.svg not found at {svg}")
        out = Path("/tmp") / f"rvpn-icon-{size}.png"
        for cmd in (["rsvg-convert", "-w", str(size), "-h", str(size), str(svg), "-o", str(out)],
                    ["inkscape", str(svg), "-w", str(size), "-h", str(size), "-o", str(out)],
                    ["convert", "-background", "none", "-resize", f"{size}x{size}", str(svg), str(out)]):
            if shutil.which(cmd[0]):
                subprocess.run(cmd, check=True, capture_output=True)
                break
        else:
            raise PatchError("no SVG renderer found (install librsvg2-bin)")
        data = out.read_bytes()
        self._icon_cache[size] = data
        return data


def stage_branding(p: Patcher) -> None:
    print("==> [1/4] branding")

    branding = p.path("chrome/app/theme/chromium/BRANDING")
    text = branding.read_text(encoding="utf-8")
    replacements = {
        "COMPANY_FULLNAME": COMPANY,
        "COMPANY_SHORTNAME": COMPANY,
        "PRODUCT_FULLNAME": PRODUCT_NAME,
        "PRODUCT_SHORTNAME": PRODUCT_SHORT,
        "PRODUCT_INSTALLER_FULLNAME": PRODUCT_NAME + " Installer",
        "PRODUCT_INSTALLER_SHORTNAME": PRODUCT_NAME + " Installer",
        "MAC_BUNDLE_ID": MAC_BUNDLE_ID,
    }
    for key, value in replacements.items():
        text = re.sub(rf"^{key}=.*$", f"{key}={value}", text, flags=re.MULTILINE)
    if not p.check_only:
        branding.write_text(text, encoding="utf-8")
    p.changed.append("chrome/app/theme/chromium/BRANDING")
    p.log("chrome/app/theme/chromium/BRANDING: product/company metadata")

    # Executable names (POSIX + Android).
    p.replace_any("chrome/common/chrome_constants.cc", [
        ('const base::FilePath::CharType kBrowserProcessExecutableName[] =\n    FPL("thorium");',
         f'const base::FilePath::CharType kBrowserProcessExecutableName[] =\n    FPL("{EXECUTABLE_NAME}");'),
        ('const base::FilePath::CharType kBrowserProcessExecutableName[] =\n    FPL("chromium");',
         f'const base::FilePath::CharType kBrowserProcessExecutableName[] =\n    FPL("{EXECUTABLE_NAME}");'),
    ], required=False)
    p.replace_any("chrome/common/chrome_constants.cc", [
        ('#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutableName[] = FPL("thorium");',
         f'#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutableName[] = FPL("{EXECUTABLE_NAME}");'),
        ('#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutableName[] = FPL("chrome");',
         f'#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutableName[] = FPL("{EXECUTABLE_NAME}");'),
    ], required=False)
    p.replace_any("chrome/common/chrome_constants.cc", [
        ('#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutablePath[] = FPL("thorium");',
         f'#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutablePath[] = FPL("{EXECUTABLE_NAME}");'),
        ('#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutablePath[] = FPL("chrome");',
         f'#elif BUILDFLAG(IS_POSIX)\nconst base::FilePath::CharType kBrowserProcessExecutablePath[] = FPL("{EXECUTABLE_NAME}");'),
    ], required=False)

    # User data directory: this also decides where native messaging manifests
    # are looked up (~/.config/<basename>/NativeMessagingHosts).
    p.replace_any("chrome/common/chrome_paths_linux.cc", [
        ('std::string data_dir_basename = "thorium";', f'std::string data_dir_basename = "{DATA_DIR_BASENAME}";'),
        ('std::string data_dir_basename = "chromium";', f'std::string data_dir_basename = "{DATA_DIR_BASENAME}";'),
    ])

    # Desktop file name.
    p.replace_any("chrome/common/channel_info_posix.cc", [
        ('return "thorium-browser.desktop";', f'return "{DESKTOP_NAME}";'),
        ('return "chromium-browser.desktop";', f'return "{DESKTOP_NAME}";'),
    ], required=False)

    # UI strings.
    for grd in ("chrome/app/chromium_strings.grd", "chrome/app/generated_resources.grd"):
        path = p.src / grd
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        original = text
        for variant in PRODUCT_VARIANTS:
            text = re.sub(
                r'(<message name="IDS_PRODUCT_NAME"[^>]*>\s*)' + variant + r'(\s*</message>)',
                r"\g<1>" + PRODUCT_NAME + r"\g<2>", text)
            text = re.sub(
                r'(<message name="IDS_SHORT_PRODUCT_NAME"[^>]*>\s*)' + variant + r'(\s*</message>)',
                r"\g<1>" + PRODUCT_SHORT + r"\g<2>", text)
            text = re.sub(
                r'(<message name="IDS_ABOUT_VERSION_COMPANY_NAME"[^>]*>\s*)Copyright[^<]*' + variant + r'[^<]*(\s*</message>)',
                r"\g<1>Copyright 2026 " + COMPANY + r"\g<2>", text)
        if text != original:
            if not p.check_only:
                path.write_text(text, encoding="utf-8")
            p.changed.append(grd)
            p.log(f"{grd}: product name strings")

    # Linux man page + desktop template.
    for rel in ("chrome/app/resources/manpage.1.in",):
        path = p.src / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        new = text
        for variant in PRODUCT_VARIANTS:
            new = new.replace(variant, PRODUCT_NAME)
        for variant in LOWER_VARIANTS:
            new = new.replace(variant, DATA_DIR_BASENAME)
        if new != text and not p.check_only:
            path.write_text(new, encoding="utf-8")
            p.changed.append(rel)
            p.log(f"{rel}: renamed")


def stage_icons(p: Patcher) -> None:
    print("==> [2/4] icons")
    pattern = re.compile(r"product_logo_(\d+)(_mono)?\.png$")
    targets = list(p.src.glob("chrome/app/theme/**/product_logo_*.png"))
    if not targets:
        raise PatchError("no product_logo_*.png found; unexpected source layout")
    for path in targets:
        match = pattern.search(path.name)
        if not match:
            continue
        size = int(match.group(1))
        data = p.render_icon(size)
        if p.check_only:
            p.changed.append(str(path.relative_to(p.src)) + " (would replace)")
            continue
        path.write_bytes(data)
        p.changed.append(str(path.relative_to(p.src)))
    p.log(f"replaced {len(targets)} icon file(s) from icon.svg")

    xpm = p.src / "chrome/app/theme/chromium/linux/product_logo_32.xpm"
    if xpm.exists() and shutil.which("convert"):
        png = Path("/tmp/rvpn-icon-32.png")
        png.write_bytes(p.render_icon(32))
        if not p.check_only:
            subprocess.run(["convert", str(png), str(xpm)], check=True, capture_output=True)
            p.changed.append(str(xpm.relative_to(p.src)))
        p.log("converted product_logo_32.xpm")


def stage_extension(p: Patcher) -> None:
    print("==> [3/4] built-in RVPN extension")
    source = REPO_ROOT / "browser" / "extension"
    files = sorted(f for f in source.rglob("*") if f.is_file())
    if not files:
        raise PatchError(f"extension sources missing in {source}")

    dest_rel = f"chrome/browser/resources/{EXTENSION_DIR_NAME}"
    p.copy_tree(source, dest_rel)

    grd_path = p.path("chrome/browser/resources/component_extension_resources.grd")
    text = grd_path.read_text(encoding="utf-8")
    if "IDR_RVPN_EXTENSION_MANIFEST" in text:
        p.skipped.append("component_extension_resources.grd (already patched)")
    else:
        includes = []
        marker = "    <includes>\n"
        if marker not in text:
            raise PatchError("component_extension_resources.grd: <includes> block not found")
        for f in files:
            rel = f.relative_to(source).as_posix()
            res_name = rel.upper().replace("/", "_").replace(".", "_").replace("-", "_")
            res_name = re.sub(r"[^A-Z0-9_]", "_", res_name)
            includes.append(
                f'      <include name="IDR_RVPN_EXTENSION_{res_name}" '
                f'file="{EXTENSION_DIR_NAME}/{rel}" '
                f'resource_path="{EXTENSION_DIR_NAME}/{rel}" type="BINDATA" />\n')
        text = text.replace(
            marker,
            marker
            + "\n      <!-- RVPN Browser built-in extension -->\n"
            + "".join(includes)
            + "\n",
            1)
        if not p.check_only:
            grd_path.write_text(text, encoding="utf-8")
        p.changed.append("chrome/browser/resources/component_extension_resources.grd")
        p.log(f"registered {len(files)} extension resource(s) in the grd")

    loader_rel = "chrome/browser/extensions/component_loader.cc"
    loader = p.path(loader_rel)
    text = loader.read_text(encoding="utf-8")
    if "AddRvpnBrowserExtension" in text:
        p.skipped.append(f"{loader_rel} (already patched)")
    else:
        if '#include "chrome/grit/component_extension_resources.h"' not in text:
            raise PatchError(f"{loader_rel}: component_extension_resources.h include not found")
        anchor = "void ComponentLoader::AddDefaultComponentExtensions(\n    bool skip_session_components) {"
        if anchor not in text:
            raise PatchError(f"{loader_rel}: AddDefaultComponentExtensions not found")
        helper = (
            "namespace {\n"
            "// RVPN Browser: the integration UI ships with the browser so that\n"
            "// RVPN mode works out of the box, without installing an extension.\n"
            f'constexpr char kRvpnExtensionDir[] = "{EXTENSION_DIR_NAME}";\n'
            "}  // namespace\n\n"
            "void ComponentLoader::AddRvpnBrowserExtension() {\n"
            "  Add(IDR_RVPN_EXTENSION_MANIFEST,\n"
            "      base::FilePath(FILE_PATH_LITERAL(kRvpnExtensionDir)));\n"
            "}\n\n")
        text = text.replace(anchor, helper + anchor, 1)

        call_anchor = "  if (!skip_session_components) {\n    AddWebStoreApp();"
        if call_anchor not in text:
            raise PatchError(f"{loader_rel}: session components block not found")
        text = text.replace(call_anchor,
                            "  AddRvpnBrowserExtension();\n\n" + call_anchor, 1)

        header_rel = "chrome/browser/extensions/component_loader.h"
        header = p.path(header_rel)
        htext = header.read_text(encoding="utf-8")
        h_anchor = "  // ComponentLoader methods:"
        if "AddRvpnBrowserExtension" not in htext:
            if h_anchor in htext:
                htext = htext.replace(
                    h_anchor,
                    "  // RVPN Browser: registers the built-in RVPN integration UI.\n"
                    "  void AddRvpnBrowserExtension();\n\n" + h_anchor, 1)
            else:
                # Fall back to inserting before the first private section.
                h_anchor2 = " private:"
                if h_anchor2 not in htext:
                    raise PatchError(f"{header_rel}: cannot find an insertion point")
                htext = htext.replace(
                    h_anchor2,
                    "  // RVPN Browser: registers the built-in RVPN integration UI.\n"
                    "  void AddRvpnBrowserExtension();\n\n" + h_anchor2, 1)
            if not p.check_only:
                header.write_text(htext, encoding="utf-8")
            p.changed.append(header_rel)
        if not p.check_only:
            loader.write_text(text, encoding="utf-8")
        p.changed.append(loader_rel)
        p.log("component_loader: built-in extension registered")


def stage_android(p: Patcher) -> None:
    print("==> [4/4] android")
    integration = REPO_ROOT / "android" / "chromium-integration"
    if not integration.exists():
        print("    !! android/chromium-integration is missing, skipping the Android stage")
        return

    # App name shown by the launcher and in the recents list.
    p.replace_any("chrome/android/java/res_chromium_base/values/channel_constants.xml", [
        ('<string name="app_name" translatable="false">Thorium</string>',
         f'<string name="app_name" translatable="false">{PRODUCT_NAME}</string>'),
        ('<string name="app_name" translatable="false">Chromium</string>',
         f'<string name="app_name" translatable="false">{PRODUCT_NAME}</string>'),
    ], required=False)

    # Launcher icons.
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            rel = f"chrome/android/java/res_chromium_base/mipmap-{density}/{name}"
            path = p.src / rel
            if not path.exists():
                continue
            data = p.render_icon(size)
            if not p.check_only:
                path.write_bytes(data)
            p.changed.append(rel)
    p.log("android launcher icons replaced")

    # RVPN Java sources + resources shipped inside the APK.
    java_src = integration / "java"
    java_dest = "chrome/android/java/src/org/chromium/chrome/browser/rvpn"
    if java_src.exists():
        p.copy_tree(java_src, java_dest)
    res_src = integration / "res"
    if res_src.exists():
        for item in sorted(res_src.iterdir()):
            p.copy_tree(item, f"chrome/android/java/res/{item.name}")
    print("    note: the C++ proxy hook and AndroidManifest entries are applied by")
    print("          browser/patches/android-rvpn-proxy.md (documented manual steps)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--chromium-src", default=os.environ.get("CR_DIR", str(Path.home() / "chromium" / "src")))
    parser.add_argument("--thorium-root", default=os.environ.get("THOR_DIR", str(Path.home() / "src" / "thorium")))
    parser.add_argument("--check", action="store_true", help="report what would change without touching the tree")
    parser.add_argument("--android", action="store_true", help="also apply the Android integration")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    src = Path(args.chromium_src).expanduser().resolve()
    if not (src / "chrome" / "common" / "chrome_paths.cc").exists():
        print(f"!! {src} does not look like a Chromium source tree", file=sys.stderr)
        return 1

    p = Patcher(src, check_only=args.check, verbose=not args.quiet)
    try:
        stage_branding(p)
        stage_icons(p)
        stage_extension(p)
        if args.android:
            stage_android(p)
    except PatchError as error:
        print(f"\n!! patch failed: {error}", file=sys.stderr)
        print("   The source tree may have changed upstream; update the anchor in this script.", file=sys.stderr)
        return 1

    print()
    print(f"==> {len(p.changed)} file(s) {'would be ' if args.check else ''}updated")
    for rel in p.changed:
        print("    " + rel)
    if p.skipped:
        print(f"==> {len(p.skipped)} item(s) skipped")
        for rel in p.skipped:
            print("    " + rel)
    return 0


if __name__ == "__main__":
    sys.exit(main())
