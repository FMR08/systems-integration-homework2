#!/usr/bin/env python3
"""
Runner de la experimentación ABET 6 (evidencia ante fallas).

Ejecuta los experimentos E1..E6 descritos en experiments/README.md contra el sistema
levantado con docker compose, en uno o más perfiles de configuración:

  completo      -> configuración real del repo (timeout + Circuit Breaker + Bulkhead)
  solo-timeout  -> timeout de 2 s, CB y Bulkhead neutralizados
  sin-patrones  -> contrafactual: sin CB, sin Bulkhead y timeout de agendar en 60 s

Por cada petición registra latencia, código HTTP y resultado; en paralelo muestrea el
actuator del orquestador (estado del circuito, cupos del bulkhead, pool de conexiones,
hilos de Tomcat). Al final de cada experimento audita las 4 bases de datos.

Solo usa la biblioteca estándar de Python 3.9+. Requiere docker compose en el PATH.

Uso (desde la raíz del repo, con el sistema ya construido: docker compose up -d --build):
  python3 experiments/run_experiments.py
  python3 experiments/run_experiments.py --quick
  python3 experiments/run_experiments.py --solo E3,E5 --perfiles completo,sin-patrones
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _leer_env(path: Path) -> dict:
    """Lee el .env de la raíz (el mismo que usa docker compose). Formato simple KEY=valor."""
    vals = {}
    if path.exists():
        for linea in path.read_text(encoding="utf-8").splitlines():
            linea = linea.strip()
            if linea and not linea.startswith("#") and "=" in linea:
                k, v = linea.split("=", 1)
                v = v.strip()
                if v[:1] in ('"', "'"):
                    v = v[1:].split(v[0], 1)[0]  # valor entre comillas
                else:
                    v = v.split(" #", 1)[0].strip()  # comentario al final de la línea
                vals[k.strip()] = v
    return vals


_ENV = _leer_env(ROOT / ".env")
GW = (os.environ.get("GW") or
      f"http://localhost:{os.environ.get('GATEWAY_PORT') or _ENV.get('GATEWAY_PORT') or 8080}").rstrip("/")
# Prioridad: variable de entorno > .env > valor por defecto del docker-compose.yml
TOKEN = os.environ.get("TOKEN") or _ENV.get("GATEWAY_TOKEN") or "forma-l-secret-token"
PG_USER = os.environ.get("POSTGRES_USER") or _ENV.get("POSTGRES_USER") or "integracion"
COMPOSE = os.environ.get("COMPOSE_CMD", "docker compose").split()

BASE_FILES = ["docker-compose.yml", "experiments/compose.observabilidad.yml"]
PERFILES = {
    "completo": [],
    "solo-timeout": ["experiments/compose.solo-timeout.yml"],
    "sin-patrones": ["experiments/compose.sin-patrones.yml"],
}
# Timeout de agendar que debe reportar el orquestador al arrancar en cada perfil
TIMEOUT_ESPERADO_MS = {"completo": 2000, "solo-timeout": 2000, "sin-patrones": 60000}

ESTADOS_FINALES = {"COMPLETADA", "COMPENSADA", "FALLIDA"}
DIRECCION = "Av. Libertador 1234, Santiago"

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # localhost sin proxy


# --------------------------------------------------------------------------- utilidades

def log(msg: str) -> None:
    linea = f"[{dt.datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(linea, flush=True)
    if CONSOLA is not None:
        CONSOLA.write(linea + "\n")
        CONSOLA.flush()


CONSOLA = None


def http(method: str, path: str, body=None, client: str | None = None,
         timeout: float = 45, auth: bool = True):
    """Devuelve (status, json|None, latencia_ms, error_cliente|None). status=0 si no hubo respuesta."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    if auth:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("X-Client-Id", client or f"exp-{uuid.uuid4().hex[:12]}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    t0 = time.perf_counter()
    try:
        with _opener.open(req, timeout=timeout) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status = e.code
        try:
            raw = e.read()
        except Exception:
            raw = b""
    except Exception as e:  # timeout del cliente, conexión rechazada, etc.
        return 0, None, (time.perf_counter() - t0) * 1000, type(e).__name__
    ms = (time.perf_counter() - t0) * 1000
    try:
        js = json.loads(raw) if raw else None
    except ValueError:
        js = None
    return status, js, ms, None


def mensaje(js) -> str:
    if not isinstance(js, dict):
        return ""
    if isinstance(js.get("error"), dict):
        return str(js["error"].get("message") or "")
    return str(js.get("message") or "")


