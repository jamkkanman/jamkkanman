"""Drives the Pleos Connect Virtual Console HTTP API from a script.

The console only accepts writes while a browser session sends a heartbeat every second,
so this keeps one alive in a background thread. Usage:
    python vc_drive.py "gear Drive" "speed 30" "sleep 3" "hazard on" "door fl open"
"""
import sys, threading, time, uuid, urllib.request, json, subprocess, os

ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")

BASE = "http://127.0.0.1:8088"
CLIENT = str(uuid.uuid4())

def _req(method, path):
    r = urllib.request.Request(BASE + path, method=method, data=b"" if method in ("POST", "PUT") else None)
    with urllib.request.urlopen(r, timeout=10) as resp:
        return resp.status, resp.read().decode("utf-8", "replace")

def heartbeat(stop):
    while not stop.is_set():
        try:
            _req("POST", f"/browser/session?client={CLIENT}")
        except Exception as e:
            print("heartbeat error", e, flush=True)
        stop.wait(1.0)

def put(path):
    try:
        status, body = _req("PUT", path)
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read().decode("utf-8", "replace")
    print(f"PUT {path} -> {status} {body[:160]}", flush=True)

speed_state = {"speed": 0, "accelerator": 0, "brake": 0, "steering": 0}

def run(cmd):
    p = cmd.split()
    k = p[0]
    if k == "sleep":
        time.sleep(float(p[1]))
    elif k == "gear":
        put(f"/vehicle/gear?gear={p[1]}")
    elif k == "speed":
        speed_state["speed"] = p[1]
        speed_state["accelerator"] = p[2] if len(p) > 2 else ("20" if float(p[1]) > 0 else "0")
        speed_state["brake"] = "0" if float(p[1]) > 0 else (p[3] if len(p) > 3 else "30")
        q = "&".join(f"{a}={b}" for a, b in speed_state.items())
        put(f"/vehicle/controls?{q}")
    elif k == "hazard":
        put(f"/vehicle/lights?feature=hazard&value={'true' if p[1] == 'on' else 'false'}")
    elif k == "door":
        put(f"/vehicle/body?door_{p[1]}={'true' if p[2] == 'open' else 'false'}")
    elif k == "turn":
        put(f"/vehicle/turn-signal?mode={p[1]}")
    elif k == "occupied":
        put(f"/vehicle/seat?kind=occupied&area={p[1]}&value={'true' if p[2] == 'yes' else 'false'}")
    elif k == "telltale":
        put(f"/vehicle/telltales/simulator?color=Red&type={p[1]}&state={p[2]}")
    elif k == "geo":
        # adb emu geo fix takes longitude first.
        subprocess.run([ADB, "-s", "emulator-5554", "emu", "geo", "fix", p[2], p[1]], capture_output=True)
        print(f"GEO {p[1]} {p[2]}", flush=True)
    elif k == "app":
        # app <action> [key=value ...] sends an intent extra to the app.
        extra = ["--es", "action", p[1]]
        for kv in p[2:]:
            a, b = kv.split("=", 1)
            extra += ["--es", a, b]
        subprocess.run([ADB, "-s", "emulator-5554", "shell", "am", "start", "-n", "app.jamkkanman/.MainActivity"] + extra, capture_output=True)
        print("APP", " ".join(p[1:]), flush=True)
    elif k == "state":
        s, body = _req("GET", "/vehicle/state")
        st = json.loads(body)
        print("STATE gear", st.get("gear", {}).get("actual"), "controls", {x: st["controls"].get(x) for x in ("speed_kph", "brake_pct", "emulator_active", "input_active")}, flush=True)
    else:
        print("unknown", cmd, flush=True)

if __name__ == "__main__":
    stop = threading.Event()
    t = threading.Thread(target=heartbeat, args=(stop,), daemon=True)
    t.start()
    time.sleep(1.5)
    for c in sys.argv[1:]:
        run(c)
    stop.set()
