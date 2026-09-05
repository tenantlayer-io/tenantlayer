#!/usr/bin/env python3
"""
Generates the architecture diagram as light and dark SVGs from one definition,
so the two can never drift. Run: python3 docs/images/generate.py
"""

LIGHT = dict(
    bg="#ffffff", box="#ffffff", box_alt="#fafafa", border="#d4d4d8",
    fg="#18181b", muted="#52525b", subtle="#71717a", accent="#0f766e",
    accent_bg="#f0fdfa", accent_border="#5eead4", arrow="#a1a1aa",
    danger="#b91c1c", code="#3f3f46", code_bg="#f4f4f5",
)
DARK = dict(
    bg="#0b0c0e", box="#131417", box_alt="#17191d", border="#34373d",
    fg="#ededee", muted="#a1a1aa", subtle="#8b8b93", accent="#2dd4bf",
    accent_bg="#0f2e2b", accent_border="#155e56", arrow="#52525b",
    danger="#f87171", code="#c9c9cf", code_bg="#1a1c20",
)

W, H = 1040, 700
BX, BW = 40, 520          # box column
GX = 600                  # annotation gutter


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def box(c, y, h, title, lines=None, *, dashed=False, emphasis=False, mono=False):
    fill = c["accent_bg"] if emphasis else c["box"]
    stroke = c["accent_border"] if emphasis else c["border"]
    dash = ' stroke-dasharray="5 4"' if dashed else ""
    out = [
        f'<rect x="{BX}" y="{y}" width="{BW}" height="{h}" rx="8" '
        f'fill="{fill}" stroke="{stroke}" stroke-width="1.25"{dash}/>',
        f'<text x="{BX + 18}" y="{y + 25}" font-family="ui-sans-serif,system-ui,sans-serif" '
        f'font-size="15" font-weight="600" fill="{c["fg"]}">{esc(title)}</text>',
    ]
    for i, line in enumerate(lines or []):
        fam = "ui-monospace,SFMono-Regular,Menlo,monospace" if mono else "ui-sans-serif,system-ui,sans-serif"
        size = 11.5 if mono else 12.5
        fill_c = c["code"] if mono else c["muted"]
        out.append(
            f'<text x="{BX + 18}" y="{y + 46 + i * 17}" font-family="{fam}" xml:space="preserve" '
            f'font-size="{size}" fill="{fill_c}">{esc(line)}</text>'
        )
    return "\n".join(out)


def arrow(c, y1, y2):
    x = BX + BW / 2
    return (
        f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2 - 7}" stroke="{c["arrow"]}" '
        f'stroke-width="1.5" marker-end="url(#a)"/>'
    )


def note(c, y, lines, *, colour=None):
    col = colour or c["subtle"]
    out = [f'<line x1="{GX - 16}" y1="{y - 12}" x2="{GX - 16}" y2="{y + len(lines) * 16 - 4}" '
           f'stroke="{c["border"]}" stroke-width="2"/>']
    for i, line in enumerate(lines):
        weight = "600" if i == 0 else "400"
        out.append(
            f'<text x="{GX}" y="{y + i * 16}" font-family="ui-sans-serif,system-ui,sans-serif" '
            f'font-size="12" font-weight="{weight}" fill="{col}">{esc(line)}</text>'
        )
    return "\n".join(out)


def render(c):
    p = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
        f'viewBox="0 0 {W} {H}" role="img" aria-label="How a request flows through TenantLayer">',
        f'<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" '
        f'markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="{c["arrow"]}"/></marker></defs>',
        f'<rect width="{W}" height="{H}" fill="{c["bg"]}"/>',
    ]

    # 1. request
    p.append(box(c, 20, 40, "HTTP request"))
    p.append(arrow(c, 60, 88))

    # 2. spring security
    p.append(box(c, 88, 58, "Spring Security filter chain",
                 ["authenticates — only when JWT resolution or membership is enabled"],
                 dashed=True))
    p.append(note(c, 108, ["Optional", "Absent for header or", "subdomain resolution"]))
    p.append(arrow(c, 146, 182))

    # 3. tenant filter
    p.append(box(c, 182, 96, "TenantFilter", [
        "1.  resolve   which tenant does this request claim to be?",
        "2.  verify    is the caller entitled to that tenant?",
        "3.  bind      TenantContext.enter(scope)",
    ]))
    p.append(note(c, 200, ["Fails closed here", "400 — no tenant resolved",
                           "403 — not a member"], colour=c["danger"]))
    p.append(arrow(c, 278, 314))

    # 4. your code
    p.append(box(c, 314, 84, "Your controller · service · repository", [
        "no tenant parameter · no header read · no findByTenantId",
        "written as though the application had one customer",
    ]))
    p.append(note(c, 336, ["Knows nothing", "about tenancy", "Nothing to forget"]))
    p.append(arrow(c, 398, 434))

    # 5. datasource
    p.append(box(c, 434, 66, "TenantAwareDataSource", [
        "select set_config('tenantlayer.tenant', ?, false)",
    ], mono=True))
    p.append(note(c, 452, ["On every checkout", "Never 'reset on return' —", "a missed reset leaks"]))
    p.append(arrow(c, 500, 552))

    # 6. postgres
    p.append(box(c, 552, 86, "Postgres — row-level security", [
        "using (tenant_id = nullif(",
        "          current_setting('tenantlayer.tenant', true), ''))",
    ], emphasis=True, mono=True))
    p.append(note(c, 574, ["The only thing that enforces",
                           "No tenant → no rows,", "never all rows"], colour=c["accent"]))

    # footer
    p.append(
        f'<text x="{BX}" y="{H - 18}" font-family="ui-sans-serif,system-ui,sans-serif" '
        f'font-size="12" fill="{c["subtle"]}">Unwound afterwards in a finally block — '
        f'container threads are pooled, and a request that leaves its tenant behind hands it to the next one.</text>'
    )
    p.append("</svg>")
    return "\n".join(p)


for name, palette in (("architecture-light.svg", LIGHT), ("architecture-dark.svg", DARK)):
    with open(name, "w") as f:
        f.write(render(palette))
    print(f"  wrote {name}")
