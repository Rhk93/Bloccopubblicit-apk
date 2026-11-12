package com.rhk93.scudo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * VPN locale "solo DNS".
 * Nel tunnel entrano SOLO le richieste DNS (i nomi dei siti) delle app non escluse.
 * Tutto il resto del traffico (pagine, foto, messaggi, password) non passa mai di qui.
 * Le app escluse e Scudo stesso sono fuori dalla VPN.
 */
public class ScudoVpnService extends VpnService {

    public static final String AVVIA = "com.rhk93.scudo.AVVIA";
    public static final String FERMA = "com.rhk93.scudo.FERMA";

    public static volatile boolean attivo = false;

    private static final String IP_VPN = "10.111.222.1";
    private static final String DNS_FINTO = "10.111.222.2";
    private static final String CANALE = "scudo";

    private Tunnel tunnel;
    private ExecutorService pool;
    private volatile String doh;
    private volatile String ipDns;
    private volatile boolean cifrato;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String azione = intent != null ? intent.getAction() : null;
        if (FERMA.equals(azione)) {
            ferma();
            stopSelf();
            return START_NOT_STICKY;
        }
        avvia();
        return START_STICKY;
    }

    private synchronized void avvia() {
        Statistiche.carica(this);
        Filtro.caricaSeServe(this);

        ServerDns s = ServerDns.attuale(this);
        doh = s.doh;
        ipDns = (s.ip == null || s.ip.isEmpty()) ? "9.9.9.9" : s.ip;
        cifrato = Prefs.cifrato(this) && doh != null && doh.startsWith("https://");

        if (pool == null) pool = Executors.newFixedThreadPool(8);

        VpnService.Builder b = new VpnService.Builder();
        b.setSession("Scudo");
        b.addAddress(IP_VPN, 32);
        b.addRoute(DNS_FINTO, 32);      // solo il DNS entra nel tunnel
        b.addDnsServer(DNS_FINTO);
        b.setMtu(1500);
        b.setBlocking(true);
        try {
            b.addDisallowedApplication(getPackageName());
        } catch (Exception ignorata) {
        }
        for (String pacchetto : Prefs.escluse(this)) {
            try {
                b.addDisallowedApplication(pacchetto);   // app escluse: fuori dalla VPN
            } catch (Exception ignorata) {
                // app non installata
            }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setConfigureIntent(pi);

        ParcelFileDescriptor pfd;
        try {
            pfd = b.establish();
        } catch (Exception e) {
            pfd = null;
        }
        if (pfd == null) {
            ferma();
            stopSelf();
            return;
        }
        Tunnel vecchio = tunnel;
        tunnel = new Tunnel(pfd);
        new Thread(tunnel, "scudo-tunnel").start();
        if (vecchio != null) vecchio.chiudi();
        attivo = true;
        primoPiano(pi);
    }

    private void primoPiano(PendingIntent pi) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CANALE, "Protezione attiva",
                    NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, CANALE)
                    .setSmallIcon(R.drawable.ic_scudo)
                    .setContentTitle("Scudo è attivo")
                    .setContentText("La pubblicità viene bloccata")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception ignorata) {
            // la VPN funziona anche senza notifica
        }
    }

    private synchronized void ferma() {
        attivo = false;
        if (tunnel != null) {
            tunnel.chiudi();
            tunnel = null;
        }
        Statistiche.salva(this);
        Statistiche.svuota();   // il registro non sopravvive allo spegnimento
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignorata) {
        }
    }

    @Override
    public void onRevoke() {
        ferma();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        ferma();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ tunnel

    private class Tunnel implements Runnable {
        final ParcelFileDescriptor pfd;
        final FileOutputStream out;
        volatile boolean vivo = true;

        Tunnel(ParcelFileDescriptor p) {
            pfd = p;
            out = new FileOutputStream(p.getFileDescriptor());
        }

        @Override
        public void run() {
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            byte[] buf = new byte[32767];
            StructPollfd pf = new StructPollfd();
            pf.fd = pfd.getFileDescriptor();
            pf.events = (short) OsConstants.POLLIN;
            StructPollfd[] elenco = {pf};
            try {
                while (vivo) {
                    pf.revents = 0;
                    int r;
                    try {
                        r = Os.poll(elenco, 1000);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EINTR) continue;
                        break;
                    }
                    if (!vivo) break;
                    if (r <= 0) continue;
                    if ((pf.revents & OsConstants.POLLIN) == 0) {
                        if ((pf.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) break;
                        continue;
                    }
                    int n = in.read(buf);
                    if (n > 0) gestisci(this, buf, n);
                }
            } catch (Exception ignorata) {
            } finally {
                try {
                    pfd.close();
                } catch (Exception ignorata) {
                }
            }
        }

        void chiudi() {
            vivo = false;
        }

        void scrivi(byte[] pacchetto) {
            if (!vivo || pacchetto == null) return;
            synchronized (this) {
                try {
                    out.write(pacchetto);
                } catch (Exception ignorata) {
                }
            }
        }
    }

    private void gestisci(Tunnel t, byte[] buf, int n) {
        if (n < 28) return;
        if (((buf[0] >> 4) & 0x0F) != 4) return;           // solo IPv4
        int ihl = (buf[0] & 0x0F) * 4;
        if (ihl < 20 || n < ihl + 8) return;
        if ((buf[9] & 0xFF) != 17) return;                   // solo UDP
        int porta = ((buf[ihl + 2] & 0xFF) << 8) | (buf[ihl + 3] & 0xFF);
        if (porta != 53) return;                             // solo DNS
        int lunUdp = ((buf[ihl + 4] & 0xFF) << 8) | (buf[ihl + 5] & 0xFF);
        int lun = Math.min(lunUdp - 8, n - ihl - 8);
        if (lun < 12) return;

        final byte[] intestazione = Arrays.copyOf(buf, ihl + 8);
        final byte[] domanda = Arrays.copyOfRange(buf, ihl + 8, ihl + 8 + lun);
        final int ihlF = ihl;

        String nome = nome(domanda);
        if (nome != null && Filtro.bloccato(nome)) {
            byte[] r = rispostaBloccata(domanda);
            if (r != null) {
                t.scrivi(avvolgi(intestazione, ihlF, r));
                Statistiche.registra(this, nome);
                return;
            }
        }

        ExecutorService p = pool;
        if (p == null) return;
        try {
            p.execute(() -> {
                byte[] r = chiedi(domanda);
                if (r == null) r = errore(domanda);
                if (r != null) t.scrivi(avvolgi(intestazione, ihlF, r));
            });
        } catch (Exception ignorata) {
        }
    }

    // ------------------------------------------------------------------ server DNS

    private byte[] chiedi(byte[] q) {
        try {
            if (cifrato) return chiediCifrato(doh, q);
            return chiediNormale(ipDns, q);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] chiediCifrato(String indirizzo, byte[] q) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(indirizzo).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setDoOutput(true);
        c.setUseCaches(false);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/dns-message");
        c.setRequestProperty("Accept", "application/dns-message");
        c.setFixedLengthStreamingMode(q.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(q);
        }
        if (c.getResponseCode() != 200) return null;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] x = new byte[2048];
            int k;
            while ((k = in.read(x)) > 0) {
                b.write(x, 0, k);
                if (b.size() > 65000) break;
            }
            byte[] r = b.toByteArray();
            if (r.length < 12) return null;
            r[0] = q[0];
            r[1] = q[1];
            return r;
        }
    }

    private byte[] chiediNormale(String ip, byte[] q) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            s.setSoTimeout(4000);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 53));
            byte[] b = new byte[4096];
            DatagramPacket risposta = new DatagramPacket(b, b.length);
            s.receive(risposta);
            return Arrays.copyOf(b, risposta.getLength());
        }
    }

    // ------------------------------------------------------------------ pacchetti DNS

    /** Posizione subito dopo la prima domanda, o -1 se il pacchetto non è valido. */
    static int fineDomanda(byte[] q) {
        if (q.length < 17) return -1;
        int conteggio = ((q[4] & 0xFF) << 8) | (q[5] & 0xFF);
        if (conteggio < 1) return -1;
        int i = 12;
        while (true) {
            if (i >= q.length) return -1;
            int l = q[i] & 0xFF;
            if (l == 0) {
                i++;
                break;
            }
            if ((l & 0xC0) != 0) return -1;
            i += l + 1;
        }
        if (i + 4 > q.length) return -1;
        return i + 4;
    }

    static String nome(byte[] q) {
        if (fineDomanda(q) < 0) return null;
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (true) {
            int l = q[i] & 0xFF;
            if (l == 0) break;
            if (sb.length() > 0) sb.append('.');
            for (int j = 0; j < l; j++) sb.append((char) (q[i + 1 + j] & 0xFF));
            i += l + 1;
        }
        if (sb.length() == 0) return null;
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Risposta "questo sito non esiste" (0.0.0.0 per IPv4, :: per IPv6, NXDOMAIN per il resto). */
    static byte[] rispostaBloccata(byte[] q) {
        int fine = fineDomanda(q);
        if (fine < 0) return null;
        int tipo = ((q[fine - 4] & 0xFF) << 8) | (q[fine - 3] & 0xFF);
        int lunDati = tipo == 1 ? 4 : (tipo == 28 ? 16 : 0);
        byte[] r = new byte[fine + (lunDati > 0 ? 12 + lunDati : 0)];
        System.arraycopy(q, 0, r, 0, fine);
        r[2] = (byte) (0x80 | (q[2] & 0x01));
        r[3] = (byte) (0x80 | (lunDati > 0 ? 0 : 3));
        r[4] = 0;
        r[5] = 1;
        r[6] = 0;
        r[7] = (byte) (lunDati > 0 ? 1 : 0);
        r[8] = 0;
        r[9] = 0;
        r[10] = 0;
        r[11] = 0;
        if (lunDati > 0) {
            int p = fine;
            r[p++] = (byte) 0xC0;
            r[p++] = 12;
            r[p++] = (byte) (tipo >> 8);
            r[p++] = (byte) tipo;
            r[p++] = 0;
            r[p++] = 1;
            r[p++] = 0;
            r[p++] = 0;
            r[p++] = 0;
            r[p++] = 60;
            r[p++] = 0;
            r[p] = (byte) lunDati;
            // gli indirizzi restano a zero
        }
        return r;
    }

    /** Risposta "server non raggiungibile": l'app riproverà. */
    static byte[] errore(byte[] q) {
        int fine = fineDomanda(q);
        if (fine < 0) return null;
        byte[] r = Arrays.copyOf(q, fine);
        r[2] = (byte) (0x80 | (q[2] & 0x01));
        r[3] = (byte) (0x80 | 2);
        r[4] = 0;
        r[5] = 1;
        for (int i = 6; i < 12; i++) r[i] = 0;
        return r;
    }

    /** Impacchetta la risposta DNS in un pacchetto IPv4/UDP indirizzato all'app che ha chiesto. */
    static byte[] avvolgi(byte[] orig, int ihl, byte[] dns) {
        byte[] p = new byte[28 + dns.length];
        int totale = p.length;
        p[0] = 0x45;
        p[2] = (byte) (totale >> 8);
        p[3] = (byte) totale;
        p[6] = 0x40;
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(orig, 16, p, 12, 4);
        System.arraycopy(orig, 12, p, 16, 4);
        int ck = checksum(p, 0, 20);
        p[10] = (byte) (ck >> 8);
        p[11] = (byte) ck;
        p[20] = orig[ihl + 2];
        p[21] = orig[ihl + 3];
        p[22] = orig[ihl];
        p[23] = orig[ihl + 1];
        int lunUdp = 8 + dns.length;
        p[24] = (byte) (lunUdp >> 8);
        p[25] = (byte) lunUdp;
        System.arraycopy(dns, 0, p, 28, dns.length);
        return p;
    }

    static int checksum(byte[] b, int da, int lun) {
        int s = 0;
        for (int i = da; i < da + lun; i += 2) {
            int alto = (b[i] & 0xFF) << 8;
            int basso = i + 1 < da + lun ? (b[i + 1] & 0xFF) : 0;
            s += alto | basso;
        }
        while ((s >> 16) != 0) s = (s & 0xFFFF) + (s >> 16);
        return ~s & 0xFFFF;
    }
}
