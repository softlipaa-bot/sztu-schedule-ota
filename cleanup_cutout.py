# -*- coding: utf-8 -*-
from pathlib import Path
import numpy as np
from PIL import Image

src = Path(r"c:\Users\Administrator\Desktop\ppt\team_cutout_transparent.png")
out_t = Path(r"c:\Users\Administrator\Desktop\ppt\team_cutout_transparent.png")
out_w = Path(r"c:\Users\Administrator\Desktop\ppt\team_cutout_white.png")

img = Image.open(src).convert("RGBA")
arr = np.array(img)
h, w = arr.shape[:2]
alpha = arr[:, :, 3] > 20

# From bottom, find first row that is mostly a continuous wide block (banner)
# Skip sparse chair scraps
best_y = h - 1
for y in range(h - 1, int(h * 0.55), -1):
    row = alpha[y]
    if row.sum() < w * 0.35:
        continue
    # check continuity of longest run
    runs = 0
    longest = 0
    cur = 0
    for v in row:
        if v:
            cur += 1
            longest = max(longest, cur)
        else:
            cur = 0
    if longest >= w * 0.40:
        best_y = y
        break

# Clear everything below banner bottom (+ small margin)
cut = min(h - 1, best_y + 6)
arr[cut + 1 :, :, 3] = 0

# Also clear any remaining side scraps above cut in lower area
y0 = int(h * 0.70)
arr[y0:cut + 1, : int(w * 0.08), 3] = 0
arr[y0:cut + 1, int(w * 0.92) :, 3] = 0

ys, xs = np.where(arr[:, :, 3] > 8)
pad = 8
box = (
    max(0, int(xs.min()) - pad),
    max(0, int(ys.min()) - pad),
    min(w, int(xs.max()) + pad + 1),
    min(h, int(ys.max()) + pad + 1),
)
final = Image.fromarray(arr).crop(box)
final.save(out_t)
white = Image.new("RGBA", final.size, (255, 255, 255, 255))
Image.alpha_composite(white, final).convert("RGB").save(out_w, quality=95)
print("banner_bottom_row", best_y, "size", final.size)
