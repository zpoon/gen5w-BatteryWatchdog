#!/usr/bin/env python3
"""Draw the launcher icon (a car battery with a heartbeat line, on the app's
green) at four densities: python3 tools/make_icon.py app/res
With --glyph <file.png>: only the battery and line, white on transparent, for
HMG-API's tools/mkicon.sh (the car's own tile style; see hmgapi-mod/README.md).
Needs Pillow. The PNGs are committed, so the build doesn't need this."""
from PIL import Image, ImageDraw
import sys, os
GREEN=(110,210,140,255); DARK=(18,20,24,255)
def icon(px,glyph=False):
    global DARK
    S=8; W=px*S                          # supersample, then downscale
    im=Image.new("RGBA",(W,W),(0,0,0,0)); d=ImageDraw.Draw(im)
    u=W/64                               # 64-unit grid, like the precondition icon
    if glyph: DARK=(255,255,255,255)     # the shape alone; mkicon.sh reads its transparency
    else: d.rounded_rectangle([0,0,W-1,W-1],radius=14*u,fill=GREEN)
    # battery body and terminals
    bx0,by0,bx1,by1=12*u,22*u,52*u,50*u
    d.rounded_rectangle([bx0,by0,bx1,by1],radius=4*u,outline=DARK,width=int(3.5*u))
    d.rounded_rectangle([18*u,17*u,25*u,22.5*u],radius=1.2*u,fill=DARK)
    d.rounded_rectangle([39*u,17*u,46*u,22.5*u],radius=1.2*u,fill=DARK)
    # heartbeat line through the battery
    pts=[(16,37),(24,37),(28,30),(32,44),(36,33),(39,37),(48,37)]
    d.line([(x*u,y*u) for x,y in pts],fill=DARK,width=int(3.2*u),joint="curve")
    for x,y in (pts[0],pts[-1]):
        r=1.6*u; d.ellipse([x*u-r,y*u-r,x*u+r,y*u+r],fill=DARK)
    return im.resize((px,px),Image.LANCZOS)
if sys.argv[1]=="--glyph":
    icon(256,glyph=True).save(sys.argv[2],optimize=True); sys.exit(0)
out=sys.argv[1]
for dens,px in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144)):
    p=os.path.join(out,"drawable-"+dens); os.makedirs(p,exist_ok=True)
    icon(px).save(os.path.join(p,"ic_launcher.png"),optimize=True)
