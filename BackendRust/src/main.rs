use clap::Parser;
use futures::stream::StreamExt;
use memchr::{memmem, memchr}; 
use scraper::{Html, Selector};
use serde_json::json;
use std::io::{Cursor, Read};
use std::sync::Arc;
use std::time::Instant;
use url::Url;
use flate2::read::GzDecoder;
use tar::Archive;

#[derive(Parser)]
struct Args {
    #[arg(long)] start: String,
    #[arg(long)] end: String,
    #[arg(long)] url: String,
    #[arg(long)] word: String,
    #[arg(long)] user: Option<String>,
    #[arg(long)] password: Option<String>,
}

#[tokio::main]
async fn main() {
    let start_time = Instant::now();
    let args = Arc::new(Args::parse());

    let num_cores = num_cpus::get();
    let max_workers = if num_cores < 4 { 25 } else { 60 }; 

    let client = reqwest::Client::builder()
        .user_agent("LogHunter/Rust-Core")
        .timeout(std::time::Duration::from_secs(60))
        .build().unwrap();
    let client = Arc::new(client);

    let base_url = if args.url.ends_with('/') { args.url.clone() } else { format!("{}/", args.url) };
    
    // Lista file dall'indice HTML
    let mut req = client.get(&base_url);
    if let (Some(u), Some(p)) = (&args.user, &args.password) { req = req.basic_auth(u, Some(p)); }

    let html = req.send().await.and_then(|r| r.text()).await.unwrap_or_default();
    let doc = Html::parse_document(&html);
    let selector = Selector::parse("a").unwrap();
    
    let dt_start = chrono::NaiveDate::parse_from_str(&args.start, "%Y-%m-%d").unwrap_or_default();
    let dt_end = chrono::NaiveDate::parse_from_str(&args.end, "%Y-%m-%d").unwrap_or_default();
    let re_date = regex::Regex::new(r"(\d{4}-\d{2}-\d{2})").unwrap();

    let finder = Arc::new(memmem::Finder::new(args.word.as_bytes()));
    let mut tasks = Vec::new();

    for el in doc.select(&selector) {
        if let Some(href) = el.value().attr("href") {
            if href.len() < 5 || href.starts_with('?') { continue; }
            if let Some(caps) = re_date.captures(href) {
                if let Ok(d) = chrono::NaiveDate::parse_from_str(&caps[1], "%Y-%m-%d") {
                    if d >= dt_start && d <= dt_end { tasks.push(href.to_string()); }
                }
            }
        }
    }

    // Scansione parallela
    futures::stream::iter(tasks).for_each_concurrent(max_workers, |filename| {
        let client = client.clone();
        let args = args.clone();
        let finder = finder.clone();
        let url_obj = Url::parse(&base_url).unwrap().join(&filename).unwrap();

        async move {
            let mut req = client.get(url_obj);
            if let (Some(u), Some(p)) = (&args.user, &args.password) { req = req.basic_auth(u, Some(p)); }

            if let Ok(resp) = req.send().await {
                if let Ok(bytes) = resp.bytes().await {
                    if filename.ends_with(".zip") {
                        if let Ok(mut zip) = zip::ZipArchive::new(Cursor::new(&bytes)) {
                            for i in 0..zip.len() {
                                if let Ok(mut f) = zip.by_index(i) {
                                    if f.is_dir() { continue; }
                                    let mut buf = Vec::with_capacity(f.size() as usize);
                                    if f.read_to_end(&mut buf).is_ok() {
                                        process_buffer(&buf, &finder, &filename, &format!("zip:{}", f.name()));
                                    }
                                }
                            }
                        }
                    } else if filename.ends_with(".tar.gz") || filename.ends_with(".tgz") {
                        let mut arch = Archive::new(GzDecoder::new(Cursor::new(&bytes)));
                        if let Ok(entries) = arch.entries() {
                            for entry in entries.flatten() {
                                let mut buf = Vec::new();
                                if let Ok(path) = entry.path() {
                                    let path_str = path.to_string_lossy().to_string();
                                    let mut e = entry;
                                    if e.read_to_end(&mut buf).is_ok() {
                                        process_buffer(&buf, &finder, &filename, &format!("tar:{}", path_str));
                                    }
                                }
                            }
                        }
                    } else {
                        process_buffer(&bytes, &finder, &filename, "file");
                    }
                }
            }
        }
    }).await;

    println!("{}", json!({"type": "end", "elapsed_seconds": start_time.elapsed().as_secs_f64()}));
}

fn process_buffer(data: &[u8], finder: &memmem::Finder, filename: &str, sub: &str) {
    let mut last_end = 0;
    let mut count = 0;

    for idx in finder.find_iter(data) {
        if idx < last_end { continue; }
        count += 1;
        if count > 100 { break; } // Limite per file per non saturare la socket

        let start = data[..idx].iter().rposition(|&b| b == b'\n').map(|i| i + 1).unwrap_or(0);
        let end = memchr(b'\n', &data[idx..]).map(|i| idx + i).unwrap_or(data.len());
        last_end = end;

        let preview = String::from_utf8_lossy(&data[start..end])
            .chars().take(300).collect::<String>()
            .replace("\"", "'").replace("\\", "\\\\");

        let name = if sub == "file" { filename.to_string() } else { format!("{} > {}", filename, sub) };
        println!("{}", json!({"type": "match", "filename": name, "matches_preview": preview}));
    }
}