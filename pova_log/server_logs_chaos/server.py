import http.server
import socketserver
import os

PORT = 8000

class ThreadingHTTPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True

if __name__ == "__main__":
    if os.path.exists("server_logs_chaos"):
        os.chdir("server_logs_chaos")
    
    handler = http.server.SimpleHTTPRequestHandler
    
    with ThreadingHTTPServer(("", PORT), handler) as httpd:
        print(f" SERVER REALE (Multi-Thread) attivo su porta {PORT}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
