"""Records the emulator window (picture) and the emulator's sound (gRPC) into one MP4.

The emulator's built-in recorder (`adb emu screenrecord`) kept only about five new frames a second of the Pleos
driving view, and it slowed the guest. This captures the emulator window with Windows Graphics Capture (ffmpeg
`gfxcapture`), which keeps every frame the emulator draws, encodes it on the GPU (NVENC), and takes the sound from
the emulator's gRPC audio stream. Both carry wall-clock time, which lines them up.

    python tools/record_window.py <out.mp4>              # records until a line arrives on stdin (or stdin closes)
    python tools/record_window.py <out.mp4> --seconds 10

Needs ffmpeg 8 or later (gfxcapture, h264_nvenc), the Python packages grpcio and grpcio-tools, and the emulator
window shown (not minimized). Prints "recording t0=<unix seconds>" once the first frame arrives and writes
<out>.json with the start time and counts.
"""
import argparse, glob, json, os, re, subprocess, sys, threading, time, wave

SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
RATE = 48000


def stubs(gen):
    if not os.path.exists(os.path.join(gen, "emulator_controller_pb2_grpc.py")):
        import grpc_tools, grpc_tools.protoc
        os.makedirs(gen, exist_ok=True)
        inc = os.path.join(os.path.dirname(grpc_tools.__file__), "_proto")
        src = os.path.join(SDK, "emulator", "lib")
        rc = grpc_tools.protoc.main(["protoc", f"-I{src}", f"-I{inc}", f"--python_out={gen}", f"--grpc_python_out={gen}",
                                     os.path.join(src, "emulator_controller.proto")])
        if rc != 0:
            raise SystemExit("protoc failed")
    sys.path.insert(0, gen)
    import emulator_controller_pb2 as pb, emulator_controller_pb2_grpc as pbg
    return pb, pbg


