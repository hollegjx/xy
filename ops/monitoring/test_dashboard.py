"""用 promtool 实际执行看板里的 PromQL。参数可传项目本地的 promtool 路径。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def test_dashboard(promtool):
    root = Path(__file__).resolve().parent
    subprocess.run([promtool, "check", "config", str(root / "prometheus.yml")], check=True)
    dashboard = json.loads((root / "xy-ai-dashboard.json").read_text())
    panels = {p["id"]: p for p in dashboard["panels"]}
    variables = {v["name"]: v for v in dashboard["templating"]["list"]}
    assert variables["ds"]["query"] == "prometheus"
    assert all(p["datasource"]["uid"] == "$ds" for p in panels.values())
    data, checks = [], []

    def series(name, values, **labels):
        labels.setdefault("job", "xy-backend")
        tags = ",".join(k + "=" + json.dumps(v) for k, v in labels.items())
        data.append({"series": name + "{" + tags + "}", "values": values})

    def check(panel, samples, target=0, group="model"):
        query = panels[panel]["targets"][target]["expr"].replace("${window:raw}", "5m").replace("${group:raw}", group)
        checks.append({"expr": query, "eval_time": "5m", "exp_samples": [
            {"labels": "{" + label + "}", "value": value} for label, value in samples]})

    series("up", "1+0x5")
    series("xy_ai_calls_total", "0+1000x5", job="another-backend", model="unrelated")
    a = dict(model="m", client="native", user_id="11", app_id="101")
    b = dict(model="r", client="routing", user_id="22", app_id="none")
    for labels, requests, seconds in [(a, 3, 6), (b, 1, 1)]:
        series("xy_ai_calls_total", f"0+{requests}x5", **labels)
        series("xy_ai_outcomes_total", f"0+{2 if labels == a else 1}x5", **labels, status="success", reason="STOP")
        series("xy_ai_call_duration_seconds_sum", f"0+{seconds}x5", **labels)
        series("xy_ai_call_duration_seconds_count", f"0+{requests}x5", **labels)
    series("xy_ai_outcomes_total", "0+1x5", **a, status="error", reason="RateLimitException")
    for kind, per_minute in [("input", 6), ("output", 3), ("total", 9)]:
        series("xy_ai_tokens_total", f"0+{per_minute}x5", **a, type=kind)
    for bound, count in [("1", 1), ("2", 2), ("3", 3), ("+Inf", 3)]:
        series("xy_ai_model_latency_seconds_bucket", f"0+{count}x5", model="m", client="native", status="success", le=bound)

    for panel, value in [(1, 1), (2, 20), (3, 5), (4, 45)]:
        check(panel, [("", value)])
    check(5, [('model="m"', 15), ('model="r"', 5)])
    check(5, [('user_id="11"', 15), ('user_id="22"', 5)], group="user_id")
    check(5, [('app_id="101"', 15), ('app_id="none"', 5)], group="app_id")
    check(6, [('type="input"', 30), ('type="output"', 15), ('type="total"', 45)])
    check(7, [('model="m"', 2), ('model="r"', 1)])
    for index, value in enumerate([1.5, 2.7, 2.85, 2.97]):
        check(8, [('model="m"', value)], index)
    check(9, [('app_id="101"', 15)])
    check(10, [('user_id="11"', 15), ('user_id="22"', 5)])
    check(11, [('app_id="101"', 45)])
    check(12, [('user_id="11"', 45)])
    check(13, [('model="m",reason="RateLimitException"', 5)])
    check(14, [('model="m"', 1 / 3), ('model="r"', 0)])
    suite = {"evaluation_interval": "1m", "fuzzy_compare": True, "tests": [
        {"interval": "1m", "input_series": data, "promql_expr_test": checks}]}
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "dashboard-test.yml"
        path.write_text(json.dumps(suite))
        subprocess.run([promtool, "test", "rules", str(path)], check=True)
    print(f"{len(panels)} panels, {len(checks)} PromQL evaluations passed")


if __name__ == "__main__":
    test_dashboard(sys.argv[1] if len(sys.argv) > 1 else "promtool")
