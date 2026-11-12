package com.rhk93.scudo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

/**
 * Contatori delle pubblicità bloccate e registro dei domini bloccati.
 * Il registro resta SOLO in memoria: si cancella quando la protezione si spegne.
 * Vengono salvati sul telefono solo i due numeri (oggi e totale).
 */
public final class Statistiche {

    public static final class Voce {
        public final String dominio;
        public long ultima;
        public int conta;

        Voce(String d) { dominio = d; }
    }

    private static boolean caricato;
    private static long totale;
    private static long oggi;
    private static int giorno;
    private static int nonSalvati;
    private static final HashMap<String, Voce> voci = new HashMap<>();

    private Statistiche() {}

    public static synchronized void carica(Context c) {
        if (caricato) return;
        caricato = true;
        SharedPreferences s = Prefs.sp(c);
        totale = s.getLong("st_totale", 0);
        oggi = s.getLong("st_oggi", 0);
        giorno = s.getInt("st_giorno", 0);
        controllaGiorno();
    }

    private static void controllaGiorno() {
        Calendar k = Calendar.getInstance();
        int g = k.get(Calendar.YEAR) * 1000 + k.get(Calendar.DAY_OF_YEAR);
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
        if (++nonSalvati >= 20) salva(c);
        if (Prefs.registro(c)) {
            Voce v = voci.get(dominio);
            if (v == null) {
                if (voci.size() >= 400) rimuoviPiuVecchia();
                v = new Voce(dominio);
                voci.put(dominio, v);
            }
            v.conta++;
            v.ultima = System.currentTimeMillis();
        }
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

    public static synchronized List<Voce> recenti() {
        List<Voce> l = new ArrayList<>(voci.values());
        l.sort((a, b) -> Long.compare(b.ultima, a.ultima));
        return l;
    }

    public static synchronized void svuota() {
        voci.clear();
    }
}
