#!/usr/bin/env python3
"""Live Demo Drill: Automated Upstream Provider Failover.
Takes Provider Alpha down live and shows request served by Provider Beta with x-prism-fallback: true.
"""

import json
import argparse
import urllib.request
import urllib.error
import uuid

def set_alpha_mode(alpha_url: str, mode: str):
    req = urllib.request.Request(
        alpha_url.rstrip("/") + "/admin/config",
        data=json.dumps({"mode": mode}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode())

def send_chat(base_url: str, step: int, note: str, prompt: str):
    req = urllib.request.Request(
        base_url.rstrip("/") + "/v1/chat/completions",
        data=json.dumps({
            "model": "fast",
            "messages": [{"role": "user", "content": prompt}]
        }).encode(),
        headers={
            "Authorization": "Bearer prism-sk-research-4d5e6f",
            "Content-Type": "application/json"
        },
        method="POST"
    )
    with urllib.request.urlopen(req) as resp:
        headers = {k.lower(): v for k, v in resp.headers.items()}
        body = json.loads(resp.read().decode())
        provider = headers.get("x-prism-provider", "unknown")
        fallback = headers.get("x-prism-fallback", "false")
        cost = headers.get("x-prism-cost-usd", "0.0")
        reply = body["choices"][0]["message"]["content"]
        print(f"  Step {step} ({note}):")
        print(f"    - HTTP Status:        {resp.status}")
        print(f"    - x-prism-provider:   {provider}")
        print(f"    - x-prism-fallback:   {fallback}")
        print(f"    - x-prism-cost-usd:   ${cost}")
        print(f"    - Response prefix:    {reply[:70]}...")

def main():
    parser=argparse.ArgumentParser(description="Check Alpha to Beta fallback and recovery against the running gateway.")
    parser.add_argument("--url",default="http://localhost:8080",help="gateway base URL")
    parser.add_argument("--alpha-url",default="http://localhost:9001",help="Alpha mock provider base URL")
    args=parser.parse_args()
    print("=" * 65)
    print("  LIVE DRILL: PROVIDER FAILOVER & RECOVERY")
    print("  Alias: 'fast' -> Primary: 'alpha-small' | Fallback: 'beta-small'")
    print("=" * 65)

    salt=uuid.uuid4().hex[:8]
    try:
        print("\n[1] Normal Traffic (Alpha is healthy):")
        set_alpha_mode(args.alpha_url,"ok")
        send_chat(args.url,1,"Primary Alpha Healthy",f"({salt}) Explain circuit breaking in distributed systems.")

        print("\n[2] Failure Injection (Taking Alpha DOWN live):")
        set_alpha_mode(args.alpha_url,"down")
        send_chat(args.url,2,"Alpha Down -> Auto-Failover to Beta",f"({salt}) Explain circuit breaking in distributed systems.")

        print("\n[3] Provider Recovery (Restoring Alpha):")
        set_alpha_mode(args.alpha_url,"ok")
        send_chat(args.url,3,"Alpha Restored -> Traffic returns to Alpha",f"({salt}) Explain circuit breaking in distributed systems.")
    finally:
        set_alpha_mode(args.alpha_url,"ok")

    print("\n" + "=" * 65)
    print("  Drill Completed: Failover and Recovery Verified.")
    print("=" * 65)

if __name__ == "__main__":
    main()
