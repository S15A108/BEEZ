"""Deterministic HTTP boundary for recovery tests; never described as a real model."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        response = {"model":"qa229-http-fixture", "result":{"candidates":[],"evidence_status":"SUFFICIENT"},
                    "usage":[{"request_key":"qa229-recovery", "feature":"CHANGE_IMPACT", "failed":False}]}
        encoded = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_args):
        pass


ThreadingHTTPServer(("0.0.0.0",8000),Handler).serve_forever()
