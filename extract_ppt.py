# -*- coding: utf-8 -*-
import zipfile
import re
import xml.etree.ElementTree as ET

src = r"c:\Users\Administrator\Desktop\ppt\人工智能学院 苗圃突击队 于渼琦 答辩PPT(2)(2).pptx"
out = r"c:\Users\Administrator\Desktop\app\ppt_text_extract.txt"
ns = {"a": "http://schemas.openxmlformats.org/drawingml/2006/main"}

with zipfile.ZipFile(src) as z, open(out, "w", encoding="utf-8") as f:
    slides = sorted(
        [n for n in z.namelist() if re.match(r"ppt/slides/slide\d+\.xml$", n)],
        key=lambda x: int(re.search(r"\d+", x).group()),
    )
    media = [n for n in z.namelist() if n.startswith("ppt/media/")]
    notes = [n for n in z.namelist() if "notesSlides" in n]
    f.write(f"SLIDE_COUNT={len(slides)}\n")
    f.write(f"MEDIA={len(media)}\n")
    f.write(f"NOTES={len(notes)}\n\n")
    for s in slides:
        root = ET.fromstring(z.read(s))
        texts = []
        for t in root.findall(".//a:t", ns):
            if t.text:
                cleaned = t.text.replace("\u200b", "").replace("\xa0", " ")
                if cleaned.strip():
                    texts.append(cleaned)
        f.write(f"==== {s} ====\n")
        f.write(" | ".join(texts))
        f.write("\n\n")

print("OK", out)
