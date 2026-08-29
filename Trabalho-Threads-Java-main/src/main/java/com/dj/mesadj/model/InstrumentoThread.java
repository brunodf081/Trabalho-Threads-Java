package com.dj.mesadj.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class InstrumentoThread extends Thread {

    private final String nome;
    private final int intervaloMs;

    private volatile EstadoInstrumento estado = EstadoInstrumento.TOCANDO;
    private volatile boolean encerrado = false;

    private final Lock lock = new ReentrantLock();
    private final Condition condicaoPausa = lock.newCondition();

    private static final List<String> LOG = new CopyOnWriteArrayList<>();
    private static final int LOG_MAX = 60;
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    public InstrumentoThread(String nome, int intervaloMs) {
        super("Thread-" + nome);
        this.nome = nome;
        this.intervaloMs = intervaloMs;
    }

    @Override
    public void run() {
        registrarLog(nome + " iniciou a reprodução.");

        while (!encerrado) {

            // Região crítica: verifica/aguarda o estado com segurança
            lock.lock();
            try {
                while (estado == EstadoInstrumento.PAUSADO && !encerrado) {
                    condicaoPausa.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                lock.unlock();
            }

            if (encerrado) {
                break;
            }


            registrarLog(nome + " ♪ tocando...");

            try {
                Thread.sleep(intervaloMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        registrarLog(nome + " encerrou a execução.");
    }

    /** Sinaliza para a thread pausar, de forma segura. */
    public void pausar() {
        lock.lock();
        try {
            if (estado == EstadoInstrumento.TOCANDO) {
                estado = EstadoInstrumento.PAUSADO;
                registrarLog(nome + " pausado.");
            }
        } finally {
            lock.unlock();
        }
    }

    public void retomar() {
        lock.lock();
        try {
            if (estado == EstadoInstrumento.PAUSADO) {
                estado = EstadoInstrumento.TOCANDO;
                condicaoPausa.signalAll();
                registrarLog(nome + " retomado.");
            }
        } finally {
            lock.unlock();
        }
    }


    public void encerrar() {
        lock.lock();
        try {
            encerrado = true;
            estado = EstadoInstrumento.ENCERRADO;
            condicaoPausa.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public EstadoInstrumento getEstado() {
        return estado;
    }

    public String getNome() {
        return nome;
    }

    private static void registrarLog(String mensagem) {
        String linha = "[" + LocalTime.now().format(FORMATO_HORA) + "] " + mensagem;
        LOG.add(0, linha);
        while (LOG.size() > LOG_MAX) {
            LOG.remove(LOG.size() - 1);
        }
    }

    public static List<String> getLog() {
        return LOG;
    }
}