def clasificar(status: int, msg: str, err: str | None, js=None) -> str:
    """Traduce la respuesta de POST /api/despachos a un resultado legible."""
    if status == 0:
        return f"SIN_RESPUESTA({err})"
    if status == 200:
        if isinstance(js, dict) and js.get("success") is False:
            return "DUPLICADO_EN_CURSO"  # carrera de duplicados: devolvió el estado de la saga ganadora
        return "OK_IDEMPOTENTE" if "idempotente" in msg.lower() else "OK"
    if status == 401:
        return "NO_AUTORIZADO"
    if status == 429:
        return "RATE_LIMIT"
    if status == 504:
        return "GATEWAY_TIMEOUT"
    if "CallNotPermittedException" in msg:
        return "CB_ABIERTO"
    if "BulkheadFullException" in msg:
        return "BULKHEAD_LLENO"
    if "ResourceAccessException" in msg or "timed out" in msg.lower() or "Timeout" in msg:
        return "TIMEOUT"
    if "ServiceUnavailable" in msg:
        return "TRANSPORTISTA_503"
    if status == 502:
        return "SAGA_COMPENSADA_502"
    if status == 409:
        return "CONFLICTO_409"
    return f"HTTP_{status}"


def compose(*args, files=None, check=True, timeout=900) -> subprocess.CompletedProcess:
    cmd = list(COMPOSE)
    for f in files or BASE_FILES:
        cmd += ["-f", f]
    cmd += list(args)
    return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout, check=check)


SAFE = re.compile(r"^[A-Za-z0-9_-]+$")


def sql(db: str, query: str) -> list[list[str]]:
    r = compose("exec", "-T", "postgres", "psql", "-q", "-U", PG_USER, "-d", db,
                "-At", "-F", "\t", "-c", query)
    return [line.split("\t") for line in r.stdout.splitlines() if line.strip()]


def crear_producto(pid: str, stock: int) -> None:
    assert SAFE.match(pid)
    sql("inventario", "INSERT INTO inventario_stock (producto_id, stock_disponible, stock_reservado, updated_at) "
                      f"VALUES ('{pid}', {int(stock)}, 0, NOW()) ON CONFLICT (producto_id) DO NOTHING;")


def _chaos(path: str) -> None:
    # X-Client-Id único: /api/*/chaos pasa por el rate limiter del gateway, y un 429 aquí dejaría
    # correr el experimento SIN la falla inducida. Por eso además se exige 200.
    s, js, _, err = http("POST", path)
    if s != 200:
        raise RuntimeError(f"no se pudo configurar el caos ({path}): HTTP {s} {err or mensaje(js)}")


def chaos_transportista(delay_ms: int = 0, down: bool = False) -> None:
    _chaos(f"/api/shipping/chaos?delayMs={int(delay_ms)}&down={'true' if down else 'false'}")


def chaos_tracking(fail: bool) -> None:
    _chaos(f"/api/tracking/chaos?fail={'true' if fail else 'false'}")


def reset_chaos() -> None:
    chaos_transportista(0, False)
    chaos_tracking(False)


def esperar_salud(path: str, nombre: str, limite_s: int = 240) -> None:
    t0, ultimo_aviso, estado = time.time(), time.time(), "-"
    while time.time() - t0 < limite_s:
        s, js, _, err = http("GET", path, timeout=5)  # /bff-actuator no está exento del rate limiter
        if s == 200 and isinstance(js, dict) and js.get("status") == "UP":
            log(f"   {nombre} UP ({time.time() - t0:.0f}s)")
            return
        estado = f"HTTP {s}" if s else (err or "sin respuesta")
        if time.time() - ultimo_aviso >= 15:
            log(f"   esperando a que {nombre} arranque... {time.time() - t0:.0f}s (último: {estado})")
            ultimo_aviso = time.time()
        time.sleep(2)
    raise RuntimeError(f"{nombre} no quedó UP en {limite_s}s ({path}, último: {estado}). "
                       f"Revisa: docker compose logs {nombre}")


def metrica(nombre: str, tag: str | None = None):
    path = f"/actuator/metrics/{nombre}" + (f"?tag={tag}" if tag else "")
    s, js, _, _ = http("GET", path, client="obs", timeout=5)
    if s != 200 or not isinstance(js, dict):
        return None
    for m in js.get("measurements", []):
        if m.get("statistic") in ("VALUE", "COUNT"):
            return m.get("value")
    return None


def _pct(v):
    try:
        return float(str(v).rstrip("%"))
    except (TypeError, ValueError):
        return None


def leer_cb() -> dict:
    s, js, _, _ = http("GET", "/actuator/circuitbreakers", client="obs", timeout=5)
    cb = ((js or {}).get("circuitBreakers") or {}).get("transportista") or {}
    return {
        "cb_estado": cb.get("state"),
        "cb_tasa_fallo": _pct(cb.get("failureRate")),
        "cb_tasa_lentas": _pct(cb.get("slowCallRate")),
        "cb_buffered": cb.get("bufferedCalls"),
        "cb_no_permitidas": cb.get("notPermittedCalls"),
        "cb_umbral_fallo": _pct(cb.get("failureRateThreshold")),
    }


