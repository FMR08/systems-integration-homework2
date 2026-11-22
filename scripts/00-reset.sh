#!/usr/bin/env bash
# Deja las fallas inducidas apagadas.
source "$(dirname "$0")/lib.sh"
reset_chaos
echo "Chaos transportista: $(get_json /api/shipping/chaos)"
echo "Chaos tracking:      $(get_json /api/tracking/chaos)"
echo "Circuit Breaker:     $(cb_state)"
