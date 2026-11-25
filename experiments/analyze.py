#!/usr/bin/env python3
"""
Análisis de una corrida de experiments/run_experiments.py.

Genera en la carpeta de la corrida:
  resumen.md                 tablas con todas las cifras (sirve de "vista de tabla" de cada figura)
  fig_e3_linea_tiempo.png    latencia de cada envío a 1 req/s + estado del circuito, por perfil
  fig_e5_dano_colateral.png  latencia de GET /api/despachos/{id} durante el pico, por perfil
  fig_e5_recursos.png        conexiones a BD en espera e hilos ocupados del orquestador
  fig_e5_resultados.png      resultado de las K sagas del pico con transportista lento

Uso:  python3 experiments/analyze.py experiments/results/<run_id>
Requiere matplotlib solo para las figuras (pip install matplotlib); el resumen sale igual sin él.
"""
from __future__ import annotations

import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

PERFILES = ["completo", "solo-timeout", "sin-patrones"]
NOMBRE_PERFIL = {"completo": "Con patrones (timeout + CB + bulkhead)",
                 "solo-timeout": "Solo timeout (sin CB ni bulkhead)",
                 "sin-patrones": "Sin patrones (contrafactual)"}
FASES_E3 = ["normal", "lento", "caido", "recuperado"]
RECHAZO_SIN_LLAMAR = {"CB_ABIERTO", "BULKHEAD_LLENO"}

# Paleta (validada con dataviz/scripts/validate_palette.js: 3 slots all-pairs y 4 slots adyacentes)
C = {"s1": "#2a78d6", "s2": "#eb6834", "s3": "#1baf7a", "s4": "#eda100",
     "surface": "#fcfcfb", "ink": "#0b0b0b", "ink2": "#52514e", "muted": "#898781",
     "grid": "#e1e0d9", "axis": "#c3c2b7", "band": "#f0efec",
     "good": "#0ca30c", "warning": "#fab219", "critical": "#d03b3b"}
COLOR_PERFIL = {"completo": C["s1"], "solo-timeout": C["s2"], "sin-patrones": C["s3"]}


# --------------------------------------------------------------------------- carga

