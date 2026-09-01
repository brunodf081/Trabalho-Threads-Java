package com.dj.mesadj.controller;

import com.dj.mesadj.service.AudioShakeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private final AudioShakeService audioShakeService;

    public AudioController(AudioShakeService audioShakeService) {
        this.audioShakeService = audioShakeService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            AudioShakeService.Processing p = audioShakeService.iniciarSeparacao(file);
            return ResponseEntity.ok(Map.of(
                    "taskId", p.taskId,
                    "status", p.status,
                    "message", "Áudio enviado. A separação por IA foi iniciada."
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "Erro no upload" : e.getMessage()));
        }
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> status(@PathVariable String taskId) {
        try {
            AudioShakeService.Processing p = audioShakeService.atualizarStatus(taskId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", p.taskId);
            result.put("status", p.status);
            result.put("targets", p.targets);
            result.put("stems", p.stems);
            result.put("errors", p.errors);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "Erro ao consultar processamento" : e.getMessage()));
        }
    }

    @GetMapping(value = "/stem/{taskId}/{filename:.+}", produces = "audio/wav" )
    public ResponseEntity<byte[]> stem(@PathVariable String taskId, @PathVariable String filename) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .body(audioShakeService.lerStem(taskId, filename));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
