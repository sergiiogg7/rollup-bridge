#!/usr/bin/env bash
# Verifies a running observability stack through its HTTP APIs (what a browser would show, without a browser):
# targets UP, alert rules loaded, dashboard provisioned, and EVERY dashboard query returns data through Grafana.
# Run it while the services are producing metrics (scripts/observability-drill.sh does, at the moment alerts fire).
# Exit 0 only if all of that holds. Variables: PROM_PORT=9090 GRAFANA_PORT=3000 GRAFANA_PASSWORD=admin
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROM=http://127.0.0.1:${PROM_PORT:-9090}; GRAF=http://127.0.0.1:${GRAFANA_PORT:-3000}; AUTH="admin:${GRAFANA_PASSWORD:-admin}"
bad=0; check() { if [ "$1" = ok ]; then echo "  ok    $2"; else echo "  FAIL  $2"; bad=1; fi; }

echo "== Prometheus targets"
targets=$(curl -sf "$PROM/api/v1/targets?state=active") || { echo "Prometheus unreachable"; exit 1; }
# one line per target: job, instance, health
echo "$targets" | grep -oP '"labels":\{[^}]*"instance":"[^"]*","job":"[^"]*"[^}]*\}|"health":"[a-z]+"' | paste - - | sed 's/"labels"://; s/,"severity.*//' | sed 's/^/  /'
echo "$targets" | grep -q '"health":"up"' && check ok "at least one target is UP" || check no "no target is UP"

echo "== Prometheus rules"
rules=$(curl -sf "$PROM/api/v1/rules?type=alert") || { echo "rules endpoint failed"; exit 1; }
for a in $(grep -oP '^      - alert: \K\w+' "$ROOT/monitoring/alerts.yml"); do
  echo "$rules" | grep -q "\"name\":\"$a\"" && check ok "rule loaded: $a" || check no "rule missing: $a"
done
echo "  firing right now: $(curl -sf "$PROM/api/v1/alerts" | grep -oP '"alertname":"\K\w+(?=",[^}]*\},"annotations":\{[^}]*\},"state":"firing")' | sort -u | tr '\n' ' ')"

echo "== Grafana"
curl -sf "$GRAF/api/health" | tr -d '\n ' | sed 's/^/  /'; echo
curl -sf -u "$AUTH" "$GRAF/api/search?query=Rollup" | grep -q '"uid":"rollup-bridge"' && check ok "dashboard 'Rollup bridge' is provisioned" || check no "dashboard missing"
curl -sf -u "$AUTH" "$GRAF/api/datasources" | grep -q '"uid":"prom"' && check ok "datasource 'prom' is provisioned" || check no "datasource missing"
echo "== every dashboard query, evaluated through Grafana's datasource proxy"
n=0
while IFS= read -r expr; do
  expr=${expr//\\\"/\"}
  res=$(curl -sf -u "$AUTH" --get "$GRAF/api/datasources/proxy/uid/prom/api/v1/query" --data-urlencode "query=$expr") || res=""
  series=$(printf '%s' "$res" | grep -o '"metric"' | wc -l)
  n=$((n+1))
  if [ "$series" -gt 0 ]; then check ok "$series series  $expr"; else check no "no data    $expr"; fi
done < <(sed -n 's/.*"expr": "\(.*\)"}.*/\1/p' "$ROOT/monitoring/grafana/dashboards/bridge.json")
echo "$n queries checked"
exit $bad
