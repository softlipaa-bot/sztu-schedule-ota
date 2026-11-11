# -*- coding: utf-8 -*-
from pathlib import Path
from rembg import remove, new_session
from PIL import Image
import numpy as np

src = Path(
    r"C:\Users\Administrator\.cursor\projects\c-Users-Administrator-Desktop-app\assets"
    r"\c__Users_Administrator_AppData_Roaming_Cursor_User_workspaceStorage_empty-window_images_de80f0f365f389d39ff7b1923a3e60f0-e1702bca-d759-40f9-886a-96df60a94e11.png"
)
out_dir = Path(r"c:\Users\Administrator\Desktop\ppt")
out_t = out_dir / "team_cutout_transparent.png"
out_w = out_dir / "team_cutout_white.png"

session = new_session("bria-rmbg")
img = Image.open(src).convert("RGBA")
cutout = remove(img, session=session)

arr = np.array(cutout)
# harden semi-transparent fringe a bit (keep hair soft): boost mid alphas
a = arr[:, :, 3].astype(np.float32)
a = np.where(a > 40, np.minimum(255, a * 1.15), a)
arr[:, :, 3] = a.astype(np.uint8)

ys, xs = np.where(arr[:, :, 3] > 12)
pad = 12
box = (
    max(0, int(xs.min()) - pad),
    max(0, int(ys.min()) - pad),
    min(arr.shape[1], int(xs.max()) + pad + 1),
    min(arr.shape[0], int(ys.max()) + pad + 1),
)
final = Image.fromarray(arr).crop(box)
final.save(out_t)

white = Image.new("RGBA", final.size, (255, 255, 255, 255))
Image.alpha_composite(white, final).convert("RGB").save(out_w, quality=95)
print("OK", final.size)