# --------------------------------------------------------------------------- registro

class Corrida:
    """Acumula peticiones, muestras y fases de toda la ejecución."""

    def __init__(self, outdir: Path):
        self.out = outdir
        self.reqs: list[dict] = []
        self.samples: list[dict] = []
        self.fases: list[dict] = []
        self.auditorias: list[dict] = []
        self.lock = threading.Lock()


class Experimento:
    def __init__(self, corrida: Corrida, exp: str, perfil: str):
        self.c = corrida
        self.exp = exp
        self.perfil = perfil
        self.t0 = time.perf_counter()
        self.inicio_utc = dt.datetime.now(dt.timezone.utc)
        self.fase = "-"
        self._fase_t = 0.0
        self._fase_meta: dict = {}
        self.prefijo = f"{exp}-{perfil[:2].upper()}-{RUN_ID}-"
        self.producto = f"X{RUN_ID}{exp}{perfil[:2].upper()}"
        self._stop = threading.Event()
        self._hilos: list[threading.Thread] = []
        self._sondas: list[threading.Thread] = []

    def t(self) -> float:
        return time.perf_counter() - self.t0

    def nueva_fase(self, nombre: str, **meta) -> None:
        self.cerrar_fase()
        self.fase, self._fase_t, self._fase_meta = nombre, self.t(), meta
        log(f"   fase {nombre} {meta if meta else ''}")

    def cerrar_fase(self) -> None:
        if self.fase != "-":
            with self.c.lock:
                self.c.fases.append({"exp": self.exp, "perfil": self.perfil, "fase": self.fase,
                                     "t_inicio_s": round(self._fase_t, 3), "t_fin_s": round(self.t(), 3),
                                     "config": json.dumps(self._fase_meta)})
        self.fase = "-"

    def registrar(self, tipo: str, tx: str, t_envio: float, status: int, ms: float, resultado: str, msg: str,
                  fase: str | None = None):
        with self.c.lock:
            self.c.reqs.append({"exp": self.exp, "perfil": self.perfil, "fase": fase or self.fase, "tipo": tipo,
                                "tx": tx, "t_envio_s": round(t_envio, 3), "latencia_ms": round(ms, 1),
                                "http": status, "resultado": resultado, "mensaje": msg[:200]})

    # --- acciones medidas
    def crear_envio(self, tx: str, cantidad: int = 1, tipo: str = "saga") -> dict:
        t_envio = self.t()
        fase = self.fase
        s, js, ms, err = http("POST", "/api/despachos", body={
            "transaccionId": tx, "productoId": self.producto, "cantidad": cantidad, "direccion": DIRECCION})
        msg = mensaje(js)
        res = clasificar(s, msg, err, js)
        with self.c.lock:
            self.c.reqs.append({"exp": self.exp, "perfil": self.perfil, "fase": fase, "tipo": tipo, "tx": tx,
                                "t_envio_s": round(t_envio, 3), "latencia_ms": round(ms, 1), "http": s,
                                "resultado": res, "mensaje": msg[:200]})
        return {"status": s, "json": js, "ms": ms, "resultado": res}

    def get_medido(self, tipo: str, path: str, tx: str = "", client: str | None = None) -> dict:
        t_envio, fase = self.t(), self.fase
        s, js, ms, err = http("GET", path, client=client)
        res = "OK" if s == 200 else (f"SIN_RESPUESTA({err})" if s == 0 else f"HTTP_{s}")
        msg = mensaje(js)
        if s == 200 and isinstance(js, dict) and "degradado" in js:
            msg = f"degradado={str(js.get('degradado')).lower()}"
            if js.get("degradado"):
                res = "OK_DEGRADADO"
        self.registrar(tipo, tx, t_envio, s, ms, res, msg, fase)
        return {"status": s, "json": js, "ms": ms}

    # --- hilos de fondo
    def muestrear(self, intervalo: float = 0.5) -> None:
        def loop():
            while not self._stop.is_set():
                t_envio, fase = self.t(), self.fase
                a = time.perf_counter()
                fila = {"exp": self.exp, "perfil": self.perfil, "fase": fase, "t_s": round(t_envio, 3)}
                fila.update(leer_cb())
                fila["bulkhead_disponibles"] = metrica("resilience4j.bulkhead.available.concurrent.calls",
                                                       "name:transportista")
                fila["hikari_activas"] = metrica("hikaricp.connections.active")
                fila["hikari_pendientes"] = metrica("hikaricp.connections.pending")
                fila["tomcat_ocupados"] = metrica("tomcat.threads.busy")
                fila["muestra_ms"] = round((time.perf_counter() - a) * 1000, 1)
                with self.c.lock:
                    self.c.samples.append(fila)
                self._stop.wait(max(0.0, intervalo - (time.perf_counter() - a)))
        self._lanzar(loop)

    def sonda(self, tipo: str, path: str, intervalo: float, tx: str) -> None:
        """Lazo abierto: lanza una consulta cada `intervalo` aunque la anterior siga esperando.
        Así una consulta trabada 10 s no esconde las que habrían llegado mientras tanto
        (evita la "omisión coordinada" de un sondeo secuencial)."""
        def loop():
            n, inicio = 0, time.perf_counter()
            while not self._stop.is_set():
                h = threading.Thread(target=self.get_medido, args=(tipo, path, tx), daemon=True)
                h.start()
                self._sondas.append(h)
                n += 1
                self._stop.wait(max(0.0, inicio + n * intervalo - time.perf_counter()))
        self._lanzar(loop)

    def _lanzar(self, fn) -> None:
        h = threading.Thread(target=fn, daemon=True)
        h.start()
        self._hilos.append(h)

    def detener_fondo(self) -> None:
        self._stop.set()
        for h in self._hilos + self._sondas:
            h.join(timeout=60)
        self._hilos.clear()
        self._sondas.clear()
        self._stop = threading.Event()

    # --- carga
    def carga_tasa_constante(self, rps: float, duracion_s: float, etiqueta: str) -> list[threading.Thread]:
        """Lazo abierto: se envía a tasa fija aunque las respuestas tarden (como clientes reales)."""
        hilos, i, inicio = [], 0, time.perf_counter()
        while True:
            objetivo = inicio + i / rps
            if objetivo - inicio >= duracion_s:
                break
            time.sleep(max(0.0, objetivo - time.perf_counter()))
            tx = f"{self.prefijo}{etiqueta}-{i:04d}"
            h = threading.Thread(target=self.crear_envio, args=(tx,), daemon=True)
            h.start()
            hilos.append(h)
            i += 1
        # se respeta la duración completa: la última petición no queda pegada al cambio de fase
        time.sleep(max(0.0, inicio + duracion_s - time.perf_counter()))
        return hilos

    def rafaga(self, k: int, etiqueta: str) -> list[threading.Thread]:
        barrera = threading.Barrier(k)

        def uno(i):
            barrera.wait()
            self.crear_envio(f"{self.prefijo}{etiqueta}-{i:03d}")

        hilos = [threading.Thread(target=uno, args=(i,), daemon=True) for i in range(k)]
        for h in hilos:
            h.start()
        return hilos

    # --- cierre
    def asentar(self, extra_s: float = 3, limite_s: float = 120) -> None:
        """Espera a que no queden sagas en estados intermedios (y a que lleguen comandos tardíos)."""
        time.sleep(extra_s)
        t0 = time.time()
        while time.time() - t0 < limite_s:
            filas = sql("despachos", "SELECT count(*) FROM despachos_saga WHERE transaccion_id LIKE "
                                     f"'{self.prefijo}%' AND estado NOT IN ('COMPLETADA','COMPENSADA','FALLIDA')")
            if int(filas[0][0]) == 0:
                return
            time.sleep(2)
        log("   AVISO: quedaron sagas en estado intermedio al vencer el plazo de espera")

    def auditar(self, stock_inicial: int) -> dict:
        p = self.prefijo
        assert SAFE.match(p.rstrip("-")) and SAFE.match(self.producto)
        sagas = {r[0]: r[1] for r in sql("despachos", f"SELECT transaccion_id, estado FROM despachos_saga "
                                                      f"WHERE transaccion_id LIKE '{p}%'")}
        reservas = {r[0]: (r[1], int(r[2])) for r in sql("inventario", "SELECT transaccion_id, estado, cantidad "
                                                         f"FROM inventario_reservas WHERE transaccion_id LIKE '{p}%'")}
        retiros = {r[0]: r[1] for r in sql("transportista", "SELECT transaccion_id, estado FROM retiros_transporte "
                                                            f"WHERE transaccion_id LIKE '{p}%'")}
        trackings = {r[0]: r[1] for r in sql("tracking", "SELECT transaccion_id, estado FROM tracking_envios "
                                                         f"WHERE transaccion_id LIKE '{p}%'")}
        dup = sql("transportista", "SELECT transaccion_id, count(*) FROM retiros_transporte WHERE transaccion_id "
                                   f"LIKE '{p}%' GROUP BY transaccion_id HAVING count(*) > 1")
        st = sql("inventario", "SELECT stock_disponible, stock_reservado FROM inventario_stock "
                               f"WHERE producto_id = '{self.producto}'")
        disp, resv = (int(st[0][0]), int(st[0][1])) if st else (None, None)

        completadas = {tx for tx, e in sagas.items() if e == "COMPLETADA"}
        intermedias = sorted(tx for tx, e in sagas.items() if e not in ESTADOS_FINALES)
        incompletas = sorted(tx for tx in completadas if reservas.get(tx, ("-",))[0] != "RESERVADA"
                             or retiros.get(tx) != "AGENDADO" or trackings.get(tx) != "GENERADO")
        # efectos activos que no pertenecen a una saga COMPLETADA = mercadería/retiro/tracking huérfano
        huerfanos = sorted({tx for tx, (e, _) in reservas.items() if e == "RESERVADA" and tx not in completadas}
                           | {tx for tx, e in retiros.items() if e == "AGENDADO" and tx not in completadas}
                           | {tx for tx, e in trackings.items() if e == "GENERADO" and tx not in completadas})
        reservado_por_reservas = sum(c for e, c in reservas.values() if e == "RESERVADA")

        def chk(nombre, ok, detalle):
            return {"control": nombre, "ok": bool(ok), "detalle": detalle}

        controles = [
            chk("C1 sin sagas en estado intermedio", not intermedias, f"{len(intermedias)} intermedias {intermedias[:5]}"),
            chk("C2 COMPLETADA => reserva RESERVADA + retiro AGENDADO + tracking GENERADO", not incompletas,
                f"{len(incompletas)} incompletas {incompletas[:5]}"),
            chk("C3 sin efectos activos fuera de una saga COMPLETADA (nada comprometido sin envío)", not huerfanos,
                f"{len(huerfanos)} huérfanos {huerfanos[:5]}"),
            chk("C4 conservación de stock (disponible + reservado = inicial)",
                disp is not None and disp + resv == stock_inicial,
                f"disponible={disp} reservado={resv} inicial={stock_inicial}"),
            chk("C5 stock reservado = suma de reservas RESERVADA", resv == reservado_por_reservas,
                f"stock_reservado={resv} suma_reservas={reservado_por_reservas}"),
            chk("C6 a lo más un retiro por envío (idempotencia)", not dup, f"{len(dup)} duplicados"),
        ]

        def conteo(d):
            out: dict = {}
            for v in d.values():
                v = v[0] if isinstance(v, tuple) else v
                out[v] = out.get(v, 0) + 1
            return out

        res = {"exp": self.exp, "perfil": self.perfil, "prefijo": p, "producto": self.producto,
               "sagas": conteo(sagas), "reservas": conteo(reservas), "retiros": conteo(retiros),
               "trackings": conteo(trackings), "stock": {"inicial": stock_inicial, "disponible": disp, "reservado": resv},
               "controles": controles, "todo_ok": all(c["ok"] for c in controles)}
        with self.c.lock:
            self.c.auditorias.append(res)
        estado = "PASS" if res["todo_ok"] else "FAIL"
        log(f"   auditoría {estado}: sagas={res['sagas']} stock={res['stock']}")
        for c in controles:
            if not c["ok"]:
                log(f"     FAIL {c['control']}: {c['detalle']}")
        return res

    def guardar_logs(self) -> Path:
        r = compose("logs", "--no-log-prefix", "--since", self.inicio_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
                    "orquestador", check=False)
        f = self.c.out / f"logs_{self.exp}_{self.perfil}.txt"
        f.write_text(r.stdout, encoding="utf-8")
        return f

    def terminar(self) -> None:
        self.cerrar_fase()
        self.detener_fondo()


