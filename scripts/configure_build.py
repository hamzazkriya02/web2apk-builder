import html, os, pathlib, re
from PIL import Image, ImageOps

name=os.environ["APP_NAME"].strip()[:30]
url=os.environ["WEBSITE_URL"].strip()
if not re.match(r"^https?://",url): raise SystemExit("Invalid URL")
values=pathlib.Path("app/src/main/res/values/strings.xml")
values.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n<string name="app_name">%s</string>\n<string name="start_url">%s</string>\n</resources>\n'%(html.escape(name),html.escape(url)),encoding="utf-8")
logo=pathlib.Path(os.environ["LOGO_PATH"])
if not logo.is_file() or logo.stat().st_size>4*1024*1024: raise SystemExit("Invalid logo")
target=pathlib.Path("app/src/main/res/drawable/app_logo.png")
with Image.open(logo) as source:
    source.thumbnail((512,512))
    canvas=Image.new("RGBA",(512,512),(255,255,255,0))
    canvas.alpha_composite(source.convert("RGBA"),((512-source.width)//2,(512-source.height)//2))
    canvas.save(target,"PNG",optimize=True)
xml=pathlib.Path("app/src/main/res/drawable/app_logo.xml")
if xml.exists(): xml.unlink()
build_id=re.sub(r"[^a-f0-9]","",os.environ["BUILD_ID"].lower())[:20]
gradle=pathlib.Path("app/build.gradle")
text=gradle.read_text(encoding="utf-8")
text=re.sub(r"applicationId 'com\.web2apk\.[^']+'",f"applicationId 'com.web2apk.b{build_id}'",text)
gradle.write_text(text,encoding="utf-8")
