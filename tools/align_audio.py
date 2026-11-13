"""Puts each sound of a scene recording where the app says it started.

The emulator's gRPC sound arrived 0.7 to 4 s late on the recording PC, by an amount that changed from sentence to
sentence, while the picture (window capture) is on time. Each sentence's sound is intact, so this cuts the sound
delivered end to end (<scene>.raw.wav from record_window.py) into utterances and places each one at the time the
app logged for it:

- the app's own sentences at TextToSpeech onStart ("음성 시작" in the app log) plus 0.05 s,
- the recorded driver answer 0.25 s after demo_live.py asked the app to play it (its run log), or without the run log
  just before the first "음성 인식 시작" (that line came 0.3 to 2.2 s after the answer began on the recording PC).

Sounds the app did not log (the navigation app's voice) keep their own time minus the median delay of the matched
ones. Usage:

    python tools/align_audio.py <scene.mp4> <scene.applog.txt> [run_log.txt]

Writes <scene>.aligned.mp4 (same picture) and <scene>.align.json (what went where).
"""
import json, os, re, statistics, subprocess, sys, wave

RATE = 48000
LEAD = 0.05     # TextToSpeech onStart to sound
GAP = 1.0       # silence that separates two utterances (sentences keep shorter pauses inside)
FLOOR_DB = -62  # sound above this level counts as sound


def levels(wav):
    """RMS level (dB) of every 20 ms of the file."""
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", wav, "-af",
                          "asetnsamples=n=960,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-",
                          "-f", "null", "-"], capture_output=True, text=True).stdout
    vals = []
    for line in out.splitlines():
        if "RMS_level=" in line:
            v = line.split("=", 1)[1].strip()
            vals.append(-200.0 if v == "-inf" else float(v))
    return vals


def utterances(vals):
    segs, start, last = [], None, None
    for i, v in enumerate(vals):
        t = i * 0.02
        if v > FLOOR_DB:
            if start is None:
                start = t
            elif t - last > GAP:
                segs.append((start, last + 0.02))
                start = t
            last = t
    if start is not None:
        segs.append((start, last + 0.02))
    return [s for s in segs if s[1] - s[0] >= 0.25]


def app_events(applog, t0, device_minus_host):
    evs, last_listen = [], -99.0
    for line in open(applog, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*(\d+\.\d+)\s", line)
        if not m:
            continue
        t = float(m.group(1)) - device_minus_host - t0
        if "음성 시작 |" in line:
            evs.append({"t": round(t + LEAD, 3), "kind": "app sentence"})
        elif "VOICE 음성 인식 시작" in line:
            # In the earlier emulator recording (picture and sound in step) the answer began just before this line.
            if t - last_listen > 2.0:
                evs.append({"t": round(t - 0.15, 3), "kind": "driver answer"})
            last_listen = t
    return sorted(evs, key=lambda e: e["t"])


def answer_requests(runlog, t0, dur):
    """Times (on the recording) when demo_live.py sent the driver answer intent; its log uses local wall time."""
    import datetime
    day = datetime.datetime.fromtimestamp(t0).date()
    out = []
    for line in open(runlog, encoding="utf-8", errors="replace"):
        m = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d\d\d) app driver_voice", line)
        if not m:
            continue
        h, mi, s, ms = map(int, m.groups())
        when = datetime.datetime(day.year, day.month, day.day, h, mi, s, ms * 1000).timestamp()
        for shift in (0, 86400, -86400):
            if 0 <= when + shift - t0 <= dur:
                out.append(round(when + shift - t0 + 0.25, 3))
    return out


def main():
    scene, applog = sys.argv[1], sys.argv[2]
    base = scene[:-4]
    info = json.load(open(base + ".json", encoding="utf-8"))
    packets = json.load(open(base + ".packets.json", encoding="utf-8"))
    t0, dur = info["t0"], info["duration"]
    raw = wave.open(base + ".raw.wav", "rb").readframes(10 ** 9)

    # Delivery time of every raw sample, from the packet it came in.
    cum, starts = 0, []
    for ts, _, n in packets:
        starts.append((cum, ts))
        cum += n

    def delivered_at(sec):
        s = int(sec * RATE)
        lo, hi = 0, len(starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if starts[mid][0] <= s:
                lo = mid
            else:
                hi = mid - 1
        c, ts = starts[lo]
        return ts + (s - c) / RATE

    utts = [{"raw": u, "delivered": round(delivered_at(u[0]), 3)} for u in utterances(levels(base + ".raw.wav"))]
    evs = app_events(applog, t0, info["device_minus_host_s"])
    if len(sys.argv) > 3:
        asked = answer_requests(sys.argv[3], t0, dur)
        if asked:
            evs = [e for e in evs if e["kind"] != "driver answer"] + [{"t": t, "kind": "driver answer"} for t in asked]
            evs.sort(key=lambda e: e["t"])

    # In order: each logged start takes the next utterance delivered near it. An app sentence's sound is never
    # delivered before its start; the driver answer can be, because its line is logged once recognition begins.
    nxt = 0
    for e in evs:
        early = 0.3 if e["kind"] == "app sentence" else 3.0
        for i in range(nxt, len(utts)):
            u = utts[i]
            if e["t"] - early <= u["delivered"] < e["t"] + 8.0:
                u["at"], u["by"] = e["t"], e["kind"]
                nxt = i + 1
                break
        else:
            e["missing"] = True
    delays = [u["delivered"] - u["at"] for u in utts if "at" in u]
    late = statistics.median(delays) if delays else 0.0
    for u in utts:
        if "at" not in u:
            u["at"], u["by"] = round(u["delivered"] - late, 3), "own time minus median delay"

    need = int(round(dur * RATE)) * 4
    pcm = bytearray(need)
    utts.sort(key=lambda u: u["at"])
    for i, u in enumerate(utts):
        a, b = u["raw"]
        a, b = max(a - 0.05, 0), b + 0.25
        if i + 1 < len(utts):  # never run into the next sound
            b = min(b, a + (utts[i + 1]["at"] - 0.05 - (u["at"] - 0.05)))
        chunk = raw[int(a * RATE) * 4:int(b * RATE) * 4]
        at = int(round((u["at"] - 0.05) * RATE)) * 4
        lo = max(at, 0)
        chunk = chunk[lo - at:max(0, need - at)]
        pcm[lo:lo + len(chunk)] = chunk
    wav = base + ".aligned.wav"
    with wave.open(wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(RATE)
        wf.writeframes(bytes(pcm))
    out = base + ".aligned.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", scene, "-i", wav, "-map", "0:v", "-map", "1:a", "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out], check=True)
    os.remove(wav)
    report = {"events": evs, "utterances": [{"raw": [round(x, 2) for x in u["raw"]], "delivered": u["delivered"],
                                             "placed": u["at"], "by": u["by"]} for u in utts],
              "median_delay_s": round(late, 3)}
    json.dump(report, open(base + ".align.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for u in report["utterances"]:
        print(f"  {u['raw'][0]:6.2f}-{u['raw'][1]:6.2f} raw, delivered {u['delivered']:6.2f} -> placed {u['placed']:6.2f} ({u['by']})")
    for e in evs:
        if e.get("missing"):
            print(f"  no sound found for {e['kind']} at {e['t']:.2f}")
    print("wrote", out)


if __name__ == "__main__":
    main()
