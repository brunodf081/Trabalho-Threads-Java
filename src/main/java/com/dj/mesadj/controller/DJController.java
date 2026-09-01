package com.dj.mesadj.controller;

import com.dj.mesadj.model.InstrumentoThread;
import com.dj.mesadj.service.MesaDJService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

@Controller
public class DJController {

    private final MesaDJService mesaDJService;

    public DJController(MesaDJService mesaDJService) {
        this.mesaDJService = mesaDJService;
    }


    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("estados", mesaDJService.getEstados());
        model.addAttribute("log", InstrumentoThread.getLog());
        model.addAttribute("modoAudioReal", mesaDJService.isModoAudioReal());
        model.addAttribute("nomeArquivoAtual", mesaDJService.getNomeArquivoAtual());
        return "index";
    }


    @PostMapping("/comando")
    public String comando(@RequestParam("comando") String comando) {
        mesaDJService.processarComando(comando);
        return "redirect:/";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("arquivo") MultipartFile arquivo, RedirectAttributes redirectAttributes) {
        try {
            mesaDJService.processarUpload(arquivo);
            redirectAttributes.addFlashAttribute("sucessoUpload",
                    "Música carregada! Faixas Vocal, Instrumental, Grave e Agudo geradas.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("erroUpload", e.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/audio/{chave}")
    @ResponseBody
    public ResponseEntity<byte[]> audio(@PathVariable String chave) {
        byte[] dados = mesaDJService.getAudio(chave);
        if (dados == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(dados);
    }

    @GetMapping("/api/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> resposta = new HashMap<>();
        resposta.put("estados", mesaDJService.getEstados());
        resposta.put("log", InstrumentoThread.getLog());
        resposta.put("modoAudioReal", mesaDJService.isModoAudioReal());
        return resposta;
    }
}
