#!/usr/bin/env python3
import os
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_NAME = os.environ.get("APP_NAME", "").strip()
APP_KEY = os.environ.get("APP_KEY", "").strip()
APPLICATION_ID = os.environ.get("APPLICATION_ID", "").strip()
LOGO_URL = os.environ.get("LOGO_URL", "").strip()

if not APP_NAME:
    sys.exit("APP_NAME is missing")
if not re.fullmatch(r"[a-z0-9_]+", APP_KEY):
    sys.exit("APP_KEY must use lowercase letters, numbers, underscore")
if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", APPLICATION_ID):
    sys.exit("APPLICATION_ID is invalid")

# applicationId only. Keep namespace / Java package com.probrowser.app.
gradle = ROOT / "app" / "build.gradle"
text = gradle.read_text(encoding="utf-8")
text, n = re.subn(r"applicationId\s+['\"][^'\"]+['\"]", f"applicationId '{APPLICATION_ID}'", text, count=1)
if n != 1:
    sys.exit("Could not replace applicationId")
gradle.write_text(text, encoding="utf-8")

# APP_KEY chooses apps/<APP_KEY> in the shared Realtime Database.
config = ROOT / "app" / "src" / "main" / "java" / "com" / "probrowser" / "app" / "AppConfig.java"
text = config.read_text(encoding="utf-8")
text, n = re.subn(r'public static final String APP_KEY\s*=\s*"[^"]*";', f'public static final String APP_KEY = "{APP_KEY}";', text, count=1)
if n != 1:
    sys.exit("Could not replace APP_KEY")
config.write_text(text, encoding="utf-8")

# App label
strings = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
text = strings.read_text(encoding="utf-8")
escaped = APP_NAME.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
text, n = re.subn(r'<string name="app_name">.*?</string>', f'<string name="app_name">{escaped}</string>', text, count=1)
if n != 1:
    sys.exit("Could not replace app_name")
strings.write_text(text, encoding="utf-8")

# Optional logo. Pillow is installed by the ProBrowser workflow.
if LOGO_URL:
    try:
        from PIL import Image, ImageOps
        from io import BytesIO
        req = urllib.request.Request(LOGO_URL, headers={"User-Agent":"ProBrowserBuilder/1.0"})
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
        if len(raw) > 4 * 1024 * 1024:
            sys.exit("Logo is larger than 4 MB")
        image = Image.open(BytesIO(raw)).convert("RGBA")
        image = ImageOps.contain(image, (512, 512))
        canvas = Image.new("RGBA", (512, 512), (255,255,255,0))
        canvas.alpha_composite(image, ((512-image.width)//2, (512-image.height)//2))
        drawable = ROOT / "app" / "src" / "main" / "res" / "drawable"
        old_xml = drawable / "ic_app.xml"
        if old_xml.exists():
            old_xml.unlink()
        canvas.save(drawable / "ic_app.png", "PNG")
    except Exception as exc:
        sys.exit(f"Logo processing failed: {exc}")

print("Configured ProBrowser build")
print("APP_NAME=", APP_NAME)
print("APP_KEY=", APP_KEY)
print("APPLICATION_ID=", APPLICATION_ID)