# --------------------------------------------------------------------------- perfiles

def verificar_gateway() -> bool:
    """Antes de tocar nada: confirma que en GW responde el gateway de ESTE proyecto y que enruta bien."""
    log(f"Verificando el gateway en {GW} ...")
    s, js, _, err = http("GET", "/api/shipping/chaos", timeout=5)
    if s == 200 and isinstance(js, dict) and "delayMs" in js:
        s2, js2, _, _ = http("GET", "/actuator/health", timeout=5)
        log(f"   gateway OK (transportista responde; salud del orquestador: HTTP {s2} "
            f"{(js2 or {}).get('status', '') if isinstance(js2, dict) else ''})")
        return True
    puerto = GW.rsplit(":", 1)[-1]
    if s == 0:
        log(f"   Nada responde en {GW} ({err}). ¿Está arriba el sistema? -> docker compose ps")
    elif s == 401:
        log("   El gateway rechazó el token (HTTP 401). El runner usa GATEWAY_TOKEN del .env (o TOKEN); "
            "debe ser el mismo con que se levantó el gateway.")
    else:
        log(f"   En {GW} responde algo que NO es el gateway de este proyecto: GET /api/shipping/chaos -> HTTP {s}.")
        log("   Revisa en qué puerto quedó publicado el gateway (columna PORTS):  docker compose ps gateway")
        log(f"   y qué programa ocupa el {puerto}:  netstat -ano | findstr :{puerto}   (Windows)  /  lsof -i :{puerto}")
        log("   Si el gateway quedó en otro puerto, define GATEWAY_PORT en .env y vuelve a levantar: docker compose up -d")
    return False


