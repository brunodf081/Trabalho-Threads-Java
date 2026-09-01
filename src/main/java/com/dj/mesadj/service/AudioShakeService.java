package com.dj.mesadj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

@Service
public class AudioShakeService {

    private static final String BASE_URL = "https://api.audioshake.ai";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, Processing> processamentos = new ConcurrentHashMap<>();

    @Value("${audioshake.api-key:}")
    private String apiKey;

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    public Processing iniciarSeparacao(MultipartFile file) throws IOException, InterruptedException {
        validarConfiguracao();
        validarArquivo(file);

        Path base = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(base);

        String original = file.getOriginalFilename() == null ? "musica" : file.getOriginalFilename();
        String safeName = UUID.randomUUID() + "-" + original.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path originalPath = base.resolve(safeName);
        file.transferTo(originalPath.toFile());

        String assetId = enviarAsset(originalPath);
        String taskId = criarTask(assetId);

        Processing processing = new Processing(taskId, original);
        processamentos.put(taskId, processing);
        return processing;
    }

    public Processing atualizarStatus(String taskId) throws IOException, InterruptedException {
        Processing processing = processamentos.get(taskId);
        if (processing == null) {
            throw new IllegalArgumentException("Processamento não encontrado. Reinicie o upload.");
        }

        JsonNode task = consultarTask(taskId);
        processing.status = calcularStatus(task);

        for (JsonNode target : task.path("targets")) {
            String model = target.path("model").asText();
            String status = target.path("status").asText();
            processing.targets.put(model, status);

            if ("error".equalsIgnoreCase(status)) {
                processing.errors.put(model, target.path("error").path("message").asText("Erro desconhecido"));
            }

            if ("completed".equalsIgnoreCase(status)) {
                JsonNode output = target.path("output");
                if (output.isArray() && !output.isEmpty()) {
                    String link = output.get(0).path("link").asText();
                    if (!link.isBlank() && !processing.stems.containsKey(model)) {
                        String filename = downloadStem(taskId, model, link);
                        processing.stems.put(model, "/api/audio/stem/" + taskId + "/" + filename);
                    }
                }
            }
        }

        return processing;
    }

    public byte[] lerStem(String taskId, String filename) throws IOException {
        Path file = Path.of(uploadDir).toAbsolutePath().normalize().resolve("stems").resolve(taskId).resolve(filename).normalize();
        Path root = Path.of(uploadDir).toAbsolutePath().normalize().resolve("stems").resolve(taskId).normalize();
        if (!file.startsWith(root) || !Files.exists(file)) {
            throw new IOException("Stem não encontrado");
        }
        return Files.readAllBytes(file);
    }

    private String enviarAsset(Path path) throws IOException, InterruptedException {
        String boundary = "----JavaBoundary" + UUID.randomUUID();

        byte[] fileBytes = Files.readAllBytes(path);

        String contentType = Files.probeContentType(path);

        if (contentType == null) {
            String name = path.getFileName().toString().toLowerCase();

            if (name.endsWith(".wav")) {
                contentType = "audio/wav";
            } else if (name.endsWith(".mp3")) {
                contentType = "audio/mpeg";
            } else if (name.endsWith(".flac")) {
                contentType = "audio/flac";
            } else if (name.endsWith(".aiff") || name.endsWith(".aif")) {
                contentType = "audio/aiff";
            } else if (name.endsWith(".m4a") || name.endsWith(".aac")) {
                contentType = "audio/aac";
            } else {
                contentType = "application/octet-stream";
            }
        }

        String header =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" +
                        path.getFileName() + "\"\r\n" +
                        "Content-Type: " + contentType + "\r\n\r\n";

        byte[] prefix = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] suffix =
                ("\r\n--" + boundary + "--\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] body = new byte[
                prefix.length +
                        fileBytes.length +
                        suffix.length
                ];

        System.arraycopy(
                prefix,
                0,
                body,
                0,
                prefix.length
        );

