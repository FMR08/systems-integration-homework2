# Experimentación ABET 6: evidencia ante fallas

Esta carpeta convierte los escenarios de falla en **experimentos medibles y repetibles**. Cada experimento:

1. Parte de un estado limpio (orquestador y BFF reiniciados: circuito `CLOSED`, bulkhead libre; producto de prueba propio con 100.000 unidades).
2. Induce la falla por HTTP (`/api/shipping/chaos`, `/api/tracking/chaos`) en momentos fijos.
3. Registra **cada petición** (latencia, código HTTP, resultado) y muestrea el actuator del orquestador cada 250–500 ms (estado del circuito, cupos del bulkhead, conexiones a BD activas y en espera, hilos de Tomcat ocupados).
4. Termina con una **auditoría SQL de las 4 bases de datos**: que no quede mercadería reservada, retiro agendado ni tracking vigente sin un envío completado.

Para responder «¿qué habría pasado **sin** el patrón?», E3 y E5 se corren en tres perfiles del orquestador:

| Perfil | Timeout agendar | Circuit Breaker | Bulkhead | Archivo |
|---|---|---|---|---|
| `completo` | 2 s | activo (ventana 6, mín. 4, 50 %, lenta > 1 s, abierto 15 s) | 3 concurrentes, sin cola | configuración del repo |
| `solo-timeout` | 2 s | neutralizado | neutralizado (10.000) | `compose.solo-timeout.yml` |
| `sin-patrones` | 60 s (manda el timeout de 30 s del gateway) | neutralizado | neutralizado (10.000) | `compose.sin-patrones.yml` |

Los perfiles cambian **solo configuración** (vía `SPRING_APPLICATION_JSON`), nunca el código: el sistema medido es el mismo. `compose.observabilidad.yml` se aplica siempre y solo publica las métricas de hilos de Tomcat. El runner verifica al arrancar cada perfil que la configuración quedó aplicada (timeout que imprime el orquestador, umbral del CB y máximo del bulkhead leídos del actuator) y omite el perfil si no.

## Cómo correrlo

Requisitos: Docker con Compose y Python 3.9+. `matplotlib` solo para las figuras (`pip install matplotlib`).

```bash
docker compose up -d --build                      # una vez (incluye el timeout configurable del orquestador)
python3 experiments/run_experiments.py            # ~15 min, los 3 perfiles
python3 experiments/analyze.py experiments/results/<run_id>
```

Opciones útiles: `--quick` (ensayo de ~6 min), `--solo E3,E5`, `--perfiles completo,sin-patrones`, `--e3-delay 1500`, `--e5-delay 5000`, `--e5-k 40`. En Windows usa `python` en vez de `python3`. Al terminar, el runner apaga el caos y deja el orquestador con la configuración del repo.

Salida en `experiments/results/<run_id>/`: `requests.csv` (una fila por petición), `samples.csv` (series del actuator), `phases.csv` (inicio y fin de cada fase), `audits.json`, `logs_<exp>_<perfil>.txt`, `consola.txt`, y tras el análisis `resumen.md` + 4 figuras PNG.

## Diseño de los experimentos

