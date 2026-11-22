"""隔离验收的模型协议替身；统计由真实 XY 应用的监听器产生。"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class Model(BaseHTTPRequestHandler):
    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path != "/v1/chat/completions" or payload.get("model") != "monitor-fixture":
            self.send_error(404)
            return
        if not payload.get("stream"):
            status, mime = 400, "application/json"
            body = json.dumps({"error": {"message": "routing fixture failure"}})
        else:
            status, mime = 200, "text/event-stream"
            content = "<!doctype html><html><head><title>Example</title></head><body>XY metrics</body></html>"
            chunks = [
                {"choices": [{"index": 0, "delta": {"role": "assistant", "content": content}}]},
                {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
                {"choices": [], "usage": {"prompt_tokens": 9, "completion_tokens": 3, "total_tokens": 12}},
            ]
            body = "".join("data: " + json.dumps({"id": "example", "model": "monitor-fixture", **chunk})
                           + "\n\n" for chunk in chunks) + "data: [DONE]\n\n"
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    ThreadingHTTPServer(("", 8200), Model).serve_forever()
