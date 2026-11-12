# Observability

What the services expose, what is alerted on, and the proof that it works. Everything here was run natively on a Windows laptop (no Docker): Prometheus 3.16.0, Grafana 13.3.1, two Anvils and the Java services. No browser screenshots were taken; the evidence is what the Prometheus and Grafana HTTP APIs return.

## What the services expose

Every role (`deposit-relayer`, `proposer`, `withdrawal-relayer`, `challenger`, `guardian`, or `all`) serves `/metrics` in the Prometheus text format on `STATUS_PORT` (127.0.0.1 only). The registry (`core/metrics`) is about 200 lines with no dependency: counters, gauges and histograms. It performs no I/O, so `core` stays free of HTTP.

Three mechanisms feed it:

- **Metered ports** (`Metered`, a dynamic proxy around `L1Reader`, `L1Writer`, `L2Reader`, `L2Writer`): counts every failure by service and a fixed set of reasons, even when the service swallows the exception and retries on the next tick, and counts transactions. The services themselves know nothing about metrics.
- **`ServiceRunner`**: tick outcome, tick duration and the time of the last successful tick.
- **`BridgeCollector`**: refreshes the gauges that describe the bridge as a whole (pull model, right before each scrape). An RPC outage shows up as `bridge_collector_errors_total{section}` and stale values, never as a failed scrape.

| Metric | Meaning |
|---|---|
| `bridge_deposits_pending`, `bridge_deposits_by_state{state}` | deposits not yet Done/Dropped; per state |
| `bridge_deposit_latency_seconds` (histogram) | first seen by the relayer process to minted on L2 (deposits this process saw; lost on restart) |
| `bridge_deposit_oldest_pending_age_seconds` | age of the oldest pending deposit |
| `bridge_l1_confirmations_waiting` | deposits seen but still short of the confirmation depth |
| `bridge_withdrawals_by_state{state}` | Initiated, Included, Proven, Invalidated, Finalized |
| `bridge_outputs_proposed_total`, `bridge_outputs_challenged_total`, `bridge_challenges_resolved_total{proposer_was_wrong}` | output lifecycle counters |
| `bridge_outputs{status}`, `bridge_outputs_on_l1`, `bridge_outputs_in_window` | newest 64 outputs by status |
| `bridge_open_output_min_window_remaining_seconds` | window left for the open output closest to finalizing |
| `bridge_unchecked_output_min_window_remaining_seconds`, `bridge_challenger_unchecked_outputs` | the same, only for outputs the challenger has NOT verified (needs the challenger's store: `challenger` or `all`) |
| `bridge_challenge_window_seconds` | the window configured on the oracle (so alert thresholds are fractions of it) |
| `bridge_cursor_lag_blocks{chain}` | chain head minus the block up to which a relayer has scanned |
| `bridge_chain_head_block{chain}`, `bridge_l1_paused` | heads; 1 if the bridge is paused |
| `bridge_service_errors_total{service,reason}` | reasons: `rpc_transport`, `rpc_error`, `tx_reverted`, `tx_timeout`, `state_machine`, `internal` (never a message or an id: cardinality stays fixed) |
| `bridge_service_ticks_total{service,outcome}`, `bridge_service_last_success_timestamp_seconds{service}`, `bridge_service_tick_duration_seconds` | liveness of each loop |
| `bridge_transactions_total{service,op,outcome}` | transactions by operation and outcome (`ok`, `failed`, `error`) |

## Alerts (`monitoring/alerts.yml`)

| Alert | Fires when | Why |
|---|---|---|
| `OutputWindowClosingUnchecked` | an unchecked output has less than half of the challenge window left | the one that matters: a wrong output nobody looked at is about to become final |
| `ChallengerDown` | no challenger tick for 30 s, or no challenger series at all | without a challenger nothing can be disputed |
| `OutputChallenged`, `BridgePaused` | an output awaits the guardian; the bridge is paused | operator attention |
| `TargetDown`, `ServiceStalled` | a target cannot be scraped; a service has not completed a tick for a minute | liveness |
| `CursorStalled` | a scan cursor is more than 25 blocks behind its head for a minute | a healthy relayer sets its cursor to the head every tick |
| `DepositStuck` | a deposit pending for more than 5 minutes | |
| `ServiceErrorsHigh` | more than 5 errors in 2 minutes for one service and reason | |

Thresholds are tuned for the lab (120 s window, 1 s polling). Those that depend on the window are expressed as a fraction of `bridge_challenge_window_seconds`, so they carry over to a 7-day window. `monitoring/tests/alerts.test.yml` unit-tests every rule with `promtool test rules` (one firing case each; the thresholds also have quiet cases):

```text
$ promtool check rules monitoring/alerts.yml     SUCCESS: 9 rules found
$ promtool test rules monitoring/tests/alerts.test.yml    SUCCESS
```

## Run it

```bash
scripts/observability-up.sh       # Prometheus + Grafana, state under .obs/ (git-ignored)
scripts/demo.sh                   # or: KEEP=1 scripts/demo.sh and watch the dashboard 'Rollup bridge'
scripts/observability-check.sh    # targets, rules, dashboard and every panel query through Grafana
scripts/observability-drill.sh    # the controlled failure below (about 4 minutes)
scripts/observability-down.sh
```

Variables: `PROMETHEUS_BIN`, `GRAFANA_HOME` (a Prometheus binary and a Grafana distribution; Docker is not needed), `PROM_PORT`, `GRAFANA_PORT`, `STATUS_PORT`, `CHALLENGER_STATUS_PORT`, `GRAFANA_PASSWORD` (a throw-away local credential, Grafana listens on loopback only). `monitoring/` holds `prometheus.yml`, `alerts.yml`, the provisioning files and the dashboard JSON.

## Evidence

### 1. Everything is wired (`scripts/observability-check.sh`, run while the alerts below were firing)

Full output: [`evidence/observability-check.txt`](evidence/observability-check.txt). Extract:

```text
== Prometheus targets
  {"instance":"127.0.0.1:38088","job":"bridge"}	"health":"up"
  {"instance":"127.0.0.1:38089","job":"bridge-challenger"}	"health":"down"     <- the challenger was killed on purpose
== Prometheus rules
  ok    rule loaded: OutputWindowClosingUnchecked      (and the other 8)
== Grafana   {"database":"ok","version":"13.3.1", ...}
  ok    dashboard 'Rollup bridge' is provisioned
  ok    datasource 'prom' is provisioned
== every dashboard query, evaluated through Grafana's datasource proxy
  ok    1 series  min(bridge_unchecked_output_min_window_remaining_seconds)
  ok    2 series  max by (chain) (bridge_cursor_lag_blocks)
  ...
21 queries checked, 21 returned data
```

All 21 panel queries returned data through Grafana (`/api/datasources/proxy/uid/prom/api/v1/query`). The first version of the script failed on `sum(bridge_outputs_challenged_total)`: the series did not exist until the first challenge. `Metered` now initializes the domain counters at 0, and the check passes.

### 2. A controlled failure makes alerts fire (`scripts/observability-drill.sh`)

Topology: `rollup-bridge all` without the challenger (`DISABLE_SERVICES=challenger`), plus the challenger as its own process. The script does a deposit and a withdrawal, then **kills the challenger by PID**, then the proposer key posts an output with a fabricated 4 ETH withdrawal that Mallory proves at once (the demo attack). Transcript: [`evidence/observability-drill.txt`](evidence/observability-drill.txt).

```text
==> 3. FAULT: kill the challenger (pid 11350), then the proposer key posts a lying output
the fabricated withdrawal #1 (4000000000000000000 wei to 0x14dc...9955) was PROVEN against it
ChallengerDown is FIRING
OutputWindowClosingUnchecked is FIRING
... bridge_unchecked_output_min_window_remaining_seconds{job="bridge"} = 46      (of a 120 s window)
==> 4. RECOVERY: restart the challenger; it challenges, the guardian rules, the alerts resolve
bridge_outputs_challenged_total{job="bridge-challenger"} = 1
drill passed: the alerts fired during the failure and cleared after the recovery
```

`ALERTS{alertstate="firing"}` at that moment held `ChallengerDown`, `TargetDown` (job `bridge-challenger`), `OutputWindowClosingUnchecked` and `ServiceErrorsHigh`. The drill exits 0 only if both alerts under test fire and then clear after the restart.

### What the drill showed that nobody planned

`ServiceErrorsHigh{service="proposer", reason="tx_reverted"}` fired too. After the lying output, the honest proposer sees a live output whose withdrawal count differs from the L2 head and tries to propose, but the oracle rejects it (`NotMonotonic()`: the lying output already claims that L2 block) on every 500 ms tick. It is harmless (a revert costs no gas on estimation) and it stops once the lying output is deleted, but without the metric it was only a log line repeated hundreds of times. This is what the error counter is for. It is not fixed; a proposer that backs off after a revert would be the change.

## Limits of this evidence

- Single run per piece of evidence; no load, no long soak. The numbers are lab values (1 s blocks, 120 s window).
- Alertmanager is not part of the stack: alerts are visible in Prometheus and Grafana, nothing is sent anywhere.
- `bridge_deposit_latency_seconds` only covers deposits that the running process saw appear; a restart resets the clock of anything already pending.
- The dashboard was provisioned and queried, but nobody looked at it in a browser here, so its layout has not been judged by eye.