def aplicar_perfil(perfil: str, corrida: Corrida) -> dict:
    log(f"== Perfil {perfil}: recreando orquestador con {PERFILES[perfil] or 'la configuración del repo'}")
    log("   (recrear + arrancar Spring Boot suele tomar 30-90 s; sigue el detalle con: docker compose logs -f orquestador)")
    compose("up", "-d", "--no-deps", "--force-recreate", "orquestador", files=BASE_FILES + PERFILES[perfil])
    log("   contenedor recreado; reiniciando bff")
    compose("restart", "bff")
    esperar_salud("/actuator/health", "orquestador")
    esperar_salud("/bff-actuator/health", "bff")
    return verificar_perfil(perfil)


def reiniciar_estado() -> None:
    """Reinicia orquestador y BFF: circuito CLOSED y bulkhead libre (estado en memoria)."""
    log("   reiniciando orquestador y bff para partir con el circuito cerrado (~30-90 s)")
    compose("restart", "orquestador", "bff")
    esperar_salud("/actuator/health", "orquestador")
    esperar_salud("/bff-actuator/health", "bff")


def verificar_perfil(perfil: str) -> dict:
    """Comprueba que la configuración del perfil quedó aplicada de verdad."""
    r = compose("logs", "--no-log-prefix", "orquestador", check=False)
    m = re.findall(r"\[TRANSPORTISTA\] timeouts agendar=(\d+)ms/(\d+)ms", r.stdout)
    timeout_ms = int(m[-1][1]) if m else None
    cb = leer_cb()
    max_bh = metrica("resilience4j.bulkhead.max.allowed.concurrent.calls", "name:transportista")
    info = {"perfil": perfil, "agendar_read_timeout_ms": timeout_ms, "cb_umbral_fallo": cb.get("cb_umbral_fallo"),
            "bulkhead_max": max_bh}
    log(f"   verificación: {info}")
    problemas, avisos = [], []
    if timeout_ms is None:
        (avisos if perfil == "completo" else problemas).append(
            "el orquestador no imprime '[TRANSPORTISTA] timeouts' -> imagen desactualizada: "
            "ejecuta 'docker compose up -d --build' antes de los experimentos")
    elif timeout_ms != TIMEOUT_ESPERADO_MS[perfil]:
        problemas.append(f"timeout de agendar = {timeout_ms} ms, se esperaba {TIMEOUT_ESPERADO_MS[perfil]}")
    if perfil == "completo" and max_bh not in (None, 3, 3.0):
        problemas.append(f"bulkhead máximo = {max_bh}, se esperaba 3")
    if perfil != "completo" and max_bh not in (None, 10000, 10000.0):
        problemas.append(f"bulkhead máximo = {max_bh}, se esperaba 10000 (neutralizado)")
    info["problemas"], info["avisos"] = problemas, avisos
    for p in problemas:
        log(f"   PROBLEMA: {p}")
    for p in avisos:
        log(f"   AVISO: {p}")
    return info


