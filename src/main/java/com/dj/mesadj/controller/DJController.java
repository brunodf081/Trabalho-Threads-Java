package com.dj.mesadj.controller;

import com.dj.mesadj.model.InstrumentoThread;
import com.dj.mesadj.service.MesaDJService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

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
        return "index";
    }


    @PostMapping("/comando")
    public String comando(@RequestParam("comando") String comando) {
        mesaDJService.processarComando(comando);
        return "redirect:/";
    }

    @GetMapping("/api/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> resposta = new HashMap<>();
        resposta.put("estados", mesaDJService.getEstados());
        resposta.put("log", InstrumentoThread.getLog());
        return resposta;
    }
}
