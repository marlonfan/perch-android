"""Local ntfy JSON-stream fixture. No external messages or credentials."""
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

events = []
condition = threading.Condition()

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        parsed = urlparse(self.path)
        if not parsed.path.endswith('/json'):
            self.send_error(404)
            return
        topics = parsed.path.strip('/').split('/')[0].split(',')
        since = int(parse_qs(parsed.query).get('since', ['0'])[0])
        self.send_response(200)
        self.send_header('Content-Type', 'application/x-ndjson')
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        def emit(message):
            self.wfile.write((json.dumps(message, ensure_ascii=False) + '\n').encode())
            self.wfile.flush()
        try:
            emit({'event': 'open', 'id': uuid.uuid4().hex[:12], 'time': int(time.time()), 'topic': topics[0]})
            with condition:
                cached = list(events)
                cursor = len(events)
            for message in cached:
                if message['topic'] in topics and message['time'] >= since:
                    emit(message)
            while True:
                with condition:
                    condition.wait(timeout=45)
                    pending = events[cursor:]
                    cursor = len(events)
                for message in pending:
                    if message['topic'] in topics:
                        emit(message)
                emit({'event': 'keepalive', 'id': 'keepalive', 'time': int(time.time()), 'topic': topics[0]})
        except (ConnectionError, OSError):
            pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode()
        if self.path == '/__replay':
            with condition:
                if events:
                    events.append(dict(events[-1]))
                    condition.notify_all()
            response = b'{}'
        else:
            message = {'id': uuid.uuid4().hex[:12], 'event': 'message', 'time': int(time.time()), 'topic': self.path.strip('/'), 'title': self.headers.get('Title', 'Smoke test'), 'message': body, 'priority': int(self.headers.get('Priority', '3'))}
            if self.headers.get('Markdown') == 'yes':
                message['content_type'] = 'text/markdown'
            with condition:
                events.append(message)
                condition.notify_all()
            response = json.dumps(message).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)

if __name__ == '__main__':
    print('ntfy fixture ready at http://127.0.0.1:8787', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8787), Handler).serve_forever()
