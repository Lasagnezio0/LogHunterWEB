import os
import random
import zipfile
import tarfile
import io
import time
from datetime import datetime, timedelta

OUTPUT_DIR = "server_logs_chaos"
NUM_FILES = 100  # Numero totale di file
TARGET_SIZE_MB = 20  # Dimensione target (20 MB)
TARGET_BYTES = TARGET_SIZE_MB * 1024 * 1024
START_DATE = datetime(2023, 1, 1)
END_DATE = datetime(2025, 12, 31)

TARGET_WORDS = ["CRITICO", "FATALE", "PASSWORD_PLAIN_TEXT", "DB_DEADLOCK", "NULL_POINTER"]
HIT_CHANCE = 0.25  # 25% di probabilità che un file contenga l'errore


USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
    "curl/7.64.1", "PostmanRuntime/7.26.8", "Googlebot/2.1", "Python-urllib/3.9"
]
URLS = ["/api/login", "/v2/users", "/static/img/logo.png", "/admin/dashboard", "/wp-login.php", "/health"]
ERRORS = ["Connection refused", "Timeout waiting for lock", "Segment fault", "OutOfMemoryError", "Invalid Token"]
DB_TABLES = ["users", "orders", "transactions", "audit_log", "inventory"]


def rnd_ip():
    return f"{random.randint(1,223)}.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(0,255)}"

def get_timestamp(base_date):
    # Aggiunge variazione di secondi casuale
    delta = timedelta(milliseconds=random.randint(0, 86400000))
    return (base_date + delta).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

# 1. TEMA WEB (Simile a Nginx/Apache)
def theme_web_access(base_date):
    status = random.choice([200, 200, 200, 301, 404, 500, 403])
    size = random.randint(100, 15000)
    return f'{rnd_ip()} - - [{get_timestamp(base_date)}] "{random.choice(["GET", "POST", "PUT"])} {random.choice(URLS)} HTTP/1.1" {status} {size} "{random.choice(USER_AGENTS)}"\n'

# 2. TEMA SYSTEM (Simile a /var/log/syslog)
def theme_syslog(base_date):
    proc = random.choice(["systemd", "sshd", "kernel", "cron", "dockerd"])
    pid = random.randint(100, 9999)
    msg = random.choice(["Started session", "Disconnected user", "Cleaning up", "Reloading configuration", "Failed to start unit"])
    return f"{get_timestamp(base_date)} srv-main {proc}[{pid}]: <INFO> {msg} result=success\n"

# 3. TEMA DATABASE (Slow Query Log)
def theme_db(base_date):
    query_time = random.uniform(0.1, 15.0)
    table = random.choice(DB_TABLES)
    return f"# Time: {get_timestamp(base_date)}\n# User@Host: admin[{rnd_ip()}]\n# Query_time: {query_time:.4f}  Lock_time: 0.0001\nSELECT * FROM {table} WHERE id > {random.randint(1000,90000)} ORDER BY created_at DESC;\n"

# 4. TEMA APPLICATION (Stacktraces Java/Python)
def theme_app_trace(base_date):
    err = random.choice(ERRORS)
    trace = f"{get_timestamp(base_date)} [main] ERROR com.company.service.Core - Exception occurred: {err}\n"
    for i in range(random.randint(5, 15)):
        trace += f"\tat com.company.module.Class{random.randint(1,99)}.method(SourceFile.java:{random.randint(10,500)})\n"
    return trace
    
