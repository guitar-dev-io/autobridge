#!/usr/bin/env python3
"""Builds design/index.html, AutoBridge's design page, from the code itself.

Everything on the page is read from the sources, so it cannot drift from what the app draws:
  - the colour tokens from common/src/main/java/dev/autobridge/design/DesignTokens.kt,
  - the browser start page's styles from app/src/main/java/dev/autobridge/browser/CarStartPage.kt,
  - the reference screenshots and their notes from design/reference/*/README.md.

Run it after changing a token or the start page:  python3 scripts/design-page.py
"""
import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKENS = os.path.join(ROOT, 'common/src/main/java/dev/autobridge/design/DesignTokens.kt')
START_PAGE = os.path.join(ROOT, 'app/src/main/java/dev/autobridge/browser/CarStartPage.kt')
REFERENCE = os.path.join(ROOT, 'design/reference')
OUT_DIR = os.path.join(ROOT, 'design')

GROUP_TITLES = {
    'Phone': 'Phone app',
    'Radio': 'Radio',
    'CarHome': 'Car home',
    'DashboardArt': 'Car home card art',
    'BrowserDark': 'Browser, dark',
    'BrowserLight': 'Browser, light',
    'Keyboard': 'Car keyboard',
    'MultiApp': 'Multi-app',
    'DesktopRail': 'Desktop browser rail',
}


def parse_tokens():
    """[(group, doc, [(name, argb, doc)], palettes)] in file order."""
    src = open(TOKENS, encoding='utf-8').read()
    groups = []
    for m in re.finditer(r'    /\*\* (.*?) \*/\n    object (\w+) \{\n([\s\S]*?)\n    \}\n', src):
        doc, name, body = m.groups()
        tokens, note = [], None
        for line in body.split('\n'):
            d = re.match(r'\s*/\*\* (.*?) \*/', line)
            if d:
                note = d.group(1)
                continue
            t = re.match(r'\s*const val (\w+) = 0x([0-9A-Fa-f]{8})', line)
            if t:
                tokens.append((t.group(1), int(t.group(2), 16), note))
                note = None
        palettes = [(p, [int(h, 16) for h in re.findall(r'0x([0-9A-Fa-f]{8})', vals)])
                    for p, vals in re.findall(r'val (\w+) = intArrayOf\(([\s\S]*?)\n\s*\)', body)]
        groups.append((name, doc, tokens, palettes))
    return groups


def css(argb):
    a, rgb = argb >> 24, argb & 0xFFFFFF
    if a == 0xFF:
        return '#%06X' % rgb
    return 'rgba(%d,%d,%d,%.2f)' % (rgb >> 16, (rgb >> 8) & 0xFF, rgb & 0xFF, a / 255)


def hex_label(argb):
    a = argb >> 24
    return ('#%06X' % (argb & 0xFFFFFF)) + ('' if a == 0xFF else ' · %d%%' % round(a * 100 / 255))


