# LogHunter Enterprise

Motore di ricerca e analisi log ad alte prestazioni progettato per scansionare enormi dataset (file grezzi, .zip, .tar.gz) in secondi. Architettura ibrida: Java per orchestrazione/API, Rust per il core di ricerca a basso livello.

## Architettura

Tre componenti principali:

### Core Engine (Rust)
Binario ottimizzato per scansione parallela di file. Utilizza `tokio` per operazioni async e `memchr`/`memmem` per matching di stringhe in buffer binari con allocazioni minime. Gestisce la decompressione on-the-fly degli archivi senza estrazione su disco.

### Backend API (Java/Spring Boot)
Livello di sicurezza (Spring Security), sessioni WebSocket per streaming dei risultati, e API di visualizzazione. Bridge tra interfaccia web e binario Rust.

### Web Dashboard (HTML5/JS)
Monitoraggio ricerca in tempo reale via WebSocket, navigazione risultati e visualizzazione file di grandi dimensioni (100MB+) con paginazione a blocchi.

## Funzionalità

- **Scansione Ibrida**: Ricerca parallela su file locali o remoti (HTTP)
- **Ispezione Profonda**: Supporto nativo per file annidati in .zip, .tar.gz, .tgz
- **WebSocket Streaming**: Risultati visualizzati sulla dashboard appena trovati
- **Anteprima Intelligente**: Anteprima immediata della riga con evidenziazione parole chiave
- **Visualizzatore File Grandi**: Caricamento paginato (default 20MB) per log giganti
- **Sicurezza**: Basic Auth + filtraggio input per prevenire iniezioni

## Requisiti

- Java 21
- Rust (Cargo)
- Maven

## Avvio Rapido

```bash
# Build Rust core
cd BackendRust && cargo build --release

# Avvia backend
mvn clean install && mvn spring-boot:run
```

Accedi: `http://localhost:8080` (admin / 0987)

## Workflow

1. Utente inserisce parametri di ricerca (data, URL, parola chiave)
2. Backend valida e avvia processo Rust
3. Rust scarica indice file, filtra per data, avvia worker paralleli
4. Risultati stampati come JSON su stdout
5. Backend intercetta stream, invia via WebSocket
6. Utente clicca "Espandi" per anteprima file completo (paginato se grande)

## Note Prestazioni

- **Memoria**: Elaborazione basata su stream, nessuna decompressione archivi completa
- **Concorrenza**: Numero worker adattivo automatico basato su core CPU
- **Rete**: Download log grandi usano copia bufferizzata per evitare overflow RAM

