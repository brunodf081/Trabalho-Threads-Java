package com.dj.mesadj.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Processa um .wav enviado pelo usuário e deriva 4 faixas aproximadas,
 * usando apenas técnicas clássicas de DSP em Java puro (sem IA/rede neural):
 *
 *  - VOCAL:        soma mono (L+R)/2 filtrada na faixa de voz humana (~300Hz–3400Hz)
 *  - INSTRUMENTAL: se estéreo, diferença (L-R) — cancela o que está centralizado
 *                   no mix (geralmente a voz principal); se mono, aplica um
 *                   corte (band-stop) na faixa de voz como aproximação.
 *  - GRAVE:         passa-baixa (~250Hz) sobre a soma mono
 *  - AGUDO:         passa-alta (~4000Hz) sobre a soma mono
 *
 * Importante: isso NÃO é separação de fontes "de estúdio" (tipo Spleeter/Demucs,
 * que dependem de redes neurais treinadas). É uma aproximação honesta via
 * cancelamento de canal e filtros IIR de 1ª ordem, adequada para o escopo do
 * trabalho (processamento em Java, sem serviços externos).
 */
public final class ProcessadorAudio {

    private static final double VOZ_FREQ_BAIXA = 300.0;
    private static final double VOZ_FREQ_ALTA = 3400.0;
    private static final double GRAVE_CORTE = 250.0;
    private static final double AGUDO_CORTE = 4000.0;

    private ProcessadorAudio() {
    }

    public record Resultado(byte[] vocal, byte[] instrumental, byte[] grave, byte[] agudo) {
    }

    public static Resultado processar(byte[] wavOriginal) throws IOException, UnsupportedAudioFileException {
        if (wavOriginal == null || wavOriginal.length == 0) {
            throw new IllegalArgumentException("Arquivo de áudio vazio.");
        }

        try (AudioInputStream original = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavOriginal))) {
            AudioFormat formatoOriginal = original.getFormat();

            AudioFormat formatoPCM = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    formatoOriginal.getSampleRate(),
                    16,
                    formatoOriginal.getChannels(),
                    formatoOriginal.getChannels() * 2,
                    formatoOriginal.getSampleRate(),
                    false
            );

            try (AudioInputStream pcmStream = AudioSystem.isConversionSupported(formatoPCM, formatoOriginal)
                    ? AudioSystem.getAudioInputStream(formatoPCM, original)
                    : original) {

                byte[] bruto = pcmStream.readAllBytes();
                int canais = formatoPCM.getChannels();
                float sampleRate = formatoPCM.getSampleRate();
                int totalFrames = bruto.length / (2 * canais);

                if (totalFrames == 0) {
                    throw new IllegalArgumentException("Não foi possível ler amostras do áudio enviado.");
                }

                double[] esquerdo = new double[totalFrames];
                double[] direito = new double[totalFrames];

                for (int i = 0; i < totalFrames; i++) {
                    int base = i * canais * 2;
                    short l = (short) ((bruto[base + 1] << 8) | (bruto[base] & 0xFF));
                    esquerdo[i] = l / 32768.0;
                    if (canais >= 2) {
                        short r = (short) ((bruto[base + 3] << 8) | (bruto[base + 2] & 0xFF));
                        direito[i] = r / 32768.0;
                    } else {
                        direito[i] = esquerdo[i];
                    }
                }

                boolean estereo = canais >= 2;
                double[] mono = new double[totalFrames];
                double[] diferenca = new double[totalFrames];
                for (int i = 0; i < totalFrames; i++) {
                    mono[i] = (esquerdo[i] + direito[i]) / 2.0;
                    diferenca[i] = esquerdo[i] - direito[i];
                }

                double[] vocalSinal = filtrarBandPass(mono, sampleRate, VOZ_FREQ_BAIXA, VOZ_FREQ_ALTA);
                double[] instrumentalSinal = estereo
                        ? diferenca
                        : filtrarBandStop(mono, sampleRate, VOZ_FREQ_BAIXA, VOZ_FREQ_ALTA);
                double[] graveSinal = filtrarPassaBaixa(mono, sampleRate, GRAVE_CORTE);
                double[] agudoSinal = filtrarPassaAlta(mono, sampleRate, AGUDO_CORTE);

                return new Resultado(
                        escreverWav(vocalSinal, sampleRate),
                        escreverWav(instrumentalSinal, sampleRate),
                        escreverWav(graveSinal, sampleRate),
                        escreverWav(agudoSinal, sampleRate)
                );
            }
        }
    }

    /** Filtro IIR passa-baixa de 1ª ordem (RC). */
    private static double[] filtrarPassaBaixa(double[] sinal, float sampleRate, double cortesHz) {
        double rc = 1.0 / (2 * Math.PI * cortesHz);
        double dt = 1.0 / sampleRate;
        double alpha = dt / (rc + dt);
        double[] saida = new double[sinal.length];
        double anterior = 0.0;
        for (int i = 0; i < sinal.length; i++) {
            anterior += alpha * (sinal[i] - anterior);
            saida[i] = anterior;
        }
        return saida;
    }

    /** Filtro IIR passa-alta de 1ª ordem (RC). */
    private static double[] filtrarPassaAlta(double[] sinal, float sampleRate, double corteHz) {
        double rc = 1.0 / (2 * Math.PI * corteHz);
        double dt = 1.0 / sampleRate;
        double alpha = rc / (rc + dt);
        double[] saida = new double[sinal.length];
        if (sinal.length == 0) {
            return saida;
        }
        saida[0] = 0.0;
        for (int i = 1; i < sinal.length; i++) {
            saida[i] = alpha * (saida[i - 1] + sinal[i] - sinal[i - 1]);
        }
        return saida;
    }

    private static double[] filtrarBandPass(double[] sinal, float sampleRate, double baixoHz, double altoHz) {
        double[] semGraves = filtrarPassaAlta(sinal, sampleRate, baixoHz);
        return filtrarPassaBaixa(semGraves, sampleRate, altoHz);
    }

    private static double[] filtrarBandStop(double[] sinal, float sampleRate, double baixoHz, double altoHz) {
        double[] faixaRemovida = filtrarBandPass(sinal, sampleRate, baixoHz, altoHz);
        double[] saida = new double[sinal.length];
        for (int i = 0; i < sinal.length; i++) {
            saida[i] = sinal[i] - faixaRemovida[i];
        }
        return saida;
    }

    private static byte[] escreverWav(double[] sinal, float sampleRate) throws IOException {
        double pico = 1e-4;
        for (double v : sinal) {
            pico = Math.max(pico, Math.abs(v));
        }
        double ganho = pico > 1.0 ? 1.0 / pico : 1.0;

        byte[] pcm = new byte[sinal.length * 2];
        for (int i = 0; i < sinal.length; i++) {
            double amostra = Math.max(-1.0, Math.min(1.0, sinal[i] * ganho));
            short valor = (short) Math.round(amostra * 32767.0);
            pcm[i * 2] = (byte) (valor & 0xFF);
            pcm[i * 2 + 1] = (byte) ((valor >> 8) & 0xFF);
        }

        AudioFormat formatoSaida = new AudioFormat(sampleRate, 16, 1, true, false);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
             AudioInputStream ais = new AudioInputStream(bais, formatoSaida, sinal.length);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        }
    }
}
