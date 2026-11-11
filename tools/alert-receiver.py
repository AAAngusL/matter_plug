#!/usr/bin/env python3
"""Matter Plug alert receiver. Python 3.8+, standard library only.

Usage:  python3 alert-receiver.py [--port 8080] [--bind 0.0.0.0] [--log alerts.log]
                                  [--notify-cmd "notify-send 'Matter Plug' {message}"]

Accepts a POST with a JSON body on any path, prints and logs it, runs --notify-cmd for
"started" and "reminder" alerts, and answers 200. Anything else gets 405 or 400.
{message} in --notify-cmd is replaced by the alert text, safely quoted for the shell.
"""
import argparse, json, shlex, subprocess, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = None


def log_line(text):
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"{stamp}  {text}"
    print(line, flush=True)
    if ARGS.log:
        with open(ARGS.log, "a", encoding="utf-8") as f:
            f.write(line + "\n")


class Handler(BaseHTTPRequestHandler):
    def _reply(self, code, text):
        body = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("body is not a JSON object")
        except Exception as e:
            log_line(f"BAD REQUEST from {self.client_address[0]}: {e}")
            return self._reply(400, '{"ok":false}')
        alert, state = data.get("alert", "?"), data.get("state", "?")
        message = str(data.get("message", ""))
        log_line(f"{alert}/{state}  battery={data.get('battery')}  plugged_in={data.get('charging')}  "
                 f"event_time={data.get('time')}  from={self.client_address[0]}  \"{message}\"")
        if state in ("started", "reminder") and ARGS.notify_cmd:
            cmd = ARGS.notify_cmd.replace("{message}", shlex.quote(message))
            try:
                subprocess.Popen(cmd, shell=True)
            except Exception as e:
                log_line(f"notify command failed: {e}")
        self._reply(200, '{"ok":true}')

    def do_GET(self):
        self._reply(405, '{"ok":false,"error":"POST only"}')

    def log_message(self, *a):  # silence the default access log
        pass


def main():
    global ARGS
    p = argparse.ArgumentParser(description="Matter Plug alert receiver")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--log", default="")
    p.add_argument("--notify-cmd", default="")
    ARGS = p.parse_args()
    server = ThreadingHTTPServer((ARGS.bind, ARGS.port), Handler)
    log_line(f"listening on {ARGS.bind}:{ARGS.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
