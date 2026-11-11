#!/usr/bin/env python3
"""Local Runner for Prism LLM Gateway.
Starts:
  1. Mock Provider Alpha (port 9001)
  2. Mock Provider Beta (port 9002)
  3. Prism Gateway (FastAPI/Uvicorn on port 8000)

Usage:
    python scripts/run_local.py
"""

from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def wait_for_url(url: str, timeout: float = 10.0) -> bool:
    start = time.time()
    while time.time() - start < timeout:
        try:
            with urllib.request.urlopen(url, timeout=1.0) as resp:
                if resp.status in (200, 404):
                    return True
        except Exception:
            time.sleep(0.3)
    return False


def main():
    parser = argparse.ArgumentParser(description="Run Prism Gateway and Mock Providers locally.")
    parser.add_argument("--gateway-port", type=int, default=8000, help="Gateway port (default: 8000)")
    parser.add_argument("--alpha-port", type=int, default=9001, help="Alpha mock port (default: 9001)")
    parser.add_argument("--beta-port", type=int, default=9002, help="Beta mock port (default: 9002)")
    args = parser.parse_args()

    print("=" * 65)
    print("  STARTING PRISM LOCAL ENVIRONMENT")
    print("=" * 65)

    processes = []
    try:
        # 1. Start Alpha Mock Provider
        print(f"[1/3] Starting Mock Provider Alpha on port {args.alpha_port}...")
        p_alpha = subprocess.Popen(
            [sys.executable, "scripts/mock_provider.py", "--port", str(args.alpha_port), "--name", "alpha"],
            cwd=str(ROOT)
        )
        processes.append(("Alpha", p_alpha))

        # 2. Start Beta Mock Provider
        print(f"[2/3] Starting Mock Provider Beta on port {args.beta_port}...")
        p_beta = subprocess.Popen(
            [sys.executable, "scripts/mock_provider.py", "--port", str(args.beta_port), "--name", "beta"],
            cwd=str(ROOT)
        )
        processes.append(("Beta", p_beta))

        # Check mocks health
        if not wait_for_url(f"http://localhost:{args.alpha_port}/health"):
            print("ERROR: Mock Provider Alpha failed to start.")
            sys.exit(1)
        if not wait_for_url(f"http://localhost:{args.beta_port}/health"):
            print("ERROR: Mock Provider Beta failed to start.")
            sys.exit(1)

        print("  --> Mock Providers Alpha & Beta are HEALTHY.")

        # 3. Start Prism Gateway (using uvicorn)
        print(f"[3/3] Starting Prism Gateway on http://localhost:{args.gateway_port}...")
        import uvicorn
        from src.gateway import app

        print("\n" + "=" * 65)
        print(f"  PRISM IS RUNNING LOCALLY!")
        print(f"  - Gateway API:   http://localhost:{args.gateway_port}/v1/chat/completions")
        print(f"  - Health Check:  http://localhost:{args.gateway_port}/health")
        print(f"  - Ops Console:   http://localhost:{args.gateway_port}/console")
        print(f"  - Provider Alpha: http://localhost:{args.alpha_port}")
        print(f"  - Provider Beta:  http://localhost:{args.beta_port}")
        print("=" * 65 + "\n")

        uvicorn.run(app, host="0.0.0.0", port=args.gateway_port, log_level="info")

    except KeyboardInterrupt:
        print("\nStopping all services...")
    finally:
        for name, p in processes:
            print(f"Terminating {name} (PID {p.pid})...")
            p.terminate()
            p.wait()
        print("All processes stopped.")


if __name__ == "__main__":
    main()