def endpoint():
    """gRPC port and token of the running emulator, from its discovery file. The token is never printed."""
    files = sorted(glob.glob(os.path.join(os.environ["LOCALAPPDATA"], "Temp", "avd", "running", "pid_*.ini")),
                   key=os.path.getmtime)
    if not files:
        raise SystemExit("no running emulator")
    kv = {}
    for line in open(files[-1], encoding="utf-8", errors="replace"):
        if "=" in line:
            k, v = line.strip().split("=", 1)
            kv[k] = v
    return kv["grpc.port"], kv.get("grpc.token")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--seconds", type=float, default=0)
    ap.add_argument("--title", default="Pleos_Connect", help="regular expression for the emulator window title")
    ap.add_argument("--fps", type=int, default=30)
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    base = out[:-4]
    pb, pbg = stubs(os.path.join(os.path.dirname(out), "_grpc"))
    import grpc

    # Device clock minus host clock, so the app's logcat times can be put on this recording's timeline.
    adb = os.path.join(SDK, "platform-tools", "adb.exe")
    offs = []
    for _ in range(3):
        h1 = time.time()
        d = subprocess.run([adb, "-s", "emulator-5554", "shell", "date +%s.%N"], capture_output=True, text=True).stdout
        h2 = time.time()
        try:
            offs.append((float(d.strip()) - (h1 + h2) / 2, h2 - h1))
        except ValueError:
            pass
    device_offset = round(min(offs, key=lambda o: o[1])[0], 3) if offs else None

    port, token = endpoint()
    ch = grpc.insecure_channel(f"localhost:{port}")
    stub = pbg.EmulatorControllerStub(ch)
    md = [("authorization", f"Bearer {token}")] if token else []
    stop = threading.Event()

    packets = []
    acall = stub.streamAudio(pb.AudioFormat(samplingRate=RATE, channels=pb.AudioFormat.Stereo,
                                            format=pb.AudioFormat.AUD_FMT_S16,
                                            mode=getattr(pb.AudioFormat, os.environ.get("RECORD_AUDIO_MODE", "MODE_UNSPECIFIED"))),
                             metadata=md)

    def read_audio():
        try:
            for p in acall:
                packets.append((p.timestamp, p.audio, time.time()))
                if stop.is_set():
                    break
        except grpc.RpcError as e:
            if not stop.is_set():
                print("audio stream ended:", e.code(), flush=True)

    threading.Thread(target=read_audio, daemon=True).start()

    video = base + ".video.mp4"
    env = dict(os.environ)
    tmp = os.path.join(os.path.dirname(out), "_tmp")
    os.makedirs(tmp, exist_ok=True)
    env["TEMP"] = env["TMP"] = tmp
    ff = subprocess.Popen(
        ["ffmpeg", "-hide_banner", "-y", "-use_wallclock_as_timestamps", "1", "-f", "lavfi", "-i",
         f"gfxcapture=window_title={a.title}:capture_cursor=0:max_framerate=60",
         "-copyts", "-vf", "showinfo,setpts=PTS-STARTPTS", "-fps_mode", "cfr", "-r", str(a.fps),
         "-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "16", "-b:v", "0", "-an", video],
        stdin=subprocess.PIPE, stderr=subprocess.PIPE, env=env)

    t0 = {"value": None}
    first = threading.Event()
    errlog = []

    def read_err():
        pat = re.compile(r"n:\s*0\s.*?pts_time:([0-9.]+)")
        for raw in iter(ff.stderr.readline, b""):
            line = raw.decode("utf-8", "replace")
            if t0["value"] is None:
                m = pat.search(line)
                if m:
                    t0["value"] = float(m.group(1))
                    first.set()
            if "Parsed_showinfo" not in line:
                errlog.append(line)

    threading.Thread(target=read_err, daemon=True).start()
    if not first.wait(20):
        ff.kill()
        raise SystemExit("no frame from the emulator window:\n" + "".join(errlog[-15:]))
    print(f"recording t0={t0['value']:.3f}", flush=True)

    if a.seconds > 0:
        time.sleep(a.seconds)
    else:
        sys.stdin.readline()
    stop.set()
    try:
        ff.stdin.write(b"q")
        ff.stdin.flush()
    except OSError:
        pass
    try:
        ff.wait(30)
    except subprocess.TimeoutExpired:
        ff.kill()
    acall.cancel()

    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video],
                               capture_output=True, text=True).stdout.strip())
    # Sound: each packet goes where its wall-clock time falls after the first frame; gaps stay silent.
    need = int(round(dur * RATE)) * 4
    pcm = bytearray(need)
    placed = 0
    # The packets' own times ran 0.7 to 4 s late and unevenly on this PC, so the sound is also kept as delivered,
    # end to end, with each packet's time; tools/align_audio.py places it by the app's speech-start log instead.
    json.dump([[round(ts / 1e6 - t0["value"], 4), round(arr - t0["value"], 4), len(d) // 4] for ts, d, arr in packets],
              open(base + ".packets.json", "w"))
    with wave.open(base + ".raw.wav", "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(RATE)
        wf.writeframes(b"".join(d for _, d, _ in packets))
    for ts, data, _ in packets:
        at = int(round((ts / 1e6 - t0["value"]) * RATE)) * 4
        if at >= need or at + len(data) <= 0:
            continue
        lo = max(at, 0)
        chunk = data[lo - at: need - at]
        pcm[lo:lo + len(chunk)] = chunk
        placed += len(chunk)
    wav = base + ".wav"
    with wave.open(wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(RATE)
        wf.writeframes(bytes(pcm))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", video, "-i", wav, "-map", "0:v", "-map", "1:a", "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out], env=env, check=True)
    os.remove(video)
    os.remove(wav)
    info = {"t0": t0["value"], "duration": dur, "device_minus_host_s": device_offset, "audio_packets": len(packets),
            "audio_seconds_placed": round(placed / 4 / RATE, 2)}
    json.dump(info, open(base + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("wrote", out, json.dumps(info), flush=True)


if __name__ == "__main__":
    main()