| | Pregunta | Variable manipulada | Qué se mide | Hipótesis (predicción antes de correr) |
|---|---|---|---|---|
| **E1** Éxito e idempotencia | ¿La saga deja los 3 pasos firmes y un mismo envío nunca se agenda dos veces? | Reintento secuencial del mismo `transaccionId`; 5 copias simultáneas de 5 ids | Resultado, `trackingNumber` antes y después, auditoría | 100 % `COMPLETADA`; el reintento devuelve el mismo tracking; los duplicados simultáneos crean un solo envío; stock reservado = envíos |
| **E2** Fallo con compensación | ¿Al fallar el último paso se compensa en orden inverso y sin estado inconsistente? | Tracking falla (`fail=true`) | Respuesta, estado final, orden de compensación en logs, auditoría | 100 % `COMPENSADA`, orden 3→2→1, stock reservado 0, retiros `CANCELADO`, trackings `ANULADO` |
| **E3** Lento y caído | ¿El CB detecta la degradación, falla rápido y deja de golpear al transportista? ¿Cuánto cuesta recuperarse? | Fases a 1 envío/s: normal 20 s → lento (3 s) 45 s → caído (503) 45 s → recuperado 45 s | Latencia por envío, % que llega al transportista, estado del CB en el tiempo | **completo**: el CB abre tras ~4 llamadas y la latencia baja de ~2 s a milisegundos; en la caída solo las sondas de `HALF_OPEN` (2 cada 15 s) llegan al transportista; tras recuperarse tarda ≤ 15 s en cerrar. **solo-timeout**: cada envío paga 2 s y el 100 % golpea al transportista. **sin-patrones**: cada envío paga los 3 s completos |
| **E5** Pico de carga | ¿El bulkhead impide que una dependencia lenta agote los recursos del resto? | Ráfaga de 40 envíos simultáneos; control sin lentitud y con transportista a 5 s | Resultado de cada saga; latencia de `GET /api/despachos/{id}` y del BFF (sondas de lazo abierto); conexiones a BD en espera; hilos ocupados; cupos del bulkhead | **completo**: 3 sagas esperan al transportista, el resto se rechaza en ms y la consulta de estado sigue en ms. **Sin bulkhead**: cada saga lenta retiene una conexión del pool Hikari (10) durante toda la petición, porque `spring.jpa.open-in-view` está activo por defecto; las consultas que no usan al transportista quedan esperando conexión → latencias de segundos (falla en cascada) |
| **E6** Rate limiting | ¿El gateway protege de un cliente abusivo sin afectar a otros? | 40 peticiones seguidas de un mismo `X-Client-Id` | Códigos HTTP | 30 pasan, 10 reciben `429`; otro cliente pasa; sin token `401` |

**Variables controladas** (iguales en todos los perfiles): misma máquina e imágenes, mismas tasas y latencias inducidas, mismo producto con stock holgado, estado reiniciado antes de cada experimento, un `X-Client-Id` distinto por petición (para que el rate limiter no contamine E3/E5; se mide aparte en E6).

**Criterios de aceptación**: auditoría C1–C6 en PASS en todos los experimentos y perfiles; E1/E2 según la hipótesis; en E3/E5 el perfil `completo` debe mostrar fail-fast y rechazos del bulkhead, y los perfiles sin patrones deben mostrar el daño que el patrón evita. Si una hipótesis no se cumple, se informa tal cual y se explica.

### Auditoría de consistencia (al final de cada experimento)

| Control | Qué verifica |
|---|---|
| C1 | Ninguna saga quedó en un estado intermedio (`INICIADA` … `COMPENSANDO`) |
| C2 | Toda saga `COMPLETADA` tiene reserva `RESERVADA`, retiro `AGENDADO` y tracking `GENERADO` |
| C3 | No hay reserva `RESERVADA`, retiro `AGENDADO` ni tracking `GENERADO` fuera de una saga `COMPLETADA`: **nada comprometido sin envío** |
| C4 | Conservación del stock: disponible + reservado = stock inicial |
| C5 | Stock reservado = suma de reservas `RESERVADA` |
| C6 | A lo más un retiro por `transaccionId` |

Antes de auditar se espera a que no queden sagas en curso y a que lleguen los comandos tardíos (p. ej., un `schedule` que el transportista procesa después del timeout y que la cancelación preventiva debe rechazar).

## Amenazas a la validez (para declararlas en el informe)

- El generador de carga y el sistema comparten la máquina: las latencias absolutas dependen del equipo; lo comparable son los perfiles entre sí dentro de una misma corrida.
- La falla se inyecta dentro del transportista (`sleep` o `503`), no en la red. Un corte de red real (conexión rechazada, paquetes perdidos) se comporta distinto; queda como extensión (p. ej. `docker compose pause transportista`).
- En `solo-timeout` y `sin-patrones` el CB y el bulkhead se neutralizan por configuración, no se eliminan: la anotación sigue envolviendo la llamada, pero nunca abre ni rechaza.
- El rate limiter usa ventana fija de 1 minuto; E6 espera a que no cruce el cambio de minuto, porque en el borde un cliente puede pasar hasta 2× el límite.
- Las peticiones se asignan a la fase en que se **enviaron**; una petición lenta enviada al final de una fase puede terminar ya en la siguiente.