def generate_varied_content(target_size, base_date, insert_target=None):
    """
    Genera contenuto fino a raggiungere target_size.
    Mischia i temi per creare 'caos'.
    """
    buffer = io.StringIO()
    current_size = 0
    
    # Sceglie un "tema dominante" per questo file o blocco
    mode = random.choice(['WEB', 'SYS', 'DB', 'APP', 'CHAOS'])
    
    generators = {
        'WEB': theme_web_access,
        'SYS': theme_syslog,
        'DB': theme_db,
        'APP': theme_app_trace
    }

    # Posizione casuale dove inserire il target (es. a metà o fine)
    target_pos = random.randint(int(target_size * 0.2), int(target_size * 0.8)) if insert_target else -1
    target_inserted = False

    while current_size < target_size:
        # Bufferizza 100 righe alla volta per velocità
        chunk_lines = []
        for _ in range(200): 
            # Se siamo in CHAOS, cambia generatore ogni riga, altrimenti usa il dominante
            gen_func = generators[random.choice(list(generators.keys()))] if mode == 'CHAOS' else generators[mode]
            line = gen_func(base_date)
            chunk_lines.append(line)
        
        chunk_str = "".join(chunk_lines)
        buffer.write(chunk_str)
        current_size += len(chunk_str)
        
        # Inserimento parola chiave target
        if insert_target and not target_inserted and current_size > target_pos:
            # Creiamo un log credibile con la parola target
            bad_log = f"\n[SECURITY_AUDIT] {get_timestamp(base_date)} ALERT: Pattern {insert_target} detected in input stream from {rnd_ip()} !!!\n"
            buffer.write(bad_log)
            current_size += len(bad_log)
            target_inserted = True

    return buffer.getvalue()


def get_filename_and_date(extension):
    delta = END_DATE - START_DATE
    random_days = random.randrange(delta.days)
    d = START_DATE + timedelta(days=random_days)
    d = d.replace(hour=random.randint(0,23), minute=random.randint(0,59), second=random.randint(0,59))
    
    ts = d.strftime("%Y-%m-%d_%H-%M-%S")
    # Nomi diversi per simulare server diversi
    prefix = random.choice(["apache_access", "catalina", "syslog", "db_slow_query", "error", "trace"])
    name = f"{prefix}_{ts}"
    return f"{name}{extension}", d
    
def create_chaos_files():
    if not os.path.exists(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR)

    print(f"--- START CHAOS GENERATOR ---")
    print(f"Creazione {NUM_FILES} files variegati da ~{TARGET_SIZE_MB}MB ciascuno.")
    print("Nota: Sarà un po' più lento perché genera contenuto unico, non copiato.")

    for i in range(NUM_FILES):
        ext = random.choice(['.log', '.zip', '.tar.gz'])
        fname, file_date = get_filename_and_date(ext)
        fpath = os.path.join(OUTPUT_DIR, fname)
        
        # Target Decision
        has_target = random.random() < HIT_CHANCE
        target_word = random.choice(TARGET_WORDS) if has_target else None
        
        print(f"[{i+1}/{NUM_FILES}] Generando {fname} (Target: {target_word if has_target else 'NO'})...")

        try:
            if ext == '.log':
                content = generate_varied_content(TARGET_BYTES, file_date, target_word)
                with open(fpath, 'w', encoding='utf-8') as f:
                    f.write(content)

            elif ext == '.zip':
                with zipfile.ZipFile(fpath, 'w', zipfile.ZIP_DEFLATED) as zf:
                    # Dividiamo i 20MB in 3-4 file interni
                    num_parts = random.randint(3, 5)
                    part_size = TARGET_BYTES // num_parts
                    
                    for p in range(num_parts):
                        t_word = target_word if (target_word and p == 1) else None
                        
                        part_content = generate_varied_content(part_size, file_date, t_word)
                        # Nomi interni realistici
                        inner_name = f"logs/node_{random.randint(1,5)}/app_{p}.log"
                        zf.writestr(inner_name, part_content)
            elif ext == '.tar.gz':
                with tarfile.open(fpath, "w:gz") as tar:
                    num_parts = 3
                    part_size = TARGET_BYTES // num_parts
                    
                    for p in range(num_parts):
                        t_word = target_word if (target_word and p == 1) else None
                        part_content = generate_varied_content(part_size, file_date, t_word)
                        
                        bytes_io = io.BytesIO(part_content.encode('utf-8'))
                        
                        info = tarfile.TarInfo(name=f"var/log/backup_{p}.log")
                        info.size = len(bytes_io.getbuffer())
                        tar.addfile(info, bytes_io)

        except Exception as e:
            print(f"Errore su {fname}: {e}")

    print(f"\n--- FATTO ---")
    print(f"Log salvati in: {OUTPUT_DIR}")
    print(f"Avvia server: cd {OUTPUT_DIR} && python -m http.server 8000")

if __name__ == "__main__":
    create_chaos_files()