# --------------------------------------------------------------------------- experimentos

STOCK = 100_000


def e1_exito(c: Corrida, perfil: str, q: bool) -> None:
    """Camino feliz + idempotencia (reintento secuencial y duplicados concurrentes)."""
    e = Experimento(c, "E1", perfil)
    crear_producto(e.producto, STOCK)
    reset_chaos()
    n = 4 if q else 10
    e.nueva_fase("secuencial", n=n)
    txs = [f"{e.prefijo}ok-{i:03d}" for i in range(n)]
    tracking_1 = {}
    for tx in txs:
        r = e.crear_envio(tx)
        tracking_1[tx] = (r["json"] or {}).get("trackingNumber")
    e.nueva_fase("reintento-mismo-id", n=n)
    distintos = 0
    for tx in txs:
        r = e.crear_envio(tx, tipo="saga-reintento")
        if (r["json"] or {}).get("trackingNumber") != tracking_1[tx]:
            distintos += 1
    log(f"   reintentos con trackingNumber distinto al original: {distintos} de {n}")
    e.nueva_fase("duplicados-concurrentes", ids=5, copias=5)
    hilos = []
    for j in range(5):
        tx = f"{e.prefijo}dup-{j}"
        barrera = threading.Barrier(5)

        def uno(tx=tx, barrera=barrera):
            barrera.wait()
            e.crear_envio(tx, tipo="saga-duplicada")

        for _ in range(5):
            h = threading.Thread(target=uno, daemon=True)
            h.start()
            hilos.append(h)
    for h in hilos:
        h.join()
    e.terminar()
    e.asentar(extra_s=2)
    a = e.auditar(STOCK)
    a["extra"] = {"reintentos_tracking_distinto": distintos, "esperado_completadas": n + 5}
    e.guardar_logs()


