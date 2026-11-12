package com.rhk93.scudo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

/**
 * Contatori delle pubblicità bloccate e registro dei siti pubblicitari bloccati.
 * Il registro sta in un file privato dell'app: non esce dal telefono e non va nel backup.
 */
public final class Statistiche {

    public static final class Voce {
        public final String dominio;
        public long ultima;
        public int oggi;
        public long totale;
        int giorno;

        Voce(String d) { dominio = d; }

        Voce copia() {
            Voce v = new Voce(dominio);
            v.ultima = ultima;
            v.oggi = oggi;
            v.totale = totale;
            v.giorno = giorno;
            return v;
        }
    }

    private static final int MAX_VOCI = 3000;

    private static boolean caricato;
    private static long totale;
    private static long oggi;
    private static int giorno;
    private static int nonSalvati;
    private static boolean registroCambiato;
    private static final HashMap<String, Voce> voci = new HashMap<>();

    private Statistiche() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "registro.txt");
    }

    public static synchronized void carica(Context c) {
        if (caricato) return;
        caricato = true;
        SharedPreferences s = Prefs.sp(c);
        totale = s.getLong("st_totale", 0);
        oggi = s.getLong("st_oggi", 0);
        giorno = s.getInt("st_giorno", 0);
        controllaGiorno();
        File f = file(c);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String riga;
                while ((riga = r.readLine()) != null) {
                    String[] p = riga.split("\t");
                    if (p.length < 5) continue;
                    Voce v = new Voce(p[0]);
                    v.ultima = Long.parseLong(p[1]);
                    v.totale = Long.parseLong(p[2]);
                    v.oggi = Integer.parseInt(p[3]);
                    v.giorno = Integer.parseInt(p[4]);
                    voci.put(v.dominio, v);
                }
            } catch (Exception ignorata) {
                // file rovinato: si riparte da zero
            }
        }
    }

    private static int oggiNumero() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.YEAR) * 1000 + k.get(Calendar.DAY_OF_YEAR);
    }

    private static void controllaGiorno() {
        int g = oggiNumero();
        if (g != giorno) {
            giorno = g;
            oggi = 0;
        }
    }

    public static synchronized void registra(Context c, String dominio) {
        carica(c);
        controllaGiorno();
        oggi++;
        totale++;
        if (Prefs.registro(c)) {
            Voce v = voci.get(dominio);
            if (v == null) {
                if (voci.size() >= MAX_VOCI) rimuoviPiuVecchia();
                v = new Voce(dominio);
                voci.put(dominio, v);
            }
            if (v.giorno != giorno) {
                v.giorno = giorno;
                v.oggi = 0;
            }
            v.oggi++;
            v.totale++;
            v.ultima = System.currentTimeMillis();
            registroCambiato = true;
        }
        if (++nonSalvati >= 20) salva(c);
    }

    private static void rimuoviPiuVecchia() {
        Voce vecchia = null;
        for (Voce v : voci.values()) {
            if (vecchia == null || v.ultima < vecchia.ultima) vecchia = v;
        }
        if (vecchia != null) voci.remove(vecchia.dominio);
    }

    public static synchronized void salva(Context c) {
        if (!caricato) return;
        nonSalvati = 0;
        Prefs.sp(c).edit()
                .putLong("st_totale", totale)
                .putLong("st_oggi", oggi)
                .putInt("st_giorno", giorno)
                .apply();
        if (!registroCambiato) return;
        registroCambiato = false;
        File f = file(c);
        File tmp = new File(f.getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
            for (Voce v : voci.values()) {
                w.write(v.dominio + "\t" + v.ultima + "\t" + v.totale + "\t" + v.oggi + "\t" + v.giorno + "\n");
            }
        } catch (Exception e) {
            return;
        }
        f.delete();
        tmp.renameTo(f);
    }

    public static synchronized long oggi(Context c) {
        carica(c);
        controllaGiorno();
        return oggi;
    }

    public static synchronized long totale(Context c) {
        carica(c);
        return totale;
    }

    /** Tutti i siti bloccati, dal più recente. */
    public static synchronized List<Voce> recenti(Context c) {
        carica(c);
        List<Voce> l = copie(false);
        l.sort((a, b) -> Long.compare(b.ultima, a.ultima));
        return l;
    }

    /** I siti bloccati oggi, dal più bloccato. */
    public static synchronized List<Voce> diOggi(Context c) {
        carica(c);
        controllaGiorno();
        List<Voce> l = copie(true);
        l.sort((a, b) -> a.oggi != b.oggi ? Integer.compare(b.oggi, a.oggi) : Long.compare(b.ultima, a.ultima));
        return l;
    }

    /** Tutti i siti bloccati da sempre, dal più bloccato. */
    public static synchronized List<Voce> perTotale(Context c) {
        carica(c);
        List<Voce> l = copie(false);
        l.sort((a, b) -> a.totale != b.totale ? Long.compare(b.totale, a.totale) : Long.compare(b.ultima, a.ultima));
        return l;
    }

    private static List<Voce> copie(boolean soloOggi) {
        List<Voce> l = new ArrayList<>();
        for (Voce v : voci.values()) {
            Voce k = v.copia();
            if (k.giorno != giorno) k.oggi = 0;
            if (soloOggi && k.oggi == 0) continue;
            l.add(k);
        }
        return l;
    }

    /** Cancella l'elenco dei siti bloccati (i due contatori restano). */
    public static synchronized void svuota(Context c) {
        carica(c);
        voci.clear();
        registroCambiato = false;
        file(c).delete();
    }
}
