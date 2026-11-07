#!/usr/bin/env python3
"""Print the routing evaluation produced by a running Spring Boot Prism gateway."""
import argparse
import json
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080", help="Spring Boot gateway base URL")
    args = parser.parse_args()
    request = urllib.request.Request(args.url.rstrip("/") + "/admin/router/eval")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response)
    except (OSError, urllib.error.URLError) as exc:
        print(f"Could not read router evaluation: {exc}", file=sys.stderr)
        return 2

    print(f"Routing evaluation: {result['correct']}/{result['total']} correct ({result['accuracy']:.1%})")
    for case in result["cases"]:
        status = "PASS" if case["correct"] else "FAIL"
        print(f"  {status} {case['id']}: expected={case['expected']} actual={case['actual']} — {case['note']}")
    return 0 if result["accuracy"] >= 0.9 else 1


if __name__ == "__main__":
    raise SystemExit(main())
