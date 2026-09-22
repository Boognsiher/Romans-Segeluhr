"""Prueft die Speed-Dip-Hypothese aus docs/Erweiterung_Windschaetzung_Robust.md
(Abschnitt "Empirische Pruefung") gegen die vorhandenen Diagnose-Logs:
Wende = starker Geschwindigkeitseinbruch beim Durch-den-Wind-Gehen,
Halse = kaum Einbruch -- rein aus cog_deg/sog_kn, ohne windDir zu brauchen.

Ablauf: steady-Kurs-Segmente aehnlich CourseTracker.steady() erkennen, bei
jedem Kurswechsel >= TACK_CHANGE_MIN_DEG den Punkt staerkster Drehrate
suchen und dort den Geschwindigkeitseinbruch relativ zur Baseline (Mittel
der beiden angrenzenden steady-Segmente) messen. Als Wende/Halse-Label dient
NICHT der Kurswinkel selbst, sondern dieselbe AWA-Klassifikation wie
WindEngine.tickContinuous() (< 90 Grad AWA = Wende, sonst Halse), aus dem im
Log mitgeschriebenen wind_dir_deg zum Zeitpunkt des Manoevers.

Bekannter Vorbehalt (siehe Doku): wind_dir_deg in genau diesen beiden Logs
kann durch den erst am 17.08. gefixten Windshift-Plausibilitaets-Bug
zeitweise verfaelscht sein -- das Label ist damit selbst mit Vorsicht zu
geniessen, nicht nur das Messergebnis.
"""
import csv
import math
import sys

STEADY_WINDOW = 8                       # Sekunden, s. CourseTracker
STEADY_MAX_DEV = 8.0                    # Constants.STEADY_COURSE_MAX_DEV
MIN_SPEED_KN = 1.5                      # Constants.MIN_SPEED_KN
TACK_CHANGE_MIN_DEG = 50.0              # Constants.TACK_CHANGE_MIN_DEG
TACK_VS_GYBE_AWA_THRESHOLD_DEG = 90.0   # Constants.TACK_VS_GYBE_AWA_THRESHOLD_DEG
PEAK_WINDOW_S = 3                       # +/- Sekunden um den Punkt max. Drehrate


def ang_diff(a, b):
    return (a - b + 180) % 360 - 180


def circ_mean(vals):
    sx = sum(math.sin(math.radians(v)) for v in vals)
    sy = sum(math.cos(math.radians(v)) for v in vals)
    if sx == 0 and sy == 0:
        return None
    return math.degrees(math.atan2(sx, sy)) % 360


def circ_spread(vals, mean):
    return max(abs(ang_diff(v, mean)) for v in vals)


def load(path):
    rows = []
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            try:
                ts = int(row["ts_epoch_ms"])
                sog = float(row["sog_kn"]) if row["sog_kn"] else None
                cog = float(row["cog_deg"]) if row["cog_deg"] else None
                valid = row["gps_valid"] == "true"
                wd = float(row["wind_dir_deg"]) if row["wind_dir_deg"] else None
                calibrated = row["wind_calibrated"] == "true"
            except (ValueError, KeyError):
                continue
            if sog is None or cog is None or not valid:
                continue
            rows.append(dict(ts=ts, sog=sog, cog=cog, wd=wd if calibrated else None))
    return rows


def find_steady_segments(rows):
    n = len(rows)
    steady_flags = [False] * n
    for i in range(n):
        window = rows[max(0, i - STEADY_WINDOW + 1) : i + 1]
        if len(window) < STEADY_WINDOW:
            continue
        cogs = [w["cog"] for w in window]
        sogs = [w["sog"] for w in window]
        if min(sogs) < MIN_SPEED_KN:
            continue
        m = circ_mean(cogs)
        if m is not None and circ_spread(cogs, m) <= STEADY_MAX_DEV:
            steady_flags[i] = True

    segments, cur_start = [], None
    for i in range(n):
        if steady_flags[i] and cur_start is None:
            cur_start = i
        elif not steady_flags[i] and cur_start is not None:
            segments.append((cur_start, i - 1))
            cur_start = None
    if cur_start is not None:
        segments.append((cur_start, n - 1))

    result = []
    for s, e in segments:
        cogs = [rows[j]["cog"] for j in range(s, e + 1)]
        sogs = [rows[j]["sog"] for j in range(s, e + 1)]
        wds = [rows[j]["wd"] for j in range(s, e + 1) if rows[j]["wd"] is not None]
        result.append(
            dict(
                s=s, e=e,
                cog=circ_mean(cogs),
                sog=sum(sogs) / len(sogs),
                wd=circ_mean(wds) if wds else None,
            )
        )
    return result


def analyze(path, label):
    rows = load(path)
    segs = find_steady_segments(rows)
    print(f"\n=== {label} ===")

    labeled = []
    for i in range(len(segs) - 1):
        a, b = segs[i], segs[i + 1]
        diff = abs(ang_diff(b["cog"], a["cog"]))
        gap_s = (rows[b["s"]]["ts"] - rows[a["e"]]["ts"]) / 1000.0
        if diff < TACK_CHANGE_MIN_DEG or gap_s > 180:
            continue

        idxs = list(range(a["e"], b["s"] + 1))
        if len(idxs) < 3:
            continue
        turn_rates = []
        for k in range(1, len(idxs)):
            j0, j1 = idxs[k - 1], idxs[k]
            dt = (rows[j1]["ts"] - rows[j0]["ts"]) / 1000.0
            if dt > 0:
                turn_rates.append((j1, abs(ang_diff(rows[j1]["cog"], rows[j0]["cog"])) / dt))
        if not turn_rates:
            continue
        peak_idx, _ = max(turn_rates, key=lambda x: x[1])
        peak_ts = rows[peak_idx]["ts"]

        window_sogs = [r["sog"] for r in rows if abs(r["ts"] - peak_ts) <= PEAK_WINDOW_S * 1000]
        if not window_sogs:
            continue
        baseline = (a["sog"] + b["sog"]) / 2.0
        if baseline <= 0:
            continue
        drop_pct = (1 - min(window_sogs) / baseline) * 100

        wd = b["wd"] or a["wd"]
        is_tack = abs(ang_diff(b["cog"], wd)) < TACK_VS_GYBE_AWA_THRESHOLD_DEG if wd is not None else None
        if is_tack is not None:
            labeled.append(dict(diff=diff, drop_pct=drop_pct, is_tack=is_tack))

    tacks = [m for m in labeled if m["is_tack"]]
    gybes = [m for m in labeled if not m["is_tack"]]

    def avg(key, lst):
        return sum(x[key] for x in lst) / len(lst) if lst else float("nan")

    def med(key, lst):
        v = sorted(x[key] for x in lst)
        return v[len(v) // 2] if v else float("nan")

    print(f"n={len(labeled)} Manoever, Fenster = Peak-Drehrate +/-{PEAK_WINDOW_S}s")
    print(f"WENDE (n={len(tacks)}): Drop Mittel={avg('drop_pct', tacks):.1f}%  Median={med('drop_pct', tacks):.1f}%")
    print(f"HALSE (n={len(gybes)}): Drop Mittel={avg('drop_pct', gybes):.1f}%  Median={med('drop_pct', gybes):.1f}%")


if __name__ == "__main__":
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    analyze(f"{base}/diagnose_20260815_140808.csv", "15.08. erster Toern")
    analyze(f"{base}/diagnose_20260816_103742.csv", "16.08. zweiter Toern")
