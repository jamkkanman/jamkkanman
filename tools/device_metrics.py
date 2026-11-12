"""Summarizes on-device measurements from app logs (adb logcat -v time -s JKM:I) into docs/metrics/device.md.

    python -X utf8 tools/device_metrics.py <app_log.txt> [more logs ...] > docs/metrics/device.md

Reaction time: from the vehicle signal that triggered a sentence (callback time) to TextToSpeech onStart.
Judge time and replay results come from the in-app metrics screen (METRIC lines).
"""
import re, statistics, sys

sys.stdout.reconfigure(encoding="utf-8")
reactions = []          # (kind, ms, text)
metrics = []            # (run, line)
for run, path in enumerate(sys.argv[1:], start=1):
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"REACTION (\w+) (\d+)ms \"(.*)\"", line)
        if m:
            reactions.append((m.group(1), int(m.group(2)), m.group(3)))
        m = re.search(r"METRIC (.*)$", line)
        if m:
            metrics.append((run, m.group(1).strip()))

print("# 에뮬레이터 안에서 잰 값")
print()
print("Pleos Connect 에뮬레이터 2.1.0, 실차 신호 모드(Virtual Console과 adb로 입력)와 앱 지표 화면에서 기록한 로그를 요약했다.")
print()
alerts = [r for r in reactions if r[0] in ("INTENT_ALERT", "ALIGHT_ALERT", "REMIND")]
others = [r for r in reactions if r not in alerts]
print("## 반응 시간 (세우려는 신호 또는 응답에서 첫 음성까지)")
print()
print("| 묶음 | 횟수 | 중앙값 | 평균 | 최대 |")
print("|---|---|---|---|---|")
for name, rows in (("정차 안내 (첫 안내, 내리기 직전, 한 번 더)", alerts), ("응답 뒤 문장 (주차장 안내, 확인)", others)):
    if rows:
        v = [r[1] for r in rows]
        print(f"| {name} | {len(v)} | {statistics.median(v):.0f} ms | {statistics.mean(v):.0f} ms | {max(v)} ms |")
print()
print("측정 지점은 앱이 신호 콜백을 받은 시각과 TextToSpeech EventListener.onStart 시각의 차이다. 차량 버스에서 앱까지의 지연은 포함하지 않는다.")
print()
if metrics:
    print("## 앱 지표 화면 결과 (실행마다)")
    print()
    print("판정 시간 줄의 숫자는 평균, p99, 최대 순서다.")
    last = None
    for run, m in metrics:
        if run != last:
            print()
            print(f"실행 {run}")
            print()
            last = run
        print(f"- {m}")
