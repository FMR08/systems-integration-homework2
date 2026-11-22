#!/usr/bin/env bash
# Escenario 3: transportista lento y luego caído. Circuit Breaker + fallback + Bulkhead.
source "$(dirname "$0")/lib.sh"
log_to 03-cascada
BASE="base-$RUN_ID"

ensure_closed() {
  reset_chaos
  local s; s=$(cb_state)
  if [ "$s" != "CLOSED" ]; then
    echo "Circuito en $s. Esperando 16s y enviando sondas para cerrarlo..."
    sleep 16
    for i in 1 2; do crear_envio "probe-$RUN_ID-$i" "probe-$RUN_ID-$i"; done
  fi
  echo "Estado del circuito: $(cb_state)"
}

step "0. ANTES: sistema sano"
ensure_closed
crear_envio "$BASE" "base-client-$RUN_ID"
snapshot_resilience

step "1. LENTITUD: transportista responde en 1500 ms (llamadas lentas)"
chaos_transportista 1500 false
for i in 1 2 3 4 5 6; do crear_envio "slow-$RUN_ID-$i" "slow-$i-$RUN_ID"; done
echo "Estado del circuito: $(cb_state)"
echo "(las primeras ~4 tardan ~1.5s; luego el circuito abre y responde en milisegundos con fallback 503)"
OPEN_RESP=$(crear_envio "ff-$RUN_ID" "ff-$RUN_ID"); echo "$OPEN_RESP"
expect "circuito OPEN" "OPEN" "$(cb_state)"
expect "fail-fast 503" "HTTP 503" "$OPEN_RESP"
expect "fallback por circuito abierto" "CallNotPermittedException" "$OPEN_RESP"

step "2. CAÍDA: transportista devuelve 503"
chaos_transportista 0 true
echo "Esperando 16s (open -> half-open) y sondeando con el transportista caído..."
sleep 16
for i in 1 2; do crear_envio "down-$RUN_ID-$i" "down-$i-$RUN_ID"; done
echo "Estado del circuito: $(cb_state)  (vuelve a OPEN)"
crear_envio "down-ff-$RUN_ID" "down-ff-$RUN_ID"
snapshot_resilience

step "3. RECUPERACIÓN: transportista vuelve"
chaos_transportista 0 false
echo "Esperando 16s y enviando sondas..."
sleep 16
for i in 1 2; do crear_envio "rec-$RUN_ID-$i" "rec-$i-$RUN_ID"; done
echo "Estado del circuito: $(cb_state)"
expect "circuito CLOSED" "CLOSED" "$(cb_state)"

step "4. PICO + LENTITUD: 8 peticiones concurrentes, bulkhead de 3"
chaos_transportista 1500 false
TMP=$(mktemp -d)
for i in $(seq 1 8); do ( crear_envio "burst-$RUN_ID-$i" "burst-$i-$RUN_ID" > "$TMP/$i.out" ) & done
sleep 0.4
echo "Mientras el transportista está saturado, el resto del sistema responde:"
timed_get "/api/despachos/$BASE" "probe-a-$RUN_ID"
timed_get "/api/bff/envios/$BASE" "probe-b-$RUN_ID"
wait
cat "$TMP"/*.out | sort
REJ=$(cat "$TMP"/*.out | grep -c "BulkheadFullException")
echo "Rechazadas por bulkhead: $REJ"
expect "bulkhead rechaza exceso" "[1-9]" "$REJ"
echo "--- Vista BFF degradada (retiro lento)"
get_json "/api/bff/envios/$BASE" "probe-c-$RUN_ID" | pj
snapshot_resilience

step "5. Restaurar"
reset_chaos
echo "Chaos apagado. Circuito: $(cb_state)"
rm -rf "$TMP"
summary
