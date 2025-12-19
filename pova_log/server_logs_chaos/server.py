import http.server
import socketserver
import os

# Configurazione
PORT = 8000

# Questa classe rende il server MULTI-THREAD
class ThreadingHTTPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True

if __name__ == "__main__":
    # Assicurati di essere nella cartella giusta, o togli questa riga se lanci lo script DENTRO la cartella logs
    if os.path.exists("server_logs_chaos"):
        os.chdir("server_logs_chaos")
    
    handler = http.server.SimpleHTTPRequestHandler
    
    with ThreadingHTTPServer(("", PORT), handler) as httpd:
        print(f"🚀 SERVER REALE (Multi-Thread) attivo su porta {PORT}")
        print(f"   Rust ora può scaricare file in parallelo!")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
