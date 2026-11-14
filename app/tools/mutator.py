#!/usr/bin/env python3
"""Small mutation tester for the pure-logic Kotlin files. For each site (a comparison, boolean operator, boolean
literal or +/- on one line) it flips the operator, runs the unit tests, and restores the file. A mutant is KILLED when
the tests fail (or the build breaks), SURVIVED when they still pass: a survivor is a behaviour no test pins down.
Run: python3 tools/mutator.py [file ...]      (default: the files in TARGETS). Needs ANDROID_HOME.
Writes survivors to tools/mutation-survivors.txt."""
import re, subprocess, sys, pathlib, shutil

ROOT = pathlib.Path("app/src/main/java/app/matterplug")
TARGETS = ["guard/logic/GuardPolicy.kt", "guard/logic/CheckDecision.kt", "guard/logic/AlertPolicy.kt",
           "guard/logic/DeliveryPlan.kt", "guard/logic/SettingsCoercion.kt", "guard/logic/GuardSettings.kt",
           "guard/logic/PercentInput.kt", "guard/logic/AlertToken.kt", "guard/logic/AlertAddress.kt", "data/Plug.kt"]
SWAPS = [(r"<=", ">"), (r">=", "<"), (r"(?<![<>=!-])<(?![=<])", ">="), (r"(?<![<>=!-])>(?![=>])", "<="),
         (r"==", "!="), (r"!=", "=="), (r"&&", "||"), (r"\|\|", "&&"), (r"\btrue\b", "false"), (r"\bfalse\b", "true"),
         (r"(?<=\w) \+ (?=\w)", " - ")]

def sites(text):
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        code = line.split("//")[0]
        if code.strip().startswith(("*", "/*", "import", "package")) or "->" in code and "when" not in code and "=" not in code.split("->")[0] and False:
            continue
        for pat, rep in SWAPS:
            for m in re.finditer(pat, code):
                out.append((n, m.start(), m.end(), rep))
    return out

def run_tests():
    """'survived' (tests pass), 'killed' (a test failed) or 'invalid' (the mutant did not compile: not counted)."""
    r = subprocess.run(["./gradlew", ":app:testDebugUnitTest", "-q", "--offline"], capture_output=True, text=True)
    if r.returncode == 0:
        return "survived"
    return "killed" if "tests completed" in r.stdout + r.stderr else "invalid"

files = [ROOT / f for f in (sys.argv[1:] or TARGETS)]
survivors, total, killed, invalid = [], 0, 0, 0
for f in files:
    original = f.read_text()
    lines = original.splitlines(keepends=True)
    for n, a, b, rep in sites(original):
        mutated = list(lines)
        mutated[n - 1] = lines[n - 1][:a] + rep + lines[n - 1][b:]
        f.write_text("".join(mutated))
        try:
            result = run_tests()
        finally:
            f.write_text(original)
        if result == "invalid":
            invalid += 1
            continue
        total += 1
        if result == "survived":
            survivors.append(f"{f.name}:{n}: '{lines[n-1][a:b]}' -> '{rep}'   | {lines[n-1].strip()}")
            print("SURVIVED", survivors[-1], flush=True)
        else:
            killed += 1
print(f"\n{killed}/{total} mutants killed, {len(survivors)} survived, {invalid} skipped (did not compile)")
pathlib.Path("tools/mutation-survivors.txt").write_text("\n".join(survivors) + "\n")
