"""Builds app/src/main/assets/data/facilities.json from the collected demo-area public data.

    python tools/build_dataset.py <demo_facilities.json> <assets/data/facilities.json>

Input rows: {"id", "type": HYDRANT | BUS_STOP | CROSSWALK | CHILD_ZONE | PARKING, "name", "lat", "lon", "source", ...}.
Checks coordinates and required fields, drops duplicate positions of the same type (within 0.5 m), keeps only
public parking with opening hours, and writes a compact, stable (sorted) file so the app and the metrics read the same data.
"""
import json, math, sys

REQ = ("id", "type", "lat", "lon")
TYPES = {"HYDRANT", "BUS_STOP", "CROSSWALK", "CHILD_ZONE", "PARKING"}
KEEP = {
    "HYDRANT": ("id", "type", "name", "lat", "lon", "source"),
    "BUS_STOP": ("id", "type", "name", "lat", "lon", "source"),
    "CROSSWALK": ("id", "type", "name", "lat", "lon", "lengthM", "widthM", "source"),
    "CHILD_ZONE": ("id", "type", "name", "lat", "lon", "radiusM", "source"),
    "PARKING": ("id", "type", "name", "lat", "lon", "address", "mgmtNo", "weekday", "saturday", "holiday",
                "feeBasic", "capacity", "source"),
}


def dist(a, b):
    r = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1, math.sqrt(h)))


def main():
    rows = json.load(open(sys.argv[1], encoding="utf-8"))
    if isinstance(rows, dict):
        rows = rows.get("items") or rows.get("facilities") or []
    out, dropped = [], []
    for r in rows:
        t = r.get("type")
        if t not in TYPES or any(r.get(k) in (None, "") for k in REQ):
            dropped.append((r.get("id"), "missing field or unknown type"))
            continue
        lat, lon = float(r["lat"]), float(r["lon"])
        if not (33.0 < lat < 39.0 and 124.0 < lon < 132.0):
            dropped.append((r["id"], "outside Korea"))
            continue
        if t == "PARKING" and not (r.get("weekday") or r.get("saturday") or r.get("holiday")):
            dropped.append((r["id"], "parking without opening hours"))
            continue
        if any(o["type"] == t and dist((lat, lon), (o["lat"], o["lon"])) < 0.5 for o in out):
            dropped.append((r["id"], "duplicate position"))
            continue
        row = {k: r[k] for k in KEEP[t] if k in r and r[k] not in (None, "")}
        row["lat"], row["lon"] = round(lat, 7), round(lon, 7)
        out.append(row)
    out.sort(key=lambda o: (o["type"], o["id"]))
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")
    counts = {}
    for o in out:
        counts[o["type"]] = counts.get(o["type"], 0) + 1
    print("kept", counts, "dropped", len(dropped))
    for d in dropped[:20]:
        print("  drop", d)


if __name__ == "__main__":
    main()
