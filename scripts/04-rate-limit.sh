#!/usr/bin/env bash
# Rate limiting del gateway (30 req/min por X-Client-Id). Se envían 40 con el mismo cliente.
source "$(dirname "$0")/lib.sh"
log_to 04-rate-limit
CLIENT="rl-$RUN_ID"
step "40 peticiones seguidas con X-Client-Id=$CLIENT"
for i in $(seq 1 40); do
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: $CLIENT" "$GW/api/despachos/rl-none-$i"
done | sort | uniq -c
echo "(404 = pasó el gateway; 429 = rechazado por rate limit)"
step "Otro cliente no se ve afectado"
curl -s -o /dev/null -w 'otro cliente -> HTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: other-$RUN_ID" "$GW/api/despachos/rl-none"
step "Sin token"
curl -s -o /dev/null -w 'sin token -> HTTP %{http_code}\n' "$GW/api/despachos/rl-none"
