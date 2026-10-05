#!/usr/bin/env python3
"""Сборка презентационного PDF из docs/PRESENTATION_DOCUMENT.md.

Зависимости: pip install markdown weasyprint
Запуск:    python3 tools/build_presentation_pdf.py
Результат: docs/RWMS_Presentation.pdf
"""
import pathlib
import markdown
from weasyprint import HTML

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "PRESENTATION_DOCUMENT.md"
OUT = ROOT / "docs" / "RWMS_Presentation.pdf"

CSS = """
@page { size: A4; margin: 22mm 18mm 20mm 18mm;
  @bottom-center { content: counter(page) " / " counter(pages); font-size: 9pt; color:#777; } }
body { font-family: 'DejaVu Sans', sans-serif; font-size: 10.5pt; line-height: 1.5; color: #1a1a2e; }
h1 { font-size: 20pt; color: #0f3460; border-bottom: 3px solid #0f3460; padding-bottom: 6pt; }
h2 { font-size: 14.5pt; color: #0f3460; margin-top: 22pt; border-bottom: 1px solid #cfd8e3; padding-bottom: 3pt; page-break-after: avoid; }
h3 { font-size: 12pt; color: #16213e; page-break-after: avoid; }
table { border-collapse: collapse; width: 100%; margin: 10pt 0; font-size: 9.5pt; page-break-inside: auto; }
th { background: #0f3460; color: white; text-align: left; padding: 5pt 7pt; }
td { border: 1px solid #cfd8e3; padding: 4pt 7pt; vertical-align: top; }
tr:nth-child(even) td { background: #f4f7fb; }
pre { background: #f0f3f8; border: 1px solid #d6dde8; border-radius: 4pt; padding: 8pt 10pt; font-size: 8.5pt; line-height: 1.35; page-break-inside: avoid; }
code { font-family: 'DejaVu Sans Mono', monospace; }
blockquote { border-left: 4px solid #e94560; margin: 10pt 0; padding: 6pt 12pt; background: #fdf2f4; font-style: italic; }
hr { border: none; border-top: 1px solid #cfd8e3; margin: 16pt 0; }
strong { color: #16213e; }
"""

def main() -> None:
    body = markdown.markdown(SRC.read_text(encoding="utf-8"),
                             extensions=["tables", "fenced_code"])
    html = (f"<html><head><meta charset='utf-8'>"
            f"<style>{CSS}</style></head><body>{body}</body></html>")
    HTML(string=html).write_pdf(str(OUT))
    print(f"OK: {OUT}")

if __name__ == "__main__":
    main()
