"""用 promtool 实际执行看板里的 PromQL。参数可传项目本地的 promtool 路径。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def test_dashboard(promtool):
    root = Path(__file__).resolve().parent
    subprocess.run([sys.executable, str(root / "dashboard_queries.py"), "--check"], check=True)
    subprocess.run([promtool, "check", "config", str(root / "prometheus.yml")], check=True)
    dashboard = json.loads((root / "xy-ai-dashboard.json").read_text())
    panels = {p["id"]: p for p in dashboard["panels"]}
    variables = {v["name"]: v for v in dashboard["templating"]["list"]}
    assert variables["ds"]["query"] == "prometheus"
    assert all(p["datasource"]["uid"] == "$ds" for p in panels.values())
    scenarios = []

    def scenario(interval, window):
        data, checks = [], []
        scenarios.append({"interval": interval, "input_series": data, "promql_expr_test": checks})

        def series(name, values, **labels):
            labels.setdefault("job", "xy-backend")
            tags = ",".join(k + "=" + json.dumps(v) for k, v in labels.items())
            data.append({"series": name + "{" + tags + "}", "values": values})

        def check(panel, samples, target=0, group="model", at=window):
            query = panels[panel]["targets"][target]["expr"].replace("${window:raw}", window).replace("${group:raw}", group)
            checks.append({"expr": query, "eval_time": at, "exp_samples": [
                {"labels": "{" + label + "}", "value": value} for label, value in samples]})
        return series, check

    series, check = scenario("1m", "5m")

    series("up", "1+0x5")
    series("process_start_time_seconds", "311+0x5")
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
    # 新用户第一次调用就失败：以前只看首个非零样本之后的变化，7 次中错 1 次竟显示 0%。
    series, check = scenario("10s", "1m")
    series("up", "1+0x9", instance="xy-1")
    series("process_start_time_seconds", "318+0x9", instance="xy-1")
    old = dict(instance="xy-1", model="m", client="native", user_id="11", app_id="101")
    new = dict(old, user_id="22", app_id="202")
    late = "_ _ _ 1 1 1 1 1 1 1"
    series("xy_ai_calls_total", "0 1 2 3 4 5 6 6 6 6", **old)
    series("xy_ai_outcomes_total", "0 1 2 3 4 5 6 6 6 6", **old, status="success", reason="STOP")
    series("xy_ai_calls_total", late, **new)
    series("xy_ai_outcomes_total", late, **new, status="error", reason="Timeout")
    series("xy_ai_tokens_total", "_ _ _ 9 9 9 9 9 9 9", **old, type="total")
    series("xy_ai_call_duration_seconds_count", late, **new, status="error")
    series("xy_ai_call_duration_seconds_sum", "_ _ _ 2 2 2 2 2 2 2", **new, status="error")
    for le in ["2", "+Inf"]:
        series("xy_ai_model_latency_seconds_bucket", late, instance="xy-1", model="m", client="native", status="error", le=le)
    check(5, [('user_id="11"', 6), ('user_id="22"', 1)], group="user_id")
    check(6, [('type="total"', 9)])
    check(7, [('model="m"', 2)])
    check(8, [('model="m"', 1)])
    check(13, [('model="m",reason="Timeout"', 1)])
    check(14, [('model="m"', 1 / 7)])
    check(14, [('model="m"', 0)], at="90s")  # 这次失败已离开窗口，后面还有三次成功。
    check(5, [], at="30s")  # 刚接入监控，没有窗口起点；累计卡仍能读到 4 次。
    check(2, [("", 4)], at="30s")

    # 有其他健康实例也不能用它的局部数据冒充全组失败率。
    for availability, starts in [("1+0x6", "311 311 311 319 319 319 319"),
                                 ("0 1 1 1 1 1 1", "311+0x6"),
                                 ("1 1 1 1 1 1 0", "311+0x6"),
                                 ("_ _ 1 1 1 1 1", "_ _ 319 319 319 319 319")]:
        series, check = scenario("10s", "1m")
        series("up", availability, instance="xy-2")
        series("process_start_time_seconds", starts, instance="xy-2")
        series("up", "1+0x6", instance="xy-1")
        series("process_start_time_seconds", "318+0x6", instance="xy-1")
        series("xy_ai_outcomes_total", "0+1x6", **old, status="success", reason="STOP")
        check(14, [])
    suite = {"evaluation_interval": "10s", "fuzzy_compare": True, "tests": scenarios}
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "dashboard-test.yml"
        path.write_text(json.dumps(suite))
        subprocess.run([promtool, "test", "rules", str(path)], check=True)
    print(f"{len(panels)} panels, {sum(len(s['promql_expr_test']) for s in scenarios)} PromQL evaluations passed")


if __name__ == "__main__":
    test_dashboard(sys.argv[1] if len(sys.argv) > 1 else "promtool")
