package com.loghunter;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class LogHunterApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogHunterApplication.class, args);
    }

    private static final int CONCURRENT_DOWNLOADS = 10;
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 60000;

    @GetMapping("/download")
    public void downloadLogs(HttpServletResponse response,
            @RequestParam String startDate, @RequestParam String endDate,
            @RequestParam String logUrl,
            @RequestParam(required = false) String user, @RequestParam(required = false) String pass) {

        ExecutorService executor = null;
        try {
            LocalDate dtStart = LocalDate.parse(startDate);
            LocalDate dtEnd = LocalDate.parse(endDate);
            String baseUrl = logUrl.endsWith("/") ? logUrl : logUrl + "/";

            List<String> filesToDownload = fetchFileList(baseUrl, user, pass, dtStart, dtEnd);
            if (filesToDownload.isEmpty()) {
                response.sendError(HttpStatus.NOT_FOUND.value(), "Nessun file trovato");
                return;
            }

            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"Logs_" + startDate + ".zip\"");

            executor = Executors.newFixedThreadPool(CONCURRENT_DOWNLOADS);

            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(response.getOutputStream()))) {
                zos.setLevel(Deflater.BEST_SPEED);
                Set<String> usedNames = ConcurrentHashMap.newKeySet();
                List<Future<?>> futures = new ArrayList<>();

                for (String filename : filesToDownload) {
                    futures.add(executor.submit(() -> {
                        try {
                            processFile(baseUrl + filename, filename, user, pass, zos, usedNames);
                        } catch (Exception e) {
                            System.err.println("Errore file " + filename + ": " + e.getMessage());
                        }
                    }));
                }
                for (Future<?> f : futures) f.get();
                zos.finish();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (executor != null) executor.shutdownNow();
        }
    }

    @GetMapping("/view")
    public void viewFile(HttpServletResponse response,
            @RequestParam String fileUrl, @RequestParam(required = false) String subPath,
            @RequestParam(defaultValue = "0") long page, @RequestParam(defaultValue = "20971520") int size,
            @RequestParam(required = false) String user, @RequestParam(required = false) String pass) {

        response.setContentType("text/plain; charset=utf-8");
        long bytesToSkip = page * size;
        String cleanSubPath = (subPath != null) ? subPath.replaceAll("^(tar|zip):", "").trim() : null;

        try (InputStream rawIn = openStream(fileUrl, user, pass);
             BufferedInputStream bufIn = new BufferedInputStream(rawIn, BUFFER_SIZE)) {

            InputStream targetStream = null;
            long totalSize = -1;

            if (fileUrl.endsWith(".zip") && cleanSubPath != null) {
                ZipInputStream zis = new ZipInputStream(bufIn);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().replace("\\", "/").equals(cleanSubPath)) {
                        targetStream = zis;
                        totalSize = entry.getSize();
                        break;
                    }
                }
            } else if ((fileUrl.endsWith(".tar.gz") || fileUrl.endsWith(".tgz")) && cleanSubPath != null) {
                TarArchiveInputStream tarIn = new TarArchiveInputStream(new GZIPInputStream(bufIn));
                TarArchiveEntry entry;
                while ((entry = tarIn.getNextTarEntry()) != null) {
                    if (entry.getName().replace("\\", "/").equals(cleanSubPath)) {
                        targetStream = tarIn;
                        totalSize = entry.getSize();
                        break;
                    }
                }
            } else {
                targetStream = bufIn;
            }

            if (totalSize != -1) response.setHeader("X-File-Size", String.valueOf(totalSize));
            if (targetStream == null) {
                response.getWriter().write("File non trovato nel pacchetto.");
                return;
            }

            IOUtils.skip(targetStream, bytesToSkip);
            IOUtils.copyLarge(targetStream, response.getOutputStream(), 0, size);

        } catch (Exception e) {
            try { response.getWriter().write("Errore: " + e.getMessage()); } catch (IOException ignored) {}
        }
    }

    private void processFile(String fileUrl, String name, String user, String pass, ZipOutputStream zos, Set<String> usedNames) {
        try (InputStream rawIn = openStream(fileUrl, user, pass);
             BufferedInputStream bufIn = new BufferedInputStream(rawIn, BUFFER_SIZE)) {

            if (name.endsWith(".zip")) {
                try (ZipInputStream zis = new ZipInputStream(bufIn)) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (!e.isDirectory()) writeEntryToZip(zos, flattenName(e.getName(), name), zis, usedNames);
                    }
                }
            } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                try (TarArchiveInputStream tarIn = new TarArchiveInputStream(new GZIPInputStream(bufIn))) {
                    TarArchiveEntry e;
                    while ((e = tarIn.getNextTarEntry()) != null) {
                        if (tarIn.canReadEntryData(e) && !e.isDirectory()) writeEntryToZip(zos, flattenName(e.getName(), name), tarIn, usedNames);
                    }
                }
            } else {
                writeEntryToZip(zos, name, bufIn, usedNames);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void writeEntryToZip(ZipOutputStream zos, String name, InputStream is, Set<String> usedNames) throws IOException {
        String finalName = name;
        int count = 1;
        while (usedNames.contains(finalName)) finalName = (count++) + "_" + name;
        usedNames.add(finalName);
        
        ZipEntry ze = new ZipEntry(finalName);
        zos.putNextEntry(ze);
        IOUtils.copy(is, zos);
        zos.closeEntry();
    }

    private String flattenName(String path, String origin) {
        String name = new File(path).getName();
        String prefix = origin.replaceAll("(\\.zip|\\.tar\\.gz|\\.tgz|apache_|catalina_)", "");
        if (prefix.length() > 15) prefix = prefix.substring(0, 15);
        return prefix + "_" + name;
    }

    private InputStream openStream(String urlStr, String user, String pass) throws IOException {
        URL url = new URL(urlStr);
        if (!url.getProtocol().startsWith("http")) throw new SecurityException("Protocollo non valido");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false); // Sicurezza SSRF

        if (user != null && !user.isEmpty()) {
            String auth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);
        }
        return conn.getInputStream();
    }

    private List<String> fetchFileList(String urlStr, String user, String pass, LocalDate start, LocalDate end) throws IOException {
        List<String> files = new ArrayList<>();
        try (Scanner s = new Scanner(openStream(urlStr, user, pass), StandardCharsets.UTF_8)) {
            String html = s.useDelimiter("\\A").hasNext() ? s.next() : "";
            Pattern pDate = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
            Matcher m = Pattern.compile("href=\"([^\"]*)\"").matcher(html);
            while (m.find()) {
                String href = m.group(1);
                if (href.length() < 5 || href.startsWith("?") || href.endsWith("/")) continue;
                Matcher mDate = pDate.matcher(href);
                if (mDate.find()) {
                    LocalDate d = LocalDate.parse(mDate.group(1));
                    if (!d.isBefore(start) && !d.isAfter(end)) files.add(href);
                }
            }
        }
        return files;
    }
}