        System.arraycopy(
                fileBytes,
                0,
                body,
                prefix.length,
                fileBytes.length
        );

        System.arraycopy(
                suffix,
                0,
                body,
                prefix.length + fileBytes.length,
                suffix.length
        );

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(BASE_URL + "/assets")
                )
                .header("x-api-key", apiKey)
                .header(
                        "Content-Type",
                        "multipart/form-data; boundary=" + boundary
                )
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        ensureSuccess(response, "upload do áudio");

        String assetId = objectMapper
                .readTree(response.body())
                .path("id")
                .asText();

        if (assetId.isBlank()) {
            throw new IOException(
                    "AudioShake não retornou o ID do arquivo."
            );
        }

        return assetId;
    }

    private String criarTask(String assetId) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", assetId);
        payload.put("targets", new Object[]{
                Map.of("model", "vocals", "formats", new String[]{"wav"}),
                Map.of("model", "instrumental", "formats", new String[]{"wav"}),
                Map.of("model", "drums", "formats", new String[]{"wav"}),
                Map.of("model", "bass", "formats", new String[]{"wav"}),
                Map.of("model", "other", "formats", new String[]{"wav"})
        });

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/tasks"))
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "criação da tarefa AudioShake");
        String id = objectMapper.readTree(response.body()).path("id").asText();
        if (id.isBlank()) throw new IOException("AudioShake não retornou task id");
        return id;
    }

    private JsonNode consultarTask(String taskId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/tasks/" + taskId))
                .header("x-api-key", apiKey)
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "consulta da tarefa AudioShake");
        return objectMapper.readTree(response.body());
    }

    private String downloadStem(String taskId, String model, String link) throws IOException, InterruptedException {
        Path dir = Path.of(uploadDir).toAbsolutePath().normalize().resolve("stems").resolve(taskId);
        Files.createDirectories(dir);
        String filename = model + ".wav";
        Path destination = dir.resolve(filename).normalize();

        HttpRequest request = HttpRequest.newBuilder(URI.create(link)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("Não foi possível baixar o stem " + model + " (HTTP " + response.statusCode() + ")");
        }
        try (InputStream in = response.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return filename;
    }

    private String calcularStatus(JsonNode task) {
        boolean processing = false;
        boolean error = false;
        boolean completed = false;
        for (JsonNode target : task.path("targets")) {
            String status = target.path("status").asText();
            processing |= "processing".equalsIgnoreCase(status) || "queued".equalsIgnoreCase(status);
            error |= "error".equalsIgnoreCase(status);
            completed |= "completed".equalsIgnoreCase(status);
        }
        if (error) return "ERROR";
        if (processing) return "PROCESSING";
        if (completed) return "COMPLETED";
        return "PROCESSING";
    }

    private void validarConfiguracao() {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("sua-chave-aqui")) {
            throw new IllegalStateException("Configure AUDIOSHAKE_API_KEY antes de fazer o upload.");
        }
    }

    private void validarArquivo(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Selecione um áudio.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac") || name.endsWith(".aiff") || name.endsWith(".aif") || name.endsWith(".aac") || name.endsWith(".m4a"))) {
            throw new IllegalArgumentException("Formato não suportado. Use MP3, WAV, FLAC, AIFF, AAC ou M4A.");
        }
    }

    private void ensureSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Falha na " + operation + ": HTTP " + response.statusCode() + " - " + response.body());
        }
    }

    public static class Processing {
        public final String taskId;
        public final String originalName;
        public volatile String status = "PROCESSING";
        public final Map<String, String> targets = new ConcurrentHashMap<>();
        public final Map<String, String> stems = new ConcurrentHashMap<>();
        public final Map<String, String> errors = new ConcurrentHashMap<>();

        public Processing(String taskId, String originalName) {
            this.taskId = taskId;
            this.originalName = originalName;
        }
    }
}
