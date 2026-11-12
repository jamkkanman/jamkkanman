"""Cuts the emulator recordings into one demo video with Korean captions (ffmpeg).

    python tools/make_demo_video.py edit.json out.mp4

edit.json:
{
  "font": "C:/Windows/Fonts/malgunbd.ttf",
  "segments": [
    {"card": "제목", "sub": "부제", "seconds": 4},
    {"src": "scene1_wait.webm", "in": 1.0, "out": 34.0,
     "captions": [{"from": 0, "to": 6, "text": "장면 1. 신호 대기", "sub": "작은 설명"}]}
  ]
}
Caption times are relative to the segment's "in". Temporary files go next to the output.
"""
import json, os, subprocess, sys

W, H = 1920, 1080


def esc(text):
    # drawtext text escaping: backslash, colon, quote, percent.
    return text.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\u2019").replace("%", "\\%")


def font_arg(path):
    return path.replace("\\", "/").replace(":", "\\:")


def caption_filters(caps, font, font_light):
    out = []
    for c in caps:
        en = f"enable='between(t,{c['from']},{c['to']})'"
        y = 36
        out.append(
            f"drawbox=x=0:y=0:w=iw:h={170 if c.get('sub') else 110}:color=black@0.62:t=fill:{en}"
        )
        out.append(
            f"drawtext=fontfile='{font}':text='{esc(c['text'])}':fontsize=46:fontcolor=white:x=(w-text_w)/2:y={y}:{en}"
        )
        if c.get("sub"):
            out.append(
                f"drawtext=fontfile='{font_light}':text='{esc(c['sub'])}':fontsize=32:fontcolor=0xFFC845:x=(w-text_w)/2:y={y + 70}:{en}"
            )
    return out


def run(cmd):
    print(" ".join(cmd[:6]), "...", flush=True)
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stderr[-3000:])
        raise SystemExit(r.returncode)


def main():
    edit = json.load(open(sys.argv[1], encoding="utf-8"))
    out = sys.argv[2]
    base = os.path.dirname(os.path.abspath(sys.argv[1]))
    work = os.path.join(os.path.dirname(os.path.abspath(out)), "_parts")
    os.makedirs(work, exist_ok=True)
    font = font_arg(edit.get("font", "C:/Windows/Fonts/malgunbd.ttf"))
    font_light = font_arg(edit.get("font_light", "C:/Windows/Fonts/malgun.ttf"))
    parts = []
    for i, seg in enumerate(edit["segments"]):
        part = os.path.join(work, f"part{i:02d}.mp4")
        if "card" in seg:
            secs = seg.get("seconds", 4)
            vf = [
                f"drawtext=fontfile='{font}':text='{esc(seg['card'])}':fontsize=72:fontcolor=white:x=(w-text_w)/2:y=(h/2)-90",
            ]
            for j, line in enumerate(seg.get("lines", [seg.get("sub")] if seg.get("sub") else [])):
                vf.append(
                    f"drawtext=fontfile='{font_light}':text='{esc(line)}':fontsize=38:fontcolor=0xD0D5DD:x=(w-text_w)/2:y=(h/2)+20+{j * 60}"
                )
            run(["ffmpeg", "-y", "-f", "lavfi", "-i", f"color=c=0x0E1116:s={W}x{H}:r=30:d={secs}",
                 "-f", "lavfi", "-i", f"anullsrc=r=48000:cl=stereo",
                 "-t", str(secs), "-vf", ",".join(vf), "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                 "-c:a", "aac", "-b:a", "160k", "-shortest", part])
        else:
            src = os.path.join(base, seg["src"])
            vf = [f"scale={W}:{H}:flags=lanczos", "fps=30"] + caption_filters(seg.get("captions", []), font, font_light)
            af = "aresample=48000,volume={}".format(seg.get("gain", 1.0))
            run(["ffmpeg", "-y", "-ss", str(seg["in"]), "-to", str(seg["out"]), "-i", src,
                 "-vf", ",".join(vf), "-af", af, "-c:v", "libx264", "-crf", "18", "-preset", "medium",
                 "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-ac", "2", part])
        parts.append(part)
    lst = os.path.join(work, "list.txt")
    with open(lst, "w", encoding="utf-8") as f:
        for p in parts:
            f.write(f"file '{p.replace(os.sep, '/')}'\n")
    joined = os.path.join(work, "joined.mp4")
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", joined])
    # The emulator records the voice quietly and the film is mostly silence, which defeats one-pass loudnorm.
    # A fixed gain with a limiter brings speech near -16 LUFS ("master_gain_db", 24 dB for these recordings).
    gain = edit.get("master_gain_db", 24)
    run(["ffmpeg", "-y", "-i", joined, "-c:v", "copy", "-af", f"volume={gain}dB,alimiter=limit=0.89:level=disabled",
         "-ar", "48000", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-map_metadata", "-1", out])
    print("wrote", out)


if __name__ == "__main__":
    main()
