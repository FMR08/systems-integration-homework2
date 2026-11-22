#!/usr/bin/env bash
# Escenario 1: camino feliz. Los tres pasos quedan firmes.
source "$(dirname "$0")/lib.sh"
log_to 01-exito
ID="ok-$RUN_ID"
reset_chaos

step "Crear envío $ID"
R=$(crear_envio "$ID"); echo "$R"
expect "HTTP 200" "HTTP 200" "$R"

step "Estado de la saga"
S=$(get_json "/api/despachos/$ID"); echo "$S" | pj
expect "saga COMPLETADA" '"estado":"COMPLETADA"' "$S"
expect "trackingNumber emitido" 'TRK-' "$S"

step "Vista BFF (4 servicios en una respuesta)"
V=$(get_json "/api/bff/envios/$ID"); echo "$V" | pj
expect "inventario RESERVADA" '"estado":"RESERVADA"' "$V"
expect "retiro AGENDADO" '"estado":"AGENDADO"' "$V"
expect "tracking GENERADO" '"estado":"GENERADO"' "$V"
expect "sin degradación" '"degradado":false' "$V"

step "Idempotencia: repetir el mismo transaccionId"
R2=$(crear_envio "$ID"); echo "$R2"
expect "HTTP 200 idempotente" "HTTP 200" "$R2"
V2=$(get_json "/api/bff/envios/$ID")
expect "mismo tracking" "$(printf '%s' "$S" | grep -o 'TRK-[A-Z0-9]*' | head -n1)" "$V2"

step "Logs de la saga"; saga_logs "$ID"
summary
