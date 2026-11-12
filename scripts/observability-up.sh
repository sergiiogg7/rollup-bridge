#!/usr/bin/env bash
# Starts Prometheus and Grafana natively (no Docker) on top of monitoring/, with state under .obs/ (git-ignored).
#
#   scripts/observability-up.sh        then run the services (scripts/demo.sh with KEEP=1, or `rollup-bridge all`)
#   scripts/observability-down.sh      stop both
#
# Needs a Prometheus binary and a Grafana distribution; point to them with
#   PROMETHEUS_BIN  (default: `prometheus` on the PATH)       GRAFANA_HOME (default: the folder that contains bin/grafana[.exe])
# Ports (all bound to 127.0.0.1): PROM_PORT=9090 GRAFANA_PORT=3000, scraped targets STATUS_PORT=18088 (the services) and
# CHALLENGER_STATUS_PORT=18089 (an optional stand-alone challenger). GRAFANA_PASSWORD (default admin) is a throw-away
# local credential: Grafana only listens on loopback here.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
OBS="$ROOT/.obs"; mkdir -p "$OBS/prometheus" "$OBS/grafana/data" "$OBS/grafana/logs" "$OBS/grafana/plugins"
PROM_PORT=${PROM_PORT:-9090}; GRAFANA_PORT=${GRAFANA_PORT:-3000}
STATUS_PORT=${STATUS_PORT:-18088}; CHALLENGER_STATUS_PORT=${CHALLENGER_STATUS_PORT:-18089}
GRAFANA_PASSWORD=${GRAFANA_PASSWORD:-admin}
native() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }   # Windows tools want C:/ paths

PROM=${PROMETHEUS_BIN:-$(command -v prometheus || true)}
[ -n "$PROM" ] && [ -x "$PROM" ] || { echo "set PROMETHEUS_BIN to a prometheus binary" >&2; exit 1; }
GRAFANA_BIN=""; for c in "${GRAFANA_HOME:-}/bin/grafana" "${GRAFANA_HOME:-}/bin/grafana.exe" "$(command -v grafana || true)"; do
  [ -f "$c" ] && GRAFANA_BIN="$c" && break; done
[ -n "$GRAFANA_BIN" ] || { echo "set GRAFANA_HOME to a Grafana distribution (bin/grafana[.exe], conf/, public/)" >&2; exit 1; }
GRAFANA_HOME=${GRAFANA_HOME:-$(dirname "$(dirname "$GRAFANA_BIN")")}

# Prometheus: monitoring/ with the ports of this run
sed -e "s/127.0.0.1:18088/127.0.0.1:$STATUS_PORT/" -e "s/127.0.0.1:18089/127.0.0.1:$CHALLENGER_STATUS_PORT/" monitoring/prometheus.yml > "$OBS/prometheus.yml"
cp monitoring/alerts.yml "$OBS/alerts.yml"

# Grafana: provisioning copied with this run's ports and the dashboard folder
rm -rf "$OBS/grafana/provisioning"; cp -r monitoring/grafana/provisioning "$OBS/grafana/provisioning"
mkdir -p "$OBS/grafana/provisioning/plugins" "$OBS/grafana/provisioning/alerting"   # silence Grafana's "cannot read" notices
sed -i "s#http://127.0.0.1:9090#http://127.0.0.1:$PROM_PORT#" "$OBS/grafana/provisioning/datasources/prometheus.yml"
sed -i "s#path: /dashboards.*#path: $(native "$ROOT/monitoring/grafana/dashboards")#" "$OBS/grafana/provisioning/dashboards/dashboards.yml"

"$PROM" --config.file="$(native "$OBS/prometheus.yml")" --storage.tsdb.path="$(native "$OBS/prometheus")" \
  --web.listen-address="127.0.0.1:$PROM_PORT" >"$OBS/prometheus.log" 2>&1 &
echo $! > "$OBS/prometheus.pid"
GF_PATHS_DATA="$(native "$OBS/grafana/data")" GF_PATHS_LOGS="$(native "$OBS/grafana/logs")" \
GF_PATHS_PLUGINS="$(native "$OBS/grafana/plugins")" GF_PATHS_PROVISIONING="$(native "$OBS/grafana/provisioning")" \
GF_SECURITY_ADMIN_PASSWORD="$GRAFANA_PASSWORD" GF_SERVER_HTTP_ADDR=127.0.0.1 GF_SERVER_HTTP_PORT="$GRAFANA_PORT" \
GF_ANALYTICS_REPORTING_ENABLED=false GF_ANALYTICS_CHECK_FOR_UPDATES=false GF_USERS_ALLOW_SIGN_UP=false \
  "$GRAFANA_BIN" server --homepath "$(native "$GRAFANA_HOME")" >"$OBS/grafana.out" 2>&1 &
echo $! > "$OBS/grafana.pid"

for _ in $(seq 60); do
  curl -sf "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null && curl -sf "http://127.0.0.1:$GRAFANA_PORT/api/health" >/dev/null && break; sleep 1
done
curl -sf "http://127.0.0.1:$PROM_PORT/-/ready" >/dev/null || { echo "prometheus did not come up, see .obs/prometheus.log" >&2; exit 1; }
curl -sf "http://127.0.0.1:$GRAFANA_PORT/api/health" >/dev/null || { echo "grafana did not come up, see .obs/grafana.out" >&2; exit 1; }
echo "Prometheus http://127.0.0.1:$PROM_PORT/   Grafana http://127.0.0.1:$GRAFANA_PORT/ (admin / $GRAFANA_PASSWORD), dashboard 'Rollup bridge'"
echo "scraping 127.0.0.1:$STATUS_PORT/metrics and 127.0.0.1:$CHALLENGER_STATUS_PORT/metrics; stop with scripts/observability-down.sh"
