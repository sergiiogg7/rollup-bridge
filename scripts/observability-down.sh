#!/usr/bin/env bash
# Stops what scripts/observability-up.sh started, by recorded PID only (never by process name).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for svc in grafana prometheus; do
  f="$ROOT/.obs/$svc.pid"
  if [ -f "$f" ]; then kill "$(cat "$f")" 2>/dev/null && echo "stopped $svc ($(cat "$f"))" || echo "$svc was not running"; rm -f "$f"; fi
done
