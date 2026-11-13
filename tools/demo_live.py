"""Runs the live demo scenes on the Pleos Connect emulator and records each from the emulator window.

Every input is a real emulator signal: Virtual Console HTTP API (speed, gear, door), `adb emu geo fix` (location),
and an adb intent that plays a recorded driver answer into SpeechToText. Usage:

    python tools/demo_live.py <points.json> <out_dir> [scene ...]

Scenes: wait, hydrant, busstop, metrics. Each scene is an MP4 from tools/record_window.py (window picture on the GPU
encoder, emulator sound over gRPC), so run this with a Python that has grpcio and grpcio-tools.
"""
import json, os, subprocess, sys, threading, time, uuid, urllib.request, urllib.error

ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = "emulator-5554"
VC = "http://127.0.0.1:8088"
CLIENT = str(uuid.uuid4())
R = 6371008.8


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def _req(method, path):
    r = urllib.request.Request(VC + path, method=method, data=b"" if method in ("POST", "PUT") else None)
    with urllib.request.urlopen(r, timeout=10) as resp:
        return resp.read().decode("utf-8", "replace")


def heartbeat(stop):
    while not stop.is_set():
        try:
            _req("POST", f"/browser/session?client={CLIENT}")
        except Exception as e:  # keep going; the next beat may succeed
            print("heartbeat", e, flush=True)
        stop.wait(1.0)


def put(path):
    for attempt in range(4):
        try:
            _req("PUT", path)
            return
        except urllib.error.HTTPError as e:
            print("PUT failed", path, e.code, flush=True)
            return
        except (urllib.error.URLError, ConnectionError, OSError) as e:  # the console dropped a connection once
            print("PUT retry", path, e, flush=True)
            time.sleep(0.3)


def log(msg):
    now = time.time()
    print(time.strftime("%H:%M:%S", time.localtime(now)) + f".{int(now * 1000) % 1000:03d}", msg, flush=True)


def speed(kmh):
    brake = 0 if kmh > 0 else 30
    acc = 20 if kmh > 0 else 0
    put(f"/vehicle/controls?speed={kmh}&accelerator={acc}&brake={brake}&steering=0")
    log(f"speed {kmh}")


def gear(g):
    put(f"/vehicle/gear?gear={g}")
    log(f"gear {g}")


def door(open_):
    put(f"/vehicle/body?door_fl={'true' if open_ else 'false'}")
    log(f"door {'open' if open_ else 'close'}")


def geo(p):
    adb("emu", "geo", "fix", f"{p[1]:.7f}", f"{p[0]:.7f}")


def offset(p, east, north):
    import math
    lat = p[0] + math.degrees(north / R)
    lon = p[1] + math.degrees(east / (R * math.cos(math.radians(p[0]))))
    return (lat, lon)


def app(action, **extras):
    args = ["shell", "am", "start", "-n", "app.jamkkanman/.MainActivity", "--es", "action", action]
    for k, v in extras.items():
        args += ["--es", k, v]
    adb(*args)
    log(f"app {action} {extras}")


def along(stop, heading, m):
    """Point [m] meters along the direction of travel from [stop] (negative is behind)."""
    import math
    h = math.radians(heading)
    return offset(stop, m * math.sin(h), m * math.cos(h))


def drive_in(stop, heading, cruise=32):
    """Drive toward [stop] along [heading] from 120 m behind and come to a halt on it."""
    gear("Drive")
    door(False)
    for k in (1.0, 0.8, 0.6):
        geo(along(stop, heading, -120 * k))
        if k == 1.0:
            speed(cruise)
        time.sleep(2.0)
    geo(along(stop, heading, -40)); time.sleep(1.5)
    speed(12)
    geo(along(stop, heading, -10)); time.sleep(1.5)
    geo(stop)
    speed(0)


def drive_out(stop, heading):
    speed(20)
    for d in (15, 35, 60):
        geo(along(stop, heading, d))
        time.sleep(2.0)


NO_RECORD = os.environ.get("NO_RECORD") == "1"
# The emulator's own recorder (`adb emu screenrecord`) kept about five new frames a second of the Pleos driving
# view and slowed the guest, so the scenes are recorded from the emulator window instead (record_window.py).
RECORDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "record_window.py")
_rec = {}


