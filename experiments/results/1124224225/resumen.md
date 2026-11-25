# Resumen de la corrida 1124224225

- Inicio: 2026-11-24T22:42:25 · fin: 2026-11-24T22:49:57 · commit `4d5f4a3` · modo rápido: False
- Latencia inducida: E3 = 3000 ms · E5 = 5000 ms con pico de 40 peticiones simultáneas
- Cada petición usa un `X-Client-Id` distinto, para que el rate limiter no interfiera (se mide aparte en E6).

## Verificación de perfiles

| Perfil | Timeout agendar (log) | Umbral fallo CB | Bulkhead máx. | Problemas |
|---|---|---|---|---|
| completo | 2000 | 50.0 | 3.0 | ninguno |
| solo-timeout | 2000 | 100.0 | 10000.0 | ninguno |
| sin-patrones | 60000 | 50.0 | 3.0 | bulkhead máximo = 3.0, se esperaba 10000 (neutralizado) |

## Auditoría de consistencia (4 bases de datos)

C1 sin sagas intermedias · C2 COMPLETADA con sus 3 efectos · C3 ningún efecto activo fuera de una saga COMPLETADA · C4 conservación de stock · C5 stock reservado = reservas activas · C6 un retiro por envío

| Exp. | Perfil | Sagas por estado | Stock reservado | Controles | Resultado |
|---|---|---|---|---|---|
| E1 | completo | COMPLETADA 15 | 15 | ✔C1 ✔C2 ✔C3 ✔C4 ✔C5 ✔C6 | PASS |
| E2 | completo | COMPENSADA 10 | 0 | ✔C1 ✔C2 ✔C3 ✔C4 ✔C5 ✔C6 | PASS |
| E3 | completo | COMPENSADA 91, COMPLETADA 64 | 64 | ✔C1 ✔C2 ✔C3 ✔C4 ✔C5 ✔C6 | PASS |
| E5 | completo | COMPENSADA 42, COMPLETADA 39 | 39 | ✔C1 ✔C2 ✔C3 ✔C4 ✔C5 ✔C6 | PASS |
| E3 | solo-timeout |  | 0 | ✔C1 ✔C2 ✔C3 ✔C4 ✔C5 ✔C6 | PASS |
| E5 | solo-timeout |  | 0 | ✔C1 ✔C2 ✔C3 ✔C4 ✔C5 ✔C6 | PASS |

Total auditado: **261 sagas**; efectos activos sin envío completado (C3): **0**.

## E1 · Camino feliz e idempotencia (perfil completo)

| Fase | Peticiones | Resultados | p50 | p95 |
|---|---|---|---|---|
| secuencial | 10 | OK 10 | 54 ms | 373 ms |
| reintento-mismo-id | 10 | OK_IDEMPOTENTE 10 | 6 ms | 8 ms |
| duplicados-concurrentes | 25 | CONFLICTO_409 11, DUPLICADO_EN_CURSO 9, OK 5 | 46 ms | 102 ms |

Sagas COMPLETADA: 15 (esperadas 15); stock reservado 15; reintentos que devolvieron otro trackingNumber: 0.

## E2 · Falla el último paso (tracking) → compensación en orden inverso

| Sagas | Respuestas | Estado final | Orden 3→2→1 en logs | Stock reservado | Retiros | Trackings |
|---|---|---|---|---|---|---|
| 10 | SAGA_COMPENSADA_502 10 | COMPENSADA 10 | 10 de 10 | 0 | CANCELADO 10 | ANULADO 10 |

## E3 · Transportista lento y luego caído (1 req/s)

Las peticiones se asignan a la fase en que se enviaron. «Llegaron al transportista» = todo lo que no fue rechazado por el CB o el bulkhead antes de llamar.

| Perfil | Fase | n | Completados | Rechazo CB | Timeout | 503 transp. | Otros | Llegaron al transp. | p50 | p95 | máx | Tiempo con CB no cerrado |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| completo | normal | 20 | 100% | 0 | 0 | 0 | 0 | 100% | 58 ms | 74 ms | 252 ms | 0% |
| completo | lento | 45 | 0% | 36 | 9 | 0 | 0 | 20% | 45 ms | 2.06 s | 2.07 s | 91% |
| completo | caido | 45 | 0% | 41 | 0 | 4 | 0 | 9% | 42 ms | 53 ms | 57 ms | 100% |
| completo | recuperado | 45 | 98% | 1 | 0 | 0 | 0 | 98% | 49 ms | 52 ms | 53 ms | 6% |
| solo-timeout | normal | 20 | 0% | 0 | 0 | 0 | 20 | 100% | 4 ms | 6 ms | 15 ms | 0% |
| solo-timeout | lento | 45 | 0% | 0 | 0 | 0 | 45 | 100% | 3 ms | 4 ms | 4 ms | 0% |
| solo-timeout | caido | 45 | 0% | 0 | 0 | 0 | 45 | 100% | 3 ms | 3 ms | 4 ms | 0% |
| solo-timeout | recuperado | 45 | 0% | 0 | 0 | 0 | 45 | 100% | 3 ms | 4 ms | 4 ms | 0% |

- completo: el circuito abrió 4.5 s después de empezar la lentitud y volvió a CLOSED 2.5 s después de la recuperación.

- solo-timeout: el circuito nunca abrió.

## E5 · Pico de carga con transportista lento: bulkhead y daño colateral

«Sonda estado» = `GET /api/despachos/{id}` lanzada cada 200 ms sin esperar a la anterior (no usa al transportista). «Sonda BFF» = vista agregada cada 500 ms. Métricas de las consultas enviadas mientras duraba el pico.

| Perfil | Variante | Sagas | Resultados | Saga p95 | Saga máx | Sonda estado p95 (antes) | Sonda estado p50 | Sonda estado p95 | Sonda estado máx | Sonda estado > 1 s | BFF con error | Conex. BD en espera (máx) | Hilos Tomcat ocupados (máx) | Cupos bulkhead (mín) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| completo | control | 40 | OK 38, BULKHEAD_LLENO 2 | 358 ms | 368 ms | 7 ms | 138 ms | 138 ms | 138 ms | 0/1 | 0/0 | 9.0 | 20.0 | 1.0 |
| completo | lento | 40 | BULKHEAD_LLENO 37, TIMEOUT 3 | 2.07 s | 2.08 s | 5 ms | 4 ms | 138 ms | 183 ms | 0/11 | 0/4 | 0.0 | 36.0 | 0.0 |
| solo-timeout | control | 40 | HTTP_404 40 | 27 ms | 29 ms | 4 ms | — | — | — | 0/0 | 0/0 | — | — | — |
| solo-timeout | lento | 40 | HTTP_404 40 | 31 ms | 34 ms | 4 ms | — | — | — | 0/0 | 0/0 | — | — | — |

## E6 · Rate limiting del gateway (30 req/min por cliente)

| 40 peticiones de un cliente | Otro cliente | Sin token |
|---|---|---|
| HTTP 404: 30, HTTP 429: 10 | 404 | 401 |

(404 = la petición pasó el gateway y el orquestador no encontró el id; 429 = cortada por el rate limiter.)

## Comparaciones calculadas


## Señales de alerta

- ⚠ El perfil sin-patrones no quedó bien aplicado: bulkhead máximo = 3.0, se esperaba 10000 (neutralizado)

## Figuras

- `fig_e3_linea_tiempo.png` · `fig_e5_dano_colateral.png` · `fig_e5_recursos.png` · `fig_e5_resultados.png`
- Las tablas de arriba son la vista de tabla de cada figura (mismos datos).
