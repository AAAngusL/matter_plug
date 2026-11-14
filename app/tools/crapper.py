#!/usr/bin/env python3
"""CRAP score per method from the JaCoCo XML report: crap = c^2 * (1 - cov)^3 + c, c = cyclomatic complexity.
Run: ./gradlew :app:createDebugUnitTestCoverageReport && python3 tools/crapper.py [threshold]"""
import sys, xml.etree.ElementTree as ET

REPORT = "app/build/reports/coverage/test/debug/report.xml"
threshold = float(sys.argv[1]) if len(sys.argv) > 1 else 10.0

def counter(m, kind):
    for c in m.findall("counter"):
        if c.get("type") == kind:
            return int(c.get("missed")), int(c.get("covered"))
    return 0, 0

rows = []
for cls in ET.parse(REPORT).getroot().iter("class"):
    for m in cls.findall("method"):
        cm, cc = counter(m, "COMPLEXITY")
        im, ic = counter(m, "INSTRUCTION")
        c = cm + cc
        if c == 0 or im + ic == 0:
            continue
        cov = ic / (im + ic)
        crap = c * c * (1 - cov) ** 3 + c
        rows.append((crap, c, cov, cls.get("name").replace("/", "."), m.get("name")))

rows.sort(reverse=True)
over = [r for r in rows if r[0] > threshold]
print(f"{len(over)} of {len(rows)} methods above CRAP {threshold:g}")
for crap, c, cov, cls, name in over:
    print(f"{crap:8.1f}  complexity {c:3d}  coverage {cov*100:5.1f}%  {cls}.{name}")