def e2_compensacion(c: Corrida, perfil: str, q: bool) -> None:
    """Falla el último paso (tracking): compensación en orden inverso, sin estado inconsistente."""
    e = Experimento(c, "E2", perfil)
    crear_producto(e.producto, STOCK)
    reset_chaos()
    chaos_tracking(True)
    n = 4 if q else 10
    e.nueva_fase("tracking-falla", n=n)
    for i in range(n):
        e.crear_envio(f"{e.prefijo}comp-{i:03d}")
    e.terminar()
    chaos_tracking(False)
    e.asentar(extra_s=2)
    a = e.auditar(STOCK)
    logs = e.guardar_logs().read_text(encoding="utf-8")
    ordenes = {}
    for linea in logs.splitlines():
        m = re.search(r"\[SAGA (" + re.escape(e.prefijo) + r"[^\]]+)\] Compensando Paso (\d)", linea)
        if m:
            ordenes.setdefault(m.group(1), []).append(int(m.group(2)))
    correctas = sum(1 for v in ordenes.values() if v == [3, 2, 1])
    log(f"   orden de compensación 3->2->1 en logs: {correctas} de {n} sagas")
    a["extra"] = {"orden_compensacion": ordenes, "orden_correcto": correctas, "n": n}


def e3_lento_caido(c: Corrida, perfil: str, q: bool, delay_ms: int) -> None:
    """Línea de tiempo a 1 req/s: normal -> transportista lento -> caído -> recuperado."""
    e = Experimento(c, "E3", perfil)
    crear_producto(e.producto, STOCK)
    reset_chaos()
    dur = {"normal": 8, "lento": 20, "caido": 20, "recuperado": 25} if q else \
          {"normal": 20, "lento": 45, "caido": 45, "recuperado": 45}
    e.muestrear(0.5)
    hilos = []
    e.nueva_fase("normal", delayMs=0, down=False)
    hilos += e.carga_tasa_constante(1.0, dur["normal"], "n")
    chaos_transportista(delay_ms, False)
    e.nueva_fase("lento", delayMs=delay_ms, down=False)
    hilos += e.carga_tasa_constante(1.0, dur["lento"], "l")
    chaos_transportista(0, True)
    e.nueva_fase("caido", delayMs=0, down=True)
    hilos += e.carga_tasa_constante(1.0, dur["caido"], "c")
    chaos_transportista(0, False)
    e.nueva_fase("recuperado", delayMs=0, down=False)
    hilos += e.carga_tasa_constante(1.0, dur["recuperado"], "r")
    for h in hilos:
        h.join(timeout=90)
    e.terminar()
    e.asentar(extra_s=delay_ms / 1000 + 2)
    e.auditar(STOCK)
    e.guardar_logs()


def e5_pico(c: Corrida, perfil: str, q: bool, delay_ms: int, k: int) -> None:
    """Pico de K peticiones simultáneas (control sin lentitud y con transportista lento).
    Sondas paralelas miden el daño colateral en operaciones que NO usan al transportista."""
    e = Experimento(c, "E5", perfil)
    crear_producto(e.producto, STOCK)
    reset_chaos()
    base = f"{e.prefijo}base"
    e.nueva_fase("preparacion")
    e.crear_envio(base, tipo="saga-base")
    e.muestrear(0.25)
    e.sonda("sonda-estado", f"/api/despachos/{base}", 0.2, base)
    e.sonda("sonda-bff", f"/api/bff/envios/{base}", 0.5, base)
    for variante, d in (("control", 0), ("lento", delay_ms)):
        chaos_transportista(d, False)
        e.nueva_fase(f"{variante}-antes", delayMs=d)
        time.sleep(3)
        e.nueva_fase(f"{variante}-pico", delayMs=d, k=k)
        hilos = e.rafaga(k, f"{variante[0]}")
        for h in hilos:
            h.join(timeout=120)
        e.nueva_fase(f"{variante}-despues", delayMs=d)
        time.sleep(4)
    chaos_transportista(0, False)
    e.terminar()
    e.asentar(extra_s=delay_ms / 1000 + 2)
    e.auditar(STOCK)
    e.guardar_logs()


def e6_rate_limit(c: Corrida, perfil: str, q: bool) -> None:
    """Pico de un mismo cliente contra el gateway (ventana fija de 1 minuto)."""
    e = Experimento(c, "E6", perfil)
    seg = dt.datetime.now().second
    if seg > 40:  # que las 40 peticiones no crucen el cambio de minuto (la ventana se reinicia)
        log(f"   esperando {61 - seg}s al inicio del próximo minuto para no cruzar la ventana")
        time.sleep(61 - seg)
    cliente = f"rl-{RUN_ID}"
    e.nueva_fase("mismo-cliente", n=40, limite=30)
    for i in range(40):
        e.get_medido("rate-limit", f"/api/despachos/rl-none-{i}", client=cliente)
    e.nueva_fase("otro-cliente")
    e.get_medido("rate-limit-otro", "/api/despachos/rl-none", client=f"otro-{RUN_ID}")
    e.nueva_fase("sin-token")
    t_envio = e.t()
    s, js, ms, err = http("GET", "/api/despachos/rl-none", auth=False)
    e.registrar("sin-token", "", t_envio, s, ms, "OK" if s == 200 else f"HTTP_{s}", mensaje(js))
    e.terminar()