def light(argb):
    r, g, b = (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF
    alpha = (argb >> 24) / 255
    lum = (0.299 * r + 0.587 * g + 0.114 * b) * alpha + 18 * (1 - alpha)
    return lum > 150


def token_value(groups, group, name):
    for g, _, tokens, _ in groups:
        if g == group:
            for n, v, _ in tokens:
                if n == name:
                    return v
    raise KeyError(f'{group}.{name}')


def start_page_sample(groups):
    """The browser start page with sample data, styled by CarStartPage's own CSS."""
    src = open(START_PAGE, encoding='utf-8').read()
    names = dict(re.findall(r'private val (\w+) = cssColor\(AutoBridgeDesign\.(\w+)\)', src))
    design = dict(re.findall(r'const val (\w+) = DesignTokens\.Phone\.(\w+)',
                             open(os.path.join(ROOT, 'app/src/main/java/dev/autobridge/ui/AutoBridgeDesign.kt'), encoding='utf-8').read()))

    def phone(design_name):
        return token_value(groups, 'Phone', design.get(design_name, design_name))

    def resolve(text):
        def tint(m):
            v = phone(m.group(1))
            return 'rgba(%d,%d,%d,%s)' % ((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, m.group(2).rstrip('f'))
        text = re.sub(r'\$\{cssColor\(AutoBridgeDesign\.(\w+), ([0-9.]+f)\)\}', tint, text)
        for local, design_name in names.items():
            text = re.sub(r'\$' + local + r'\b', css(phone(design_name)), text)
        return text

    def raw(name):
        return resolve(re.search(r'private (?:const )?val ' + name + r' = """([\s\S]*?)"""', src).group(1))

    tiles = [('YouTube Music', 'music.youtube.com', 'YM', 0), ('Netflix', 'netflix.com', 'N', 2),
             ('YouTube', 'm.youtube.com', 'YT', 1), ('Google', 'google.com', 'G', 3), ('Google Maps', 'maps.google.com', 'GM', 6)]
    palette = next(p for g, _, _, ps in groups for n, p in ps if n == 'TILE_PALETTE')
    tile_html = ''.join(
        f'<a class="tile" href="#"><span class="badge" style="background:{css(palette[i])}">{b}</span>'
        f'<span class="t">{t}</span><span class="h">{h}</span></a>' for t, h, b, i in tiles)
    body = (
        '<header><h1>AutoBridge Browser</h1><p class="sub">เข้าเว็บที่ใช้บ่อยได้ในแตะเดียว</p></header>'
        f'<form class="search" onsubmit="return false">{raw("SEARCH_ICON")}'
        '<input type="search" placeholder="ค้นหาหรือป้อนที่อยู่เว็บ">'
        f'<button type="submit">{raw("ARROW_ICON")}</button></form>'
        '<section class="card"><div class="card-head"><div class="card-icon">☕</div><h2>เลี้ยงค่าขนมผู้พัฒนา</h2></div>'
        '<p>ถ้า AutoBridge ทำให้การขับรถสนุกขึ้น ค่าขนมเล็กๆ น้อยๆ ช่วยให้มีอัปเดตใหม่ๆ ต่อไปได้ ขอบคุณมากครับ ☕✨</p>'
        f'<div class="actions"><a class="pill" href="#">สนับสนุนผู้พัฒนา{raw("EXTERNAL_ICON")}</a>'
        '<a class="pill ghost" href="#">เปิดเบราว์เซอร์</a></div></section>'
        f'<h3>ลิงก์ด่วน</h3><div class="tiles">{tile_html}</div>'
    )
    return ('<!doctype html><html lang="th"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Start page sample</title>'
            f'<style>{raw("CSS")}</style></head><body><main>{body}</main></body></html>')


def reference_sets():
    """[(version, [(file, screen, note)])], newest first."""
    sets = []
    for version in sorted(os.listdir(REFERENCE), reverse=True):
        readme = os.path.join(REFERENCE, version, 'README.md')
        if not os.path.exists(readme):
            continue
        rows = re.findall(r'^\| `([^`]+)` \| ([^|]+) \| ([^|]+) \|$', open(readme, encoding='utf-8').read(), re.M)
        sets.append((version, [(f, s.strip(), n.strip()) for f, s, n in rows]))
    return sets


def page(groups):
    p = {n: css(v) for n, v, _ in next(t for g, _, t, _ in groups if g == 'Phone')}
    total = sum(len(t) for _, _, t, _ in groups)
    nav = ''.join(f'<a href="#{g}">{html.escape(GROUP_TITLES.get(g, g))}</a>' for g, _, _, _ in groups)
    sections = []
    for g, doc, tokens, palettes in groups:
        cards = ''.join(
            f'<div class="sw"><div class="chip{" lt" if light(v) else ""}" style="background:{css(v)}"></div>'
            f'<div class="meta"><code>{n}</code><span>{hex_label(v)}</span>'
            + (f'<em>{html.escape(note)}</em>' if note else '') + '</div></div>'
            for n, v, note in tokens)
        for name, values in palettes:
            strip = ''.join(f'<i style="background:{css(v)}" title="{hex_label(v)}"></i>' for v in values)
            cards += f'<div class="sw wide"><div class="strip">{strip}</div><div class="meta"><code>{name}</code><span>{len(values)} colours</span></div></div>'
        sections.append(
            f'<section id="{g}"><div class="head"><h2>{html.escape(GROUP_TITLES.get(g, g))}</h2>'
            f'<code class="path">DesignTokens.{g}</code></div><p class="doc">{html.escape(doc)}</p>'
            f'<div class="grid">{cards}</div></section>')
    refs = ''
    for version, rows in reference_sets():
        shots = ''.join(
            f'<figure><img loading="lazy" src="reference/{version}/{f}" alt="{html.escape(s)}">'
            f'<figcaption><strong>{html.escape(s)}</strong>{html.escape(n)}</figcaption></figure>' for f, s, n in rows)
        refs += f'<h3>{version}</h3><div class="shots">{shots}</div>'
    return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AutoBridge Design</title>
<style>
*{{box-sizing:border-box}}
body{{margin:0;background:{p['INK']};color:{p['TEXT']};font:15px/1.5 Roboto,"Noto Sans Thai","Noto Sans",system-ui,sans-serif}}
a{{color:{p['ACCENT']}}}
.top{{position:sticky;top:0;z-index:2;background:{p['INK']}F2;border-bottom:1px solid {p['HAIRLINE']};backdrop-filter:blur(8px)}}
.top .in{{max-width:1180px;margin:0 auto;padding:14px 20px;display:flex;gap:18px;align-items:center;flex-wrap:wrap}}
.top b{{font-size:18px;margin-right:auto}}
.top nav{{display:flex;gap:6px;flex-wrap:wrap}}
.top nav a{{text-decoration:none;color:{p['TEXT_MUTED']};padding:6px 12px;border-radius:999px;background:{p['SURFACE']};border:1px solid {p['HAIRLINE']};font-size:13px}}
.top nav a:hover{{color:{p['TEXT']}}}
main{{max-width:1180px;margin:0 auto;padding:28px 20px 80px}}
h1{{font-size:34px;margin:0 0 6px}}
.lead{{color:{p['TEXT_MUTED']};margin:0 0 30px;max-width:760px}}
h2{{font-size:22px;margin:0}}
h3{{font-size:16px;color:{p['TEXT_MUTED']};letter-spacing:1px;margin:26px 0 12px}}
section{{margin:0 0 40px;scroll-margin-top:150px}}
.head{{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}}
.path{{color:{p['TEXT_MUTED']};font-size:13px}}
.doc{{color:{p['TEXT_MUTED']};margin:4px 0 14px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}}
.sw{{background:{p['SURFACE']};border:1px solid {p['HAIRLINE']};border-radius:16px;overflow:hidden}}
.sw.wide{{grid-column:span 2}}
.chip{{height:64px;border-bottom:1px solid {p['HAIRLINE']};background-image:none}}
.strip{{display:flex;height:64px}} .strip i{{flex:1}}
.meta{{padding:10px 12px;display:flex;flex-direction:column;gap:2px;min-width:0}}
.meta code{{font-size:12.5px;color:{p['TEXT']};overflow:hidden;text-overflow:ellipsis}}
.meta span{{font-size:12px;color:{p['TEXT_MUTED']};font-family:ui-monospace,monospace}}
.meta em{{font-size:12px;color:{p['TEXT_MUTED']};font-style:normal}}
.frame{{border:1px solid {p['HAIRLINE']};border-radius:20px;overflow:hidden;background:{p['SURFACE']}}}
.frame iframe{{display:block;width:100%;height:560px;border:0}}
.shots{{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px}}
figure{{margin:0;background:{p['SURFACE']};border:1px solid {p['HAIRLINE']};border-radius:16px;overflow:hidden}}
figure img{{display:block;width:100%;height:auto;background:#000}}
figcaption{{padding:10px 12px;font-size:12.5px;color:{p['TEXT_MUTED']}}}
figcaption strong{{display:block;color:{p['TEXT']};font-size:14px}}
@media (max-width:520px){{.sw.wide{{grid-column:span 1}}}}
</style></head>
<body>
<div class="top"><div class="in"><b>AutoBridge Design</b><nav>{nav}<a href="#start-page">Start page</a><a href="#screens">Screens</a></nav></div></div>
<main>
<h1>AutoBridge Design</h1>
<p class="lead">Every colour the app draws with ({total} tokens), the browser start page and the reference screens, in one place.
Generated from <code>DesignTokens.kt</code>, <code>CarStartPage.kt</code> and <code>design/reference</code> by
<code>scripts/design-page.py</code>; change the code, run the script, and this page follows.</p>
{''.join(sections)}
<section id="start-page"><div class="head"><h2>Browser start page</h2><code class="path">CarStartPage</code></div>
<p class="doc">The page the car browser and the desktop browsers (Chrome, Brave) open on. Sample data; styles are the page's own.</p>
<div class="frame"><iframe src="start-page.html" title="Start page sample"></iframe></div></section>
<section id="screens"><div class="head"><h2>Reference screens</h2><code class="path">design/reference</code></div>
<p class="doc">The shipped look new and reworked screens match.</p>{refs}</section>
</main></body></html>
'''


def main():
    groups = parse_tokens()
    if not groups:
        sys.exit('no token groups found in ' + TOKENS)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, 'index.html'), 'w', encoding='utf-8') as f:
        f.write(page(groups))
    with open(os.path.join(OUT_DIR, 'start-page.html'), 'w', encoding='utf-8') as f:
        f.write(start_page_sample(groups))
    print('wrote design/index.html and design/start-page.html (%d tokens in %d groups)'
          % (sum(len(t) for _, _, t, _ in groups), len(groups)))


if __name__ == '__main__':
    main()
