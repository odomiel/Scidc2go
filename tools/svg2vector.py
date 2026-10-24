#!/usr/bin/env python3
"""Wandelt die cburnett-Figuren (SVG, GPLv2+) in Android-VectorDrawables um.
Aufruf: svg2vector.py <svg-verzeichnis> <res/drawable-verzeichnis>"""
import sys, os
import xml.etree.ElementTree as ET

NS = '{http://www.w3.org/2000/svg}'
INHERIT = ['fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill-rule']

def color(c):
    if c is None or c == 'none':
        return None
    if c.startswith('#') and len(c) == 4:
        c = '#' + ''.join(ch * 2 for ch in c[1:])
    return c.upper()

def circle_path(cx, cy, r):
    return (f"M{cx - r},{cy}a{r},{r} 0,1 1,{2 * r},0a{r},{r} 0,1 1,{-2 * r},0z")

def emit(el, attrs, out):
    a = dict(attrs)
    for k in INHERIT:
        if el.get(k) is not None:
            a[k] = el.get(k)
    tag = el.tag.replace(NS, '')
    if tag in ('svg', 'g'):
        for ch in el:
            emit(ch, a, out)
        return
    if tag == 'path':
        d = el.get('d')
    elif tag == 'circle':
        d = circle_path(float(el.get('cx')), float(el.get('cy')), float(el.get('r')))
    else:
        return
    parts = [f'android:pathData="{d}"']
    fill = color(a.get('fill', '#000'))
    if fill:
        parts.append(f'android:fillColor="{fill}"')
        if a.get('fill-rule') == 'evenodd':
            parts.append('android:fillType="evenOdd"')
    stroke = color(a.get('stroke'))
    if stroke:
        parts.append(f'android:strokeColor="{stroke}"')
        parts.append(f'android:strokeWidth="{a.get("stroke-width", "1")}"')
        cap = a.get('stroke-linecap')
        if cap in ('round', 'square', 'butt'):
            parts.append(f'android:strokeLineCap="{cap}"')
        join = a.get('stroke-linejoin')
        if join in ('round', 'miter', 'bevel'):
            parts.append(f'android:strokeLineJoin="{join}"')
    out.append('    <path ' + '\n        '.join(parts) + '/>')

def main(src, dst):
    for f in sorted(os.listdir(src)):
        if not f.endswith('.svg'):
            continue
        root = ET.parse(os.path.join(src, f)).getroot()
        out = []
        emit(root, {}, out)
        name = 'piece_' + f[:-4].lower()
        with open(os.path.join(dst, name + '.xml'), 'w') as fh:
            fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
            fh.write('<!-- Schachfigur von Colin M.L. Burnett (cburnett), Lizenz GPLv2+ -->\n')
            fh.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                     '    android:width="45dp" android:height="45dp"\n'
                     '    android:viewportWidth="45" android:viewportHeight="45">\n')
            fh.write('\n'.join(out) + '\n</vector>\n')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