# --------------------------------------------------------------------------- main

def escribir_csv(path: Path, filas: list[dict]) -> None:
    if not filas:
        return
    campos = list(dict.fromkeys(k for f in filas for k in f))
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=campos)
        w.writeheader()
        w.writerows(filas)


def guardar(c: Corrida, meta: dict) -> None:
    escribir_csv(c.out / "requests.csv", c.reqs)
    escribir_csv(c.out / "samples.csv", c.samples)
    escribir_csv(c.out / "phases.csv", c.fases)
    (c.out / "audits.json").write_text(json.dumps(c.auditorias, indent=2, ensure_ascii=False), encoding="utf-8")
    (c.out / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")


def main() -> int:
    global RUN_ID, CONSOLA
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--perfiles", default="completo,solo-timeout,sin-patrones")
    ap.add_argument("--solo", default="E1,E2,E3,E5,E6", help="experimentos a correr")
    ap.add_argument("--quick", action="store_true", help="duraciones cortas (ensayo)")
    ap.add_argument("--e3-delay", type=int, default=3000, help="latencia del transportista en E3 (ms)")
    ap.add_argument("--e5-delay", type=int, default=5000, help="latencia del transportista en E5 (ms)")
    ap.add_argument("--e5-k", type=int, default=40, help="peticiones simultáneas en el pico de E5")
    ap.add_argument("--build", action="store_true", help="ejecuta 'docker compose up -d --build' antes")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    perfiles = [p.strip() for p in args.perfiles.split(",") if p.strip()]
    for p in perfiles:
        if p not in PERFILES:
            ap.error(f"perfil desconocido: {p}")
    exps = {x.strip().upper() for x in args.solo.split(",")}

    RUN_ID = dt.datetime.now().strftime("%m%d%H%M%S")
    out = Path(args.out) if args.out else ROOT / "experiments" / "results" / RUN_ID
    out.mkdir(parents=True, exist_ok=True)
    CONSOLA = (out / "consola.txt").open("w", encoding="utf-8")
    c = Corrida(out)

    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    meta = {"run_id": RUN_ID, "inicio": dt.datetime.now().isoformat(timespec="seconds"), "git": commit,
            "gateway": GW, "perfiles": perfiles, "experimentos": sorted(exps), "quick": args.quick,
            "e3_delay_ms": args.e3_delay, "e5_delay_ms": args.e5_delay, "e5_k": args.e5_k, "verificaciones": []}
    log(f"Corrida {RUN_ID} -> {out}")

    if args.build:
        log("Construyendo y levantando el sistema (docker compose up -d --build)...")
        compose("up", "-d", "--build")

    if not verificar_gateway():
        return 2

    try:
        for perfil in perfiles:
            info = aplicar_perfil(perfil, c)
            meta["verificaciones"].append(info)
            if info["problemas"]:
                log(f"   Se omite el perfil {perfil}: su configuración no quedó aplicada.")
                continue
            if perfil == "completo":
                if "E1" in exps:
                    log("-- E1 camino feliz e idempotencia"); e1_exito(c, perfil, args.quick)
                if "E2" in exps:
                    log("-- E2 fallo del último paso con compensación"); e2_compensacion(c, perfil, args.quick)
                if "E6" in exps:
                    log("-- E6 rate limiting en el gateway"); e6_rate_limit(c, perfil, args.quick)
            if "E3" in exps:
                reiniciar_estado()
                log("-- E3 transportista lento y caído (línea de tiempo)")
                e3_lento_caido(c, perfil, args.quick, args.e3_delay)
            if "E5" in exps:
                reiniciar_estado()
                log("-- E5 pico de carga con transportista lento (bulkhead y daño colateral)")
                e5_pico(c, perfil, args.quick, args.e5_delay, args.e5_k)
            guardar(c, meta)
    finally:
        log("Restaurando: chaos apagado y orquestador con la configuración del repo")
        # pasos independientes: que falle el caos no impide devolver el orquestador a su configuración
        for paso in (reset_chaos,
                     lambda: compose("up", "-d", "--no-deps", "--force-recreate", "orquestador", files=["docker-compose.yml"])):
            try:
                paso()
            except Exception as ex:  # no tapar el error original
                log(f"   no se pudo restaurar: {ex}")
        meta["fin"] = dt.datetime.now().isoformat(timespec="seconds")
        guardar(c, meta)
        CONSOLA.close()
    print(f"\nListo. Resultados en {out}\nSiguiente paso: python3 experiments/analyze.py {out}")
    return 0


RUN_ID = "0"

if __name__ == "__main__":
    sys.exit(main())