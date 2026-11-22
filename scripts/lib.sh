#!/usr/bin/env bash
# Utilidades compartidas por los escenarios. Requiere: bash, curl. (python3 opcional para JSON legible)
GW="${GW:-http://localhost:8080}"
TOKEN="${TOKEN:-forma-l-secret-token}"
RUN_ID="${RUN_ID:-$(date +%s)}"
EVID_DIR="${EVID_DIR:-evidence}"
PRODUCTO="${PRODUCTO:-PROD-001}"
DIRECCION="${DIRECCION:-Av. Libertador 1234, Santiago}"
mkdir -p "$EVID_DIR"
PASS=0; FAIL=0

log_to() { exec > >(tee "$EVID_DIR/$1-$RUN_ID.txt") 2>&1; }
step()   { printf '\n=== %s ===\n' "$*"; }
pj() { local t; t=$(cat); printf '%s' "$t" | python3 -m json.tool 2>/dev/null || printf '%s\n' "$t"; }

# api METHOD PATH [CLIENT]  -> imprime cuerpo y, en la última línea, "HTTP <code> <tiempo>s"
api() {
  curl -s -w '\nHTTP %{http_code} %{time_total}s\n' -X "$1" "$GW$2" \
    -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: ${3:-script-$RUN_ID}"
}

# crear_envio ID [CLIENT]  -> una línea: [ID] HTTP code tiempo :: mensaje
crear_envio() {
  local id="$1" client="${2:-script-$RUN_ID}" out code time msg body
  body=$(printf '{"transaccionId":"%s","productoId":"%s","cantidad":1,"direccion":"%s"}' "$id" "$PRODUCTO" "$DIRECCION")
  out=$(curl -s -w '\n%{http_code} %{time_total}' -X POST "$GW/api/despachos" \
    -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: $client" \
    -H 'Content-Type: application/json' -d "$body")
  code=$(printf '%s' "$out" | tail -n1 | cut -d' ' -f1)
  time=$(printf '%s' "$out" | tail -n1 | cut -d' ' -f2)
  msg=$(printf '%s' "$out" | head -n1 | grep -o '"message":"[^"]*"' | head -n1)
  printf '[%s] HTTP %s %ss :: %s\n' "$id" "$code" "$time" "$msg"
}

get_json()  { curl -s -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: ${2:-script-$RUN_ID}" "$GW$1"; }
timed_get() { curl -s -o /dev/null -w "$1 -> HTTP %{http_code} %{time_total}s\n" -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: ${2:-script-$RUN_ID}" "$GW$1"; }

chaos_transportista() { api POST "/api/shipping/chaos?delayMs=${1:-0}&down=${2:-false}" "chaos-$RUN_ID" | head -n1; }
chaos_tracking()      { api POST "/api/tracking/chaos?fail=${1:-false}" "chaos-$RUN_ID" | head -n1; }
reset_chaos()         { chaos_transportista 0 false >/dev/null; chaos_tracking false >/dev/null; }

# Actuator del orquestador (vía gateway, con token)
act()       { curl -s -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: obs-$RUN_ID" "$GW$1"; }
cb_state()  { act /actuator/circuitbreakers | grep -o '"state":"[A-Z_]*"' | head -n1 | cut -d'"' -f4; }
snapshot_resilience() {
  echo "--- Circuit Breaker transportista"
  act /actuator/circuitbreakers | pj
  echo "--- Bulkhead transportista (llamadas concurrentes disponibles)"
  act "/actuator/metrics/resilience4j.bulkhead.available.concurrent.calls?tag=name:transportista" | pj
  echo "--- Eventos Circuit Breaker (últimos)"
  act /actuator/circuitbreakerevents/transportista | pj | tail -n 45
  echo "--- Bulkhead: llamadas rechazadas acumuladas"
  act /actuator/bulkheadevents/transportista | grep -o 'CALL_REJECTED' | wc -l
}

expect() { # etiqueta patrón texto
  if printf '%s' "$3" | grep -q "$2"; then echo "PASS  $1"; PASS=$((PASS+1)); else echo "FAIL  $1 (esperaba: $2)"; FAIL=$((FAIL+1)); fi
}
summary() { printf '\nResultado: %s PASS, %s FAIL\n' "$PASS" "$FAIL"; [ "$FAIL" -eq 0 ]; }

saga_logs() { (cd "$(dirname "${BASH_SOURCE[0]}")/.." && docker compose logs --no-log-prefix orquestador 2>/dev/null | grep "SAGA $1" ) || echo "(logs no disponibles: ejecutar: docker compose logs orquestador | grep 'SAGA $1')"; }
