package com.dj.mesadj.service;

import com.dj.mesadj.audio.ProcessadorAudio;
import com.dj.mesadj.model.EstadoInstrumento;
import com.dj.mesadj.model.InstrumentoThread;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MesaDJService {

    private final Map<String, InstrumentoThread> instrumentos = new LinkedHashMap<>();
    private final Map<String, byte[]> faixasAudio = new ConcurrentHashMap<>();
    private volatile boolean modoAudioReal = false;
    private volatile String nomeArquivoAtual;

    @PostConstruct
    public void iniciar() {

        instrumentos.put("bateria", new InstrumentoThread("Bateria", 500));
        instrumentos.put("baixo", new InstrumentoThread("Baixo", 800));
        instrumentos.put("synth", new InstrumentoThread("Synth", 650));

        instrumentos.values().forEach(InstrumentoThread::start);
    }

    /**
     * Recebe um .wav enviado pelo usuário, deriva as 4 faixas (vocal, instrumental,
     * grave, agudo) via {@link ProcessadorAudio} e substitui os canais simulados
     * pelos canais de áudio real — cada um ainda controlado por sua própria
     * {@link InstrumentoThread} (mesmo mecanismo de Lock/Condition de antes).
     */
    public synchronized void processarUpload(MultipartFile arquivo) throws IOException {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Selecione um arquivo .wav antes de enviar.");
        }
        String nomeOriginal = arquivo.getOriginalFilename();
        if (nomeOriginal == null || !nomeOriginal.toLowerCase().endsWith(".wav")) {
            throw new IllegalArgumentException("Apenas arquivos .wav são aceitos.");
        }

        ProcessadorAudio.Resultado resultado;
        try {
            resultado = ProcessadorAudio.processar(arquivo.getBytes());
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível processar o áudio: " + e.getMessage(), e);
        }

        // Encerra as threads/canais anteriores antes de substituir
        instrumentos.values().forEach(InstrumentoThread::encerrar);
        instrumentos.clear();
        faixasAudio.clear();

        registrarFaixa("vocal", "Vocal", resultado.vocal());
        registrarFaixa("instrumental", "Instrumental", resultado.instrumental());
        registrarFaixa("grave", "Grave", resultado.grave());
        registrarFaixa("agudo", "Agudo", resultado.agudo());

        modoAudioReal = true;
        nomeArquivoAtual = nomeOriginal;
    }

    private void registrarFaixa(String chave, String nomeExibicao, byte[] wavBytes) {
        faixasAudio.put(chave, wavBytes);
        InstrumentoThread thread = new InstrumentoThread(nomeExibicao, 1000);
        instrumentos.put(chave, thread);
        thread.start();
        // Faixas recém-carregadas começam pausadas — o usuário decide o que tocar.
        thread.pausar();
    }

    public byte[] getAudio(String chave) {
        return faixasAudio.get(chave == null ? null : chave.toLowerCase());
    }

    public boolean isModoAudioReal() {
        return modoAudioReal;
    }

    public String getNomeArquivoAtual() {
        return nomeArquivoAtual;
    }

    public synchronized String processarComando(String comandoBruto) {
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

    public synchronized Map<String, EstadoInstrumento> getEstados() {
        Map<String, EstadoInstrumento> estados = new LinkedHashMap<>();
        instrumentos.forEach((chave, thread) -> estados.put(thread.getNome(), thread.getEstado()));
        return estados;
    }

    @PreDestroy
    public synchronized void encerrarTudo() {
        instrumentos.values().forEach(InstrumentoThread::encerrar);
    }
}