def record_start(path):
    if NO_RECORD:
        log("record skipped")
        return
    adb("logcat", "-c")  # the scene's app log is kept next to the recording (align_audio.py reads it)
    p = subprocess.Popen([sys.executable, RECORDER, path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         text=True, encoding="utf-8", errors="replace")
    line = p.stdout.readline().strip()  # "recording t0=..." once the first frame is in
    if not line.startswith("recording"):
        raise SystemExit(f"recorder did not start: {line}")
    _rec["p"], _rec["path"] = p, path
    log(f"record start {path} ({line})")


def record_stop():
    if NO_RECORD:
        return
    p = _rec.pop("p")
    p.stdin.write("stop\n")
    p.stdin.close()
    tail = p.stdout.read().strip()
    p.wait()
    with open(_rec["path"][:-4] + ".applog.txt", "w", encoding="utf-8") as f:
        f.write(adb("logcat", "-d", "-v", "epoch", "-s", "JKM:I").stdout)
    log(f"record stop ({tail.splitlines()[-1] if tail else 'no output'})")


def tap_text(label):
    """Taps the first on-screen element whose text is [label] (uiautomator dump). Returns True when found."""
    import re
    os.environ["MSYS_NO_PATHCONV"] = "1"
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1).strip() == label:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            log(f"tap '{label}'")
            return True
    return False


def clear_navi():
    """Ends any route left from a previous scene. NaviHelper.cancelRoute did not end guidance on this emulator,
    so the navigation app is restarted and its 'continue the previous route?' dialog is answered with 'end'."""
    adb("shell", "am", "force-stop", "ai.umos.maps.android.navigation.app")
    time.sleep(1.5)
    adb("shell", "am", "start", "-n", "ai.umos.maps.android.navigation.app/.MainActivity")
    # One or two UI dumps only: repeated dumps load the system server.
    for wait in (4.0, 3.0):
        time.sleep(wait)
        if tap_text("안내 종료"):
            time.sleep(2.0)
            return
    log("no route to end")


def reset(start=None):
    if start is not None:
        # Put the car where the drive will begin first, so the app's judgment from the previous scene's
        # stop is replaced before recording (it stays on screen while driving otherwise).
        geo(start)
    speed(0)
    clear_navi()
    app("live")
    app("clear_silence")
    gear("Drive")
    door(False)
    speed(0)
    time.sleep(2)


def scene_wait(pts, out):
    stop = (pts["crosswalk"]["lat"], pts["crosswalk"]["lon"])
    heading = pts["crosswalk"].get("headingDeg", 90.0)
    reset(along(stop, heading, -120))
    record_start(os.path.join(out, "scene1_wait.mp4"))
    time.sleep(1.5)
    drive_in(stop, heading)
    time.sleep(14)          # red light: speed 0, gear D, door closed
    app("live_view")        # open the app at the light to show the decision log (silent by design)
    time.sleep(8)
    drive_out(stop, heading)
    time.sleep(3)
    record_stop()


def scene_hydrant(pts, out):
    stop = (pts["hydrant"]["lat"], pts["hydrant"]["lon"])
    heading = pts["hydrant"].get("headingDeg", 90.0)
    reset(along(stop, heading, -120))
    record_start(os.path.join(out, "scene2_hydrant.mp4"))
    time.sleep(1.5)
    drive_in(stop, heading)
    time.sleep(4.5)
    gear("Park")
    time.sleep(9)           # first sentence
    app("driver_voice", asset="demo_voice/reply_yes.wav")
    time.sleep(9)           # parking sentence and route change; navigation comes to the front
    app("live_view")
    time.sleep(5)
    record_stop()


def scene_busstop(pts, out):
    stop = (pts["busStop"]["lat"], pts["busStop"]["lon"])
    heading = pts["busStop"].get("headingDeg", 90.0)
    reset(along(stop, heading, -120))
    record_start(os.path.join(out, "scene3_busstop.mp4"))
    time.sleep(1.5)
    drive_in(stop, heading)
    time.sleep(5)
    door(True)
    time.sleep(7)           # alighting sentence
    app("driver_voice", asset="demo_voice/reply_soon.wav")
    time.sleep(4)
    door(False)
    time.sleep(48)          # 50 s after the answer: one more reminder
    time.sleep(8)
    drive_out(stop, heading)
    time.sleep(2)
    record_stop()


def scene_metrics(pts, out):
    app("live")
    time.sleep(1)
    record_start(os.path.join(out, "scene4_metrics.mp4"))
    time.sleep(1)
    app("metrics")
    time.sleep(25)
    record_stop()


SCENES = {"wait": scene_wait, "hydrant": scene_hydrant, "busstop": scene_busstop, "metrics": scene_metrics}

if __name__ == "__main__":
    pts = json.load(open(sys.argv[1], encoding="utf-8"))
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    names = sys.argv[3:] or list(SCENES)
    adb("forward", "tcp:8088", "tcp:8088")
    stop = threading.Event()
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    time.sleep(1.5)
    try:
        for n in names:
            log(f"=== scene {n}")
            SCENES[n](pts, out)
            time.sleep(2)
    finally:
        stop.set()
