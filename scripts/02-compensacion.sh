#!/usr/bin/env bash
# Escenario 2: falla el ÚLTIMO paso (tracking). Se compensa en orden inverso.
source "$(dirname "$0")/lib.sh"
log_to 02-compensacion
ID="comp-$RUN_ID"
reset_chaos

step "Inducir falla en tracking (paso 3)"
chaos_tracking true

step "Crear envío $ID (debe fallar y compensar)"
R=$(crear_envio "$ID"); echo "$R"
expect "HTTP 502" "HTTP 502" "$R"
expect "mensaje de compensación" "compensado" "$R"

step "Estado de la saga"
S=$(get_json "/api/despachos/$ID"); echo "$S" | pj
expect "saga COMPENSADA" '"estado":"COMPENSADA"' "$S"

step "Estado real en cada servicio (vista BFF)"
V=$(get_json "/api/bff/envios/$ID"); echo "$V" | pj
expect "reserva LIBERADA" '"estado":"LIBERADA"' "$V"
expect "retiro CANCELADO" '"estado":"CANCELADO"' "$V"
expect "tracking ANULADO (sin tracking activo)" '"estado":"ANULADO"' "$V"

step "Orden de compensación (logs): tracking -> transporte -> inventario"
saga_logs "$ID"

step "Restaurar tracking"
chaos_tracking false
summary
