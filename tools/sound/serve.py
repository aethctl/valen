# SPDX-License-Identifier: GPL-3.0-only
"""Three origins for the Web Audio check: the page, media with CORS, media without."""
import http.server
import socketserver
import sys
import threading

ROOT = sys.argv[1]
BASE = int(sys.argv[2])


def handler(cors):
    class H(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=ROOT, **k)

        def end_headers(self):
            if cors:
                self.send_header("Access-Control-Allow-Origin", "*")
            super().end_headers()

        def log_message(self, *a):
            pass

    return H


class Server(socketserver.ThreadingTCPServer):
    # A port still holding the last run's closed connections can be bound again at once.
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        pass  # the browser hangs up mid-file whenever a source changes


for port, cors in ((BASE, False), (BASE + 1, True), (BASE + 2, False)):
    srv = Server(("127.0.0.1", port), handler(cors))
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
print("serving", flush=True)
threading.Event().wait()
