package com.dj.mesadj.service;

import com.dj.mesadj.model.EstadoInstrumento;
import com.dj.mesadj.model.InstrumentoThread;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MesaDJService {

    private final Map<String, InstrumentoThread> instrumentos = new LinkedHashMap<>();

    @PostConstruct
    public void iniciar() {

        instrumentos.put("bateria", new InstrumentoThread("Bateria", 500));
        instrumentos.put("baixo", new InstrumentoThread("Baixo", 800));
        instrumentos.put("synth", new InstrumentoThread("Synth", 650));

        instrumentos.values().forEach(InstrumentoThread::start);
    }

    public String processarComando(String comandoBruto) {
        if (comandoBruto == null || comandoBruto.isBlank()) {
            return "Comando vazio.";
        }

        String[] partes = comandoBruto.trim().toLowerCase().split("\\s+");
        if (partes.length < 2) {
            return "Formato inválido. Use: <instrumento|todas> <pausar|retomar>";
        }

        String alvo = partes[0];
        String acao = partes[1];

        if (alvo.equals("todas") || alvo.equals("todos")) {
            instrumentos.values().forEach(i -> aplicarAcao(i, acao));
            return "Comando '" + acao + "' aplicado a todas as faixas.";
        }

        InstrumentoThread instrumento = instrumentos.get(alvo);
        if (instrumento == null) {
            return "Instrumento não encontrado: " + alvo;
        }

        aplicarAcao(instrumento, acao);
        return "Comando '" + acao + "' aplicado a " + alvo + ".";
    }

    private void aplicarAcao(InstrumentoThread instrumento, String acao) {
        switch (acao) {
            case "pausar" -> instrumento.pausar();
            case "retomar" -> instrumento.retomar();
            default -> { }
        }
    }

    public Map<String, EstadoInstrumento> getEstados() {
        Map<String, EstadoInstrumento> estados = new LinkedHashMap<>();
        instrumentos.forEach((chave, thread) -> estados.put(thread.getNome(), thread.getEstado()));
        return estados;
    }

    @PreDestroy
    public void encerrarTudo() {
        instrumentos.values().forEach(InstrumentoThread::encerrar);
    }
}
