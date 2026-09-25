"""在本机提供前端静态文件预览，并避免浏览器继续使用旧缓存

这里只预览页面样式，不提供应用接口
"""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse


class PreviewHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store, max-age=0")
        super().end_headers()

    def do_GET(self):
        if self.path.split("?", 1)[0] == "/__design":
            content = Path(__file__).with_name("design-preview.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return
        # 开发时刷新页面要读取最新文件，即使浏览器还留着上次服务的缓存校验信息
        for header in ("If-Modified-Since", "If-None-Match"):
            if header in self.headers:
                del self.headers[header]
        super().do_GET()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18766)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "src/main/resources/static"
    handler = partial(PreviewHandler, directory=str(root))
    with ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
        print(f"Static preview: http://127.0.0.1:{args.port}/html/login.html", flush=True)
        server.serve_forever()