def leer_csv(p: Path) -> list[dict]:
    if not p.exists():
        return []
    with p.open(encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def pct(xs, q):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    k = (len(xs) - 1) * q / 100
    i, f = int(k), k - int(k)
    return xs[i] if i + 1 >= len(xs) else xs[i] + (xs[i + 1] - xs[i]) * f


def fmt_ms(v):
    if v is None:
        return "—"
    return f"{v / 1000:.2f} s" if v >= 1000 else f"{v:.0f} ms"


def fmt_pct(a, b):
    return "—" if not b else f"{100 * a / b:.0f}%"


class Datos:
    def __init__(self, d: Path):
        self.dir = d
        self.reqs = leer_csv(d / "requests.csv")
        for r in self.reqs:
            r["lat"] = num(r["latencia_ms"])
            r["t"] = num(r["t_envio_s"])
        self.samples = leer_csv(d / "samples.csv")
        for s in self.samples:
            s["t"] = num(s["t_s"])
        self.fases = leer_csv(d / "phases.csv")
        for f in self.fases:
            f["ini"], f["fin"] = num(f["t_inicio_s"]), num(f["t_fin_s"])
        self.audits = json.loads((d / "audits.json").read_text(encoding="utf-8")) if (d / "audits.json").exists() else []
        self.meta = json.loads((d / "meta.json").read_text(encoding="utf-8")) if (d / "meta.json").exists() else {}

    def r(self, **kw):
        return [x for x in self.reqs if all(x.get(k) == v for k, v in kw.items())]

    def s(self, **kw):
        return [x for x in self.samples if all(x.get(k) == v for k, v in kw.items())]

    def fase(self, exp, perfil, nombre):
        for f in self.fases:
            if f["exp"] == exp and f["perfil"] == perfil and f["fase"] == nombre:
                return f
        return None

    def perfiles(self, exp):
        return [p for p in PERFILES if any(x["exp"] == exp and x["perfil"] == p for x in self.reqs)]

    def audit(self, exp, perfil):
        for a in self.audits:
            if a["exp"] == exp and a["perfil"] == perfil:
                return a
        return None


# --------------------------------------------------------------------------- métricas

def metricas_e3(D: Datos, perfil: str) -> dict:
    out = {}
    for fase in FASES_E3:
        rs = D.r(exp="E3", perfil=perfil, fase=fase, tipo="saga")
        c = Counter(x["resultado"] for x in rs)
        n = len(rs)
        llegaron = sum(v for k, v in c.items() if k not in RECHAZO_SIN_LLAMAR)
        sm = D.s(exp="E3", perfil=perfil, fase=fase)
        abiertos = sum(1 for s in sm if s["cb_estado"] in ("OPEN", "HALF_OPEN"))
        out[fase] = {"n": n, "ok": c.get("OK", 0), "cb": c.get("CB_ABIERTO", 0), "bh": c.get("BULKHEAD_LLENO", 0),
                     "timeout": c.get("TIMEOUT", 0), "t503": c.get("TRANSPORTISTA_503", 0),
                     "otros": n - sum(c.get(k, 0) for k in ("OK", "CB_ABIERTO", "BULKHEAD_LLENO", "TIMEOUT", "TRANSPORTISTA_503")),
                     "llegaron": llegaron, "p50": pct([x["lat"] for x in rs], 50), "p95": pct([x["lat"] for x in rs], 95),
                     "max": max((x["lat"] for x in rs), default=None),
                     "frac_cb_no_cerrado": (abiertos / len(sm)) if sm else None}
    # tiempo hasta que el circuito abre (desde el inicio de "lento") y hasta que vuelve a cerrar
    f_l, f_r = D.fase("E3", perfil, "lento"), D.fase("E3", perfil, "recuperado")
    sm = sorted(D.s(exp="E3", perfil=perfil), key=lambda s: s["t"])
    t_open = next((s["t"] - f_l["ini"] for s in sm if f_l and s["t"] >= f_l["ini"] and s["cb_estado"] == "OPEN"), None)
    t_close = None
    if f_r:
        t_close = next((s["t"] - f_r["ini"] for s in sm if s["t"] >= f_r["ini"] and s["cb_estado"] == "CLOSED"), None)
    out["t_apertura_s"], out["t_cierre_s"] = t_open, t_close
    return out


def metricas_e5(D: Datos, perfil: str, variante: str) -> dict:
    fase = f"{variante}-pico"
    sagas = D.r(exp="E5", perfil=perfil, fase=fase, tipo="saga")
    c = Counter(x["resultado"] for x in sagas)
    est = D.r(exp="E5", perfil=perfil, fase=fase, tipo="sonda-estado")
    est_antes = D.r(exp="E5", perfil=perfil, fase=f"{variante}-antes", tipo="sonda-estado")
    bff = D.r(exp="E5", perfil=perfil, fase=fase, tipo="sonda-bff")
    sm = D.s(exp="E5", perfil=perfil, fase=fase)
    vals = lambda k: [num(s[k]) for s in sm if num(s[k]) is not None]
    return {"n": len(sagas), "res": c, "saga_p50": pct([x["lat"] for x in sagas], 50),
            "saga_p95": pct([x["lat"] for x in sagas], 95), "saga_max": max((x["lat"] for x in sagas), default=None),
            "est_n": len(est), "est_p50": pct([x["lat"] for x in est], 50), "est_p95": pct([x["lat"] for x in est], 95),
            "est_max": max((x["lat"] for x in est), default=None),
            "est_antes_p95": pct([x["lat"] for x in est_antes], 95),
            "est_err": sum(1 for x in est if x["http"] != "200"),
            "est_lentas": sum(1 for x in est if x["lat"] is not None and x["lat"] > 1000),
            "bff_n": len(bff), "bff_503": sum(1 for x in bff if x["http"] != "200"),
            "bff_deg": sum(1 for x in bff if x["resultado"] == "OK_DEGRADADO"),
            "hik_pend_max": max(vals("hikari_pendientes"), default=None),
            "hik_act_max": max(vals("hikari_activas"), default=None),
            "tomcat_max": max(vals("tomcat_ocupados"), default=None),
            "bh_min": min(vals("bulkhead_disponibles"), default=None),
            "duracion_s": (D.fase("E5", perfil, fase)["fin"] - D.fase("E5", perfil, fase)["ini"]) if D.fase("E5", perfil, fase) else None}


# --------------------------------------------------------------------------- resumen

def tabla(cab: list[str], filas: list[list]) -> str:
    out = ["| " + " | ".join(cab) + " |", "|" + "|".join("---" for _ in cab) + "|"]
    out += ["| " + " | ".join("—" if v is None else str(v) for v in f) + " |" for f in filas]
    return "\n".join(out)


def resumen(D: Datos) -> tuple[str, list[str]]:
    L, alertas = [], []
    m = D.meta
    L.append(f"# Resumen de la corrida {m.get('run_id', D.dir.name)}\n")
    L.append(f"- Inicio: {m.get('inicio')} · fin: {m.get('fin')} · commit `{m.get('git')}` · modo rápido: {m.get('quick')}")
    L.append(f"- Latencia inducida: E3 = {m.get('e3_delay_ms')} ms · E5 = {m.get('e5_delay_ms')} ms con pico de {m.get('e5_k')} peticiones simultáneas")
    L.append("- Cada petición usa un `X-Client-Id` distinto, para que el rate limiter no interfiera (se mide aparte en E6).\n")
    L.append("## Verificación de perfiles\n")
    L.append(tabla(["Perfil", "Timeout agendar (log)", "Umbral fallo CB", "Bulkhead máx.", "Problemas"],
                   [[v["perfil"], v.get("agendar_read_timeout_ms"), v.get("cb_umbral_fallo"), v.get("bulkhead_max"),
                     "; ".join(v.get("problemas", []) + v.get("avisos", [])) or "ninguno"] for v in m.get("verificaciones", [])]))
    for v in m.get("verificaciones", []):
        if v.get("problemas"):
            alertas.append(f"El perfil {v['perfil']} no quedó bien aplicado: {'; '.join(v['problemas'])}")

    # Auditoría
    L.append("\n## Auditoría de consistencia (4 bases de datos)\n")
    L.append("C1 sin sagas intermedias · C2 COMPLETADA con sus 3 efectos · C3 ningún efecto activo fuera de una saga "
             "COMPLETADA · C4 conservación de stock · C5 stock reservado = reservas activas · C6 un retiro por envío\n")
    filas = []
    for a in D.audits:
        ctrl = " ".join(("✔" if c["ok"] else "✘") + c["control"].split()[0] for c in a["controles"])
        filas.append([a["exp"], a["perfil"], ", ".join(f"{k} {v}" for k, v in sorted(a["sagas"].items())),
                      f"{a['stock']['reservado']}", ctrl, "PASS" if a["todo_ok"] else "**FAIL**"])
        if not a["todo_ok"]:
            for c in a["controles"]:
                if not c["ok"]:
                    alertas.append(f"Auditoría {a['exp']}/{a['perfil']}: {c['control']} -> {c['detalle']}")
    L.append(tabla(["Exp.", "Perfil", "Sagas por estado", "Stock reservado", "Controles", "Resultado"], filas))
    total_sagas = sum(sum(a["sagas"].values()) for a in D.audits)
    huerf = sum(int(c["detalle"].split()[0]) for a in D.audits for c in a["controles"] if c["control"].startswith("C3"))
    L.append(f"\nTotal auditado: **{total_sagas} sagas**; efectos activos sin envío completado (C3): **{huerf}**.")

    # E1
    if D.r(exp="E1"):
        L.append("\n## E1 · Camino feliz e idempotencia (perfil completo)\n")
        filas = []
        for fase in ("secuencial", "reintento-mismo-id", "duplicados-concurrentes"):
            rs = D.r(exp="E1", fase=fase)
            c = Counter(x["resultado"] for x in rs)
            filas.append([fase, len(rs), ", ".join(f"{k} {v}" for k, v in c.most_common()),
                          fmt_ms(pct([x["lat"] for x in rs], 50)), fmt_ms(pct([x["lat"] for x in rs], 95))])
        L.append(tabla(["Fase", "Peticiones", "Resultados", "p50", "p95"], filas))
        a = D.audit("E1", "completo")
        if a:
            ex = a.get("extra", {})
            L.append(f"\nSagas COMPLETADA: {a['sagas'].get('COMPLETADA', 0)} (esperadas {ex.get('esperado_completadas')}); "
                     f"stock reservado {a['stock']['reservado']}; reintentos que devolvieron otro trackingNumber: "
                     f"{ex.get('reintentos_tracking_distinto')}.")

    # E2
    if D.r(exp="E2"):
        L.append("\n## E2 · Falla el último paso (tracking) → compensación en orden inverso\n")
        rs = D.r(exp="E2")
        c = Counter(x["resultado"] for x in rs)
        a = D.audit("E2", "completo") or {}
        ex = a.get("extra", {})
        L.append(tabla(["Sagas", "Respuestas", "Estado final", "Orden 3→2→1 en logs", "Stock reservado", "Retiros", "Trackings"],
                       [[len(rs), ", ".join(f"{k} {v}" for k, v in c.items()), ", ".join(f"{k} {v}" for k, v in a.get("sagas", {}).items()),
                         f"{ex.get('orden_correcto')} de {ex.get('n')}", a.get("stock", {}).get("reservado"),
                         ", ".join(f"{k} {v}" for k, v in a.get("retiros", {}).items()),
                         ", ".join(f"{k} {v}" for k, v in a.get("trackings", {}).items())]]))

    # E3
    perf3 = D.perfiles("E3")
    M3 = {p: metricas_e3(D, p) for p in perf3}
    if perf3:
        L.append("\n## E3 · Transportista lento y luego caído (1 req/s)\n")
        L.append("Las peticiones se asignan a la fase en que se enviaron. «Llegaron al transportista» = todo lo que no fue "
                 "rechazado por el CB o el bulkhead antes de llamar.\n")
        filas = []
        for p in perf3:
            for f in FASES_E3:
                x = M3[p][f]
                filas.append([p, f, x["n"], fmt_pct(x["ok"], x["n"]), x["cb"], x["timeout"], x["t503"], x["bh"] + x["otros"],
                              fmt_pct(x["llegaron"], x["n"]), fmt_ms(x["p50"]), fmt_ms(x["p95"]), fmt_ms(x["max"]),
                              "—" if x["frac_cb_no_cerrado"] is None else f"{100 * x['frac_cb_no_cerrado']:.0f}%"])
        L.append(tabla(["Perfil", "Fase", "n", "Completados", "Rechazo CB", "Timeout", "503 transp.", "Otros",
                        "Llegaron al transp.", "p50", "p95", "máx", "Tiempo con CB no cerrado"], filas))
        for p in perf3:
            to, tc = M3[p]["t_apertura_s"], M3[p]["t_cierre_s"]
            if to is None:
                L.append(f"\n- {p}: el circuito nunca abrió.")
            else:
                L.append(f"\n- {p}: el circuito abrió {to:.1f} s después de empezar la lentitud y volvió a CLOSED "
                         + (f"{tc:.1f} s después de la recuperación." if tc is not None else "— (no alcanzó a cerrar)."))
        if "completo" in M3 and M3["completo"]["t_apertura_s"] is None:
            alertas.append("E3/completo: el circuito nunca pasó a OPEN. Revisa la configuración de Resilience4j.")

    # E5
    perf5 = D.perfiles("E5")
    M5 = {(p, v): metricas_e5(D, p, v) for p in perf5 for v in ("control", "lento")}
    if perf5:
        L.append("\n## E5 · Pico de carga con transportista lento: bulkhead y daño colateral\n")
        L.append("«Sonda estado» = `GET /api/despachos/{id}` lanzada cada 200 ms sin esperar a la anterior (no usa al "
                 "transportista). «Sonda BFF» = vista agregada cada 500 ms. Métricas de las consultas enviadas mientras duraba el pico.\n")
        filas = []
        for p in perf5:
            for v in ("control", "lento"):
                x = M5[(p, v)]
                filas.append([p, v, x["n"], ", ".join(f"{k} {n}" for k, n in x["res"].most_common()), fmt_ms(x["saga_p95"]),
                              fmt_ms(x["saga_max"]), fmt_ms(x["est_antes_p95"]), fmt_ms(x["est_p50"]), fmt_ms(x["est_p95"]),
                              fmt_ms(x["est_max"]), f"{x['est_lentas']}/{x['est_n']}", f"{x['bff_503']}/{x['bff_n']}",
                              x["hik_pend_max"], x["tomcat_max"], x["bh_min"]])
        L.append(tabla(["Perfil", "Variante", "Sagas", "Resultados", "Saga p95", "Saga máx", "Sonda estado p95 (antes)",
                        "Sonda estado p50", "Sonda estado p95", "Sonda estado máx", "Sonda estado > 1 s", "BFF con error", "Conex. BD en espera (máx)",
                        "Hilos Tomcat ocupados (máx)", "Cupos bulkhead (mín)"], filas))
        sp = M5.get(("sin-patrones", "lento"))
        if sp and sp["res"].get("TIMEOUT", 0) > sp["n"] / 2:
            alertas.append("E5/sin-patrones: la mayoría de las sagas terminó en TIMEOUT. Con el timeout en 60 s eso no "
                           "debería pasar: probablemente el orquestador no tomó el perfil (¿imagen sin reconstruir?).")

    # E6
    if D.r(exp="E6"):
        L.append("\n## E6 · Rate limiting del gateway (30 req/min por cliente)\n")
        rs = D.r(exp="E6", fase="mismo-cliente")
        c = Counter(x["http"] for x in rs)
        otro = D.r(exp="E6", fase="otro-cliente")
        sin = D.r(exp="E6", fase="sin-token")
        L.append(tabla(["40 peticiones de un cliente", "Otro cliente", "Sin token"],
                       [[", ".join(f"HTTP {k}: {v}" for k, v in sorted(c.items())), otro[0]["http"] if otro else None,
                         sin[0]["http"] if sin else None]]))
        L.append("\n(404 = la petición pasó el gateway y el orquestador no encontró el id; 429 = cortada por el rate limiter.)")

    # Hallazgos calculados (frases para el informe)
    L.append("\n## Comparaciones calculadas\n")
    if "completo" in M3 and "sin-patrones" in M3:
        a, b = M3["completo"]["lento"], M3["sin-patrones"]["lento"]
        L.append(f"- E3, fase lenta: p50 de latencia {fmt_ms(a['p50'])} con patrones vs {fmt_ms(b['p50'])} sin patrones; "
                 f"llegaron al transportista {fmt_pct(a['llegaron'], a['n'])} vs {fmt_pct(b['llegaron'], b['n'])} de las peticiones.")
        a, b = M3["completo"]["caido"], M3["sin-patrones"]["caido"]
        L.append(f"- E3, fase caída: llegaron al transportista {fmt_pct(a['llegaron'], a['n'])} con patrones vs "
                 f"{fmt_pct(b['llegaron'], b['n'])} sin patrones.")
        a, b = M3["completo"]["recuperado"], M3["sin-patrones"]["recuperado"]
        L.append(f"- E3, recuperación: completados {fmt_pct(a['ok'], a['n'])} con patrones vs {fmt_pct(b['ok'], b['n'])} sin "
                 "patrones (costo del CB: espera la ventana OPEN antes de volver a probar).")
    if ("completo", "lento") in M5 and ("sin-patrones", "lento") in M5:
        a, b = M5[("completo", "lento")], M5[("sin-patrones", "lento")]
        factor = (b["est_p95"] / a["est_p95"]) if a["est_p95"] and b["est_p95"] else None
        comp = "" if not factor else (f" ({factor:.0f}× más lenta sin patrones)." if factor >= 1.5 else " (similar).")
        L.append(f"- E5, pico con lentitud: la consulta de estado (que no usa al transportista) tuvo p95 {fmt_ms(a['est_p95'])} con "
                 f"patrones vs {fmt_ms(b['est_p95'])} sin patrones" + (comp or ".") +
                 f" Consultas sobre 1 s: {a['est_lentas']}/{a['est_n']} vs {b['est_lentas']}/{b['est_n']}.")
        L.append(f"- E5, pico con lentitud: el pico duró {a['duracion_s']:.1f} s con patrones vs {b['duracion_s']:.1f} s sin patrones; "
                 f"conexiones a BD en espera (máx) {a['hik_pend_max']} vs {b['hik_pend_max']}.")

    L.append("\n## Señales de alerta\n")
    sin_resp = Counter(x["resultado"] for x in D.reqs if x["resultado"].startswith("SIN_RESPUESTA"))
    if sin_resp:
        alertas.append(f"Hubo peticiones sin respuesta HTTP (cliente): {dict(sin_resp)}")
    L += [f"- ⚠ {a}" for a in alertas] or ["- Ninguna."]
    L.append("\n## Figuras\n")
    L.append("- `fig_e3_linea_tiempo.png` · `fig_e5_dano_colateral.png` · `fig_e5_recursos.png` · `fig_e5_resultados.png`")
    L.append("- Las tablas de arriba son la vista de tabla de cada figura (mismos datos).")
    return "\n".join(L) + "\n", alertas


# --------------------------------------------------------------------------- figuras

def estilo(plt):
    plt.rcParams.update({
        "figure.facecolor": C["surface"], "axes.facecolor": C["surface"], "savefig.facecolor": C["surface"],
        "font.family": "sans-serif", "font.sans-serif": ["Segoe UI", "Helvetica", "Arial", "DejaVu Sans"],
        "font.size": 9, "text.color": C["ink"], "axes.labelcolor": C["ink2"], "axes.edgecolor": C["axis"],
        "axes.linewidth": 0.8, "xtick.color": C["muted"], "ytick.color": C["muted"], "axes.titlesize": 10,
        "axes.titleweight": "bold", "axes.titlecolor": C["ink"], "axes.grid": True, "grid.color": C["grid"],
        "grid.linewidth": 0.6, "grid.linestyle": "-", "axes.spines.top": False, "axes.spines.right": False,
        "legend.frameon": False, "legend.fontsize": 8.5,
    })


def bandas_fase(ax, D, exp, perfil, nombres, etiquetas=True):
    for i, n in enumerate(nombres):
        f = D.fase(exp, perfil, n)
        if not f:
            continue
        if i % 2 == 1:
            ax.axvspan(f["ini"], f["fin"], color=C["band"], zorder=0, lw=0)
        if etiquetas:
            ax.text((f["ini"] + f["fin"]) / 2, 1.0, {"caido": "caído"}.get(n, n), transform=ax.get_xaxis_transform(), ha="center", va="bottom",
                    fontsize=8, color=C["ink2"])


def fig_e3(D: Datos, plt, out: Path) -> Path | None:
    perf = D.perfiles("E3")
    if not perf:
        return None
    from matplotlib.patches import Patch
    from matplotlib.lines import Line2D
    fig = plt.figure(figsize=(10, 2.35 * len(perf) + 0.9))
    gs = fig.add_gridspec(len(perf) * 2, 1, height_ratios=[1, 0.09] * len(perf), hspace=0.35)
    grupos = [("Completado", lambda r: r == "OK", C["s1"], "o"),
              ("Rechazado sin llamar (CB abierto / bulkhead)", lambda r: r in RECHAZO_SIN_LLAMAR, C["s2"], "s"),
              ("Falló al llamar (timeout ≈2 s o 503 del transportista)", lambda r: r in ("TIMEOUT", "TRANSPORTISTA_503"), C["s3"], "^"),
              ("Otro", lambda r: r not in RECHAZO_SIN_LLAMAR | {"OK", "TIMEOUT", "TRANSPORTISTA_503"}, C["muted"], "x")]
    ax0 = None
    for i, p in enumerate(perf):
        ax = fig.add_subplot(gs[2 * i], sharex=ax0)
        ax0 = ax0 or ax
        rs = D.r(exp="E3", perfil=p, tipo="saga")
        bandas_fase(ax, D, "E3", p, FASES_E3, etiquetas=(i == 0))
        for nombre, f, col, mk in grupos:
            xs = [(r["t"], max(r["lat"], 1)) for r in rs if f(r["resultado"])]
            if xs:
                ax.scatter([a for a, _ in xs], [b for _, b in xs], s=16, c=col, marker=mk, linewidths=0.4,
                           edgecolors=C["surface"], zorder=3)
        ax.set_yscale("log")
        ax.set_ylim(1, 40000)
        ax.set_yticks([1, 10, 100, 1000, 10000])
        ax.set_yticklabels(["1 ms", "10 ms", "100 ms", "1 s", "10 s"])
        ax.set_ylabel("latencia")
        ax.set_title(NOMBRE_PERFIL[p], loc="left", pad=14 if i == 0 else 4)
        plt.setp(ax.get_xticklabels(), visible=False)
        # franja del estado del circuito
        axs = fig.add_subplot(gs[2 * i + 1], sharex=ax0)
        sm = sorted(D.s(exp="E3", perfil=p), key=lambda s: s["t"])
        col_estado = {"CLOSED": C["good"], "HALF_OPEN": C["warning"], "OPEN": C["critical"]}
        for a, b in zip(sm, sm[1:] + [None]):
            fin = b["t"] if b else a["t"] + 0.5
            axs.axvspan(a["t"], fin, color=col_estado.get(a["cb_estado"], C["grid"]), lw=0)
        axs.set_yticks([])
        axs.grid(False)
        axs.set_ylabel("CB", rotation=0, ha="right", va="center", fontsize=8, color=C["ink2"])
        for sp in axs.spines.values():
            sp.set_visible(False)
        if i < len(perf) - 1:
            plt.setp(axs.get_xticklabels(), visible=False)
    axs.set_xlabel("segundos desde el inicio del experimento (1 envío por segundo)")
    handles = [Line2D([], [], marker=mk, color=col, ls="", markersize=6, label=n) for n, _, col, mk in grupos]
    handles += [Patch(color=C["good"], label="CB cerrado"), Patch(color=C["warning"], label="CB semiabierto"),
                Patch(color=C["critical"], label="CB abierto")]
    fig.legend(handles=handles, loc="lower center", ncol=4, bbox_to_anchor=(0.5, -0.005))
    fig.suptitle("E3 · Latencia de cada «crear envío» con el transportista lento y luego caído", x=0.06, ha="left",
                 fontweight="bold", fontsize=11)
    fig.subplots_adjust(top=0.9, bottom=0.12, left=0.08, right=0.98)
    f = out / "fig_e3_linea_tiempo.png"
    fig.savefig(f, dpi=200)
    plt.close(fig)
    return f


def _rel_pico(D, exp, p, variante):
    f = D.fase(exp, p, f"{variante}-pico")
    return f["ini"] if f else 0


def fig_e5_colateral(D: Datos, plt, out: Path) -> Path | None:
    perf = D.perfiles("E5")
    if not perf:
        return None
    fig, axes = plt.subplots(1, len(perf), figsize=(10, 3.2), sharey=True)
    axes = axes if len(perf) > 1 else [axes]
    for ax, p in zip(axes, perf):
        t0 = _rel_pico(D, "E5", p, "lento")
        rs = [r for r in D.r(exp="E5", perfil=p, tipo="sonda-estado") if r["fase"].startswith("lento")]
        rs.sort(key=lambda r: r["t"])
        f = D.fase("E5", p, "lento-pico")
        if f:
            ax.axvspan(0, f["fin"] - t0, color=C["band"], lw=0, zorder=0)
            ax.text((f["fin"] - t0) / 2, 0.97, "pico", transform=ax.get_xaxis_transform(), ha="center", va="top",
                    fontsize=8, color=C["ink2"])
        xs = [r["t"] - t0 for r in rs]
        ys = [max(r["lat"], 1) for r in rs]
        ax.scatter(xs, ys, s=9, color=COLOR_PERFIL[p], zorder=3, linewidths=0)
        if ys:
            k = max(range(len(ys)), key=lambda j: ys[j])
            ax.scatter([xs[k]], [ys[k]], s=22, color=COLOR_PERFIL[p], zorder=4, edgecolors=C["surface"], linewidths=1)
            ax.annotate(f"máx {fmt_ms(ys[k])}", (xs[k], ys[k]), xytext=(6, 4), textcoords="offset points", fontsize=8,
                        color=C["ink"])
        ax.set_yscale("log")
        ax.set_ylim(1, 40000)
        ax.set_yticks([1, 10, 100, 1000, 10000])
        ax.set_yticklabels(["1 ms", "10 ms", "100 ms", "1 s", "10 s"])
        ax.set_title(NOMBRE_PERFIL[p], loc="left", fontsize=9)
        ax.set_xlabel("s desde el inicio del pico")
    axes[0].set_ylabel("latencia de cada consulta")
    fig.suptitle("E5 · Daño colateral: GET /api/despachos/{id} (no usa al transportista) durante un pico de "
                 f"{D.meta.get('e5_k', 40)} envíos\ncon el transportista lento ({D.meta.get('e5_delay_ms')} ms). "
                 "Cada punto es una consulta, lanzada cada 200 ms.", x=0.06, ha="left", fontweight="bold", fontsize=10)
    fig.subplots_adjust(top=0.78, bottom=0.17, left=0.09, right=0.98, wspace=0.08)
    f = out / "fig_e5_dano_colateral.png"
    fig.savefig(f, dpi=200)
    plt.close(fig)
    return f


def fig_e5_recursos(D: Datos, plt, out: Path) -> Path | None:
    perf = D.perfiles("E5")
    if not perf:
        return None
    metricas = [("hikari_pendientes", "Peticiones esperando una conexión a BD"),
                ("tomcat_ocupados", "Hilos de Tomcat ocupados")]
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.1))
    for ax, (k, titulo) in zip(axes, metricas):
        hay = False
        for p in perf:
            t0 = _rel_pico(D, "E5", p, "lento")
            sm = sorted((s for s in D.s(exp="E5", perfil=p) if s["fase"].startswith("lento") and num(s[k]) is not None),
                        key=lambda s: s["t"])
            if not sm:
                continue
            hay = True
            xs, ys = [s["t"] - t0 for s in sm], [num(s[k]) for s in sm]
            ax.plot(xs, ys, color=COLOR_PERFIL[p], lw=1.5, label=p, drawstyle="steps-post")
            jmax = max(range(len(ys)), key=lambda j: ys[j])
            if ys[jmax] > 0:
                ax.annotate(f"máx {ys[jmax]:.0f}", (xs[jmax], ys[jmax]), xytext=(4, 3), textcoords="offset points",
                            fontsize=7.5, color=C["ink2"])
        ax.set_title(titulo, loc="left", fontsize=9)
        ax.set_xlabel("s desde el inicio del pico")
        ax.axvline(0, color=C["axis"], lw=0.8)
        if not hay:
            ax.text(0.5, 0.5, "métrica no disponible", transform=ax.transAxes, ha="center", color=C["muted"])
        ax.set_ylim(bottom=0, top=max(ax.get_ylim()[1] * 1.12, 1))
    h, l = axes[0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper right", ncol=len(l), bbox_to_anchor=(0.98, 0.99))
    fig.suptitle("E5 · Recursos del orquestador durante el pico con el transportista lento", x=0.06, ha="left",
                 fontweight="bold", fontsize=10.5)
    fig.subplots_adjust(top=0.8, bottom=0.17, left=0.07, right=0.95, wspace=0.2)
    f = out / "fig_e5_recursos.png"
    fig.savefig(f, dpi=200)
    plt.close(fig)
    return f


def fig_e5_resultados(D: Datos, plt, out: Path) -> Path | None:
    perf = D.perfiles("E5")
    if not perf:
        return None
    cats = [("OK", "Completado", C["s1"]), ("BULKHEAD_LLENO", "Rechazado por bulkhead", C["s2"]),
            ("TIMEOUT", "Timeout", C["s3"]), ("OTRO", "Otro", C["s4"])]
    fig, ax = plt.subplots(figsize=(10, 0.65 * len(perf) + 1.3))
    for i, p in enumerate(perf):
        x = metricas_e5(D, p, "lento")
        c = Counter()
        for k, v in x["res"].items():
            c[k if k in ("OK", "BULKHEAD_LLENO", "TIMEOUT") else "OTRO"] += v
        izq = 0
        y = len(perf) - 1 - i
        for k, nombre, col in cats:
            v = c.get(k, 0)
            if not v:
                continue
            ax.barh(y, v - 0.15, left=izq + 0.075, height=0.5, color=col, lw=0)
            if v >= 3:
                ax.text(izq + v / 2, y, str(v), ha="center", va="center", fontsize=8,
                        color=C["ink"] if col == C["s4"] else "#ffffff")
            izq += v
        ax.text(izq + 0.6, y, f"p95 {fmt_ms(x['saga_p95'])} · máx {fmt_ms(x['saga_max'])}", va="center", fontsize=8,
                color=C["ink2"])
    ax.set_yticks(range(len(perf)))
    ax.set_yticklabels([NOMBRE_PERFIL[p] for p in reversed(perf)])
    ax.set_xlabel(f"sagas del pico ({D.meta.get('e5_k', 40)} simultáneas, transportista con {D.meta.get('e5_delay_ms')} ms)")
    ax.grid(axis="y", visible=False)
    ax.set_xlim(0, (D.meta.get("e5_k") or 40) * 1.35)
    from matplotlib.patches import Patch
    ax.legend(handles=[Patch(color=col, label=n) for _, n, col in cats], loc="upper center", ncol=4,
              bbox_to_anchor=(0.45, -0.32 if len(perf) > 1 else -0.5))
    ax.set_title("E5 · Qué pasó con cada envío del pico", loc="left")
    fig.subplots_adjust(left=0.27, right=0.98, bottom=0.33, top=0.86)
    f = out / "fig_e5_resultados.png"
    fig.savefig(f, dpi=200)
    plt.close(fig)
    return f


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    d = Path(sys.argv[1])
    D = Datos(d)
    if not D.reqs:
        print(f"No hay requests.csv en {d}")
        return 1
    texto, alertas = resumen(D)
    (d / "resumen.md").write_text(texto, encoding="utf-8")
    print(f"resumen.md escrito ({len(D.reqs)} peticiones, {len(D.samples)} muestras)")
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib no está instalado: se omiten las figuras (pip install matplotlib)")
        return 0
    estilo(plt)
    for fn in (fig_e3, fig_e5_colateral, fig_e5_recursos, fig_e5_resultados):
        f = fn(D, plt, d)
        if f:
            print(f"figura: {f.name}")
    for a in alertas:
        print(f"ALERTA: {a}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
