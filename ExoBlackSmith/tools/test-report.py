#!/usr/bin/env python3
"""Summarises surefire XML reports, listing every skipped/failed/errored test with its cause."""
import glob, re, sys
total = {"ok": 0, "skipped": 0, "failure": 0, "error": 0}
for path in sorted(glob.glob("target/surefire-reports/TEST-*.xml")):
    x = open(path).read()
    suite = re.search(r'<testsuite[^>]*name="([^"]+)"', x).group(1).split(".")[-1]
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', x, re.S):
        name, body = m.group(1), m.group(3) or ""
        k = re.search(r'<(skipped|failure|error)(?:\s+message="([^"]*)")?', body)
        if k:
            total[k.group(1)] += 1
            frames = [l.strip() for l in body.split("\n") if "com.exoblacksmith" in l][:4]
            print(f"{k.group(1).upper():8} {suite}.{name}: {(k.group(2) or '')[:240]}")
            for f in frames:
                print("           " + f)
        else:
            total["ok"] += 1
            if "-v" in sys.argv:
                print(f"OK       {suite}.{name}")
print(total)
