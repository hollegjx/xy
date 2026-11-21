"""生成独立 Grafana JSON 里的滚动窗口查询；Grafana 运行时不需要 Python。"""
import json
from pathlib import Path
import sys


def window_queries():
    window = "${window:raw}"
    job = 'job="xy-backend"'
    up = f"up{{{job}}}"
    start = f"process_start_time_seconds{{{job}}}"
    # 纳入窗口内出现过的所有目标，包括在左边界后消失的目标。
    targets = f"(present_over_time({up}[{window}]) or {up} offset {window})"
    stable = (f"(({start} == {start} offset {window}) and on(job,instance) ({up} == 1)"
              f" and on(job,instance) ({up} offset {window} == 1))")
    incomplete = f"({targets} unless on(job,instance) {stable})"

    def delta(metric, group, labels=""):
        counter = f"{metric}{{{job}{labels}}}"
        # 同一进程的 Micrometer 计数器只增不减。左边界未注册的标签从零计。
        return f"sum by ({group}) (({counter} - {counter} offset {window}) or {counter})"

    duration = delta("xy_ai_call_duration_seconds_sum", "model")
    count = delta("xy_ai_call_duration_seconds_count", "model")
    buckets = delta("xy_ai_model_latency_seconds_bucket", "le, model")
    errors = delta("xy_ai_outcomes_total", "model", ',status="error"')
    outcomes = delta("xy_ai_outcomes_total", "model")
    panels = {
        5: [delta("xy_ai_calls_total", "${group:raw}")],
        6: [delta("xy_ai_tokens_total", "type")],
        7: [f"{duration} / ({count} > 0)"],
        8: [f"histogram_quantile({q}, {buckets})" for q in (0.5, 0.9, 0.95, 0.99)],
        13: [delta("xy_ai_outcomes_total", "model, reason", ',status="error"')],
        14: [f"({errors} or on(model) (0 * {outcomes})) / ({outcomes} > 0)"],
    }
    return {panel: [f"({expr}) unless on() {incomplete}" for expr in queries]
            for panel, queries in panels.items()}


if __name__ == "__main__":
    path = Path(__file__).with_name("xy-ai-dashboard.json")
    original = raw = path.read_text()
    panels = {p["id"]: p for p in json.loads(raw)["panels"]}
    for panel, queries in window_queries().items():
        assert len(panels[panel]["targets"]) == len(queries)
        for target, query in zip(panels[panel]["targets"], queries):
            # 保留看板现有排版，只更新查询字符串。
            old = json.dumps(target["expr"], ensure_ascii=False)
            assert raw.count(old) == 1, f"cannot uniquely locate panel {panel} query"
            raw = raw.replace(old, json.dumps(query, ensure_ascii=False), 1)
    if "--check" in sys.argv:
        if raw != original:
            raise SystemExit("查询有更新，请运行 python3 ops/monitoring/dashboard_queries.py")
    elif raw != original:
        path.write_text(raw)
