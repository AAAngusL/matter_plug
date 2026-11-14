#!/usr/bin/env python3
"""Finds duplicated code in src/main Kotlin: runs of >= MIN_LINES consecutive lines that are identical after
trimming whitespace (comments, blank lines, imports and lone braces are ignored). A clone is reported once, as the
longest run. Rank = share of the run that is real code (not lone braces/parens): 1.0 is a verbatim copy.
Run: python3 tools/dryer.py [min_lines]"""
import sys, pathlib, collections

MIN_LINES = int(sys.argv[1]) if len(sys.argv) > 1 else 5
NOISE = {"{", "}", ")", "(", "),", "})", "}))", "else {", "} else {", "try {", "}"}

def load(path):
    out = []
    for n, raw in enumerate(path.read_text().splitlines(), 1):
        t = raw.strip()
        if not t or t.startswith(("//", "/*", "*", "import ", "package ")):
            continue
        out.append((n, t))
    return out

files = {p: load(p) for p in sorted(pathlib.Path("app/src/main/java").rglob("*.kt"))}
index = collections.defaultdict(list)          # window of MIN_LINES lines -> [(file, start index)]
for p, lines in files.items():
    for i in range(len(lines) - MIN_LINES + 1):
        index[tuple(t for _, t in lines[i:i + MIN_LINES])].append((p, i))

seen, clones = set(), []
for key, spots in index.items():
    if len(spots) < 2:
        continue
    for a in range(len(spots)):
        for b in range(a + 1, len(spots)):
            (pa, ia), (pb, ib) = spots[a], spots[b]
            if (pa, ia, pb, ib) in seen:
                continue
            if pa == pb and abs(ia - ib) < MIN_LINES:      # overlapping with itself
                continue
            n = MIN_LINES
            while ia + n < len(files[pa]) and ib + n < len(files[pb]) and files[pa][ia + n][1] == files[pb][ib + n][1]:
                n += 1
            for k in range(n - MIN_LINES + 1):            # mark every window inside this run as reported
                seen.add((pa, ia + k, pb, ib + k))
            body = [t for _, t in files[pa][ia:ia + n]]
            rank = sum(t not in NOISE for t in body) / n
            clones.append((rank, n, pa, files[pa][ia][0], pb, files[pb][ib][0]))

clones.sort(key=lambda c: (-c[0] * c[1]))
print(f"{len(clones)} clone runs of >= {MIN_LINES} lines")
for rank, n, pa, la, pb, lb in clones:
    print(f"rank {rank:.2f}  {n:3d} lines  {pa.name}:{la}  <->  {pb.name}:{lb}")
