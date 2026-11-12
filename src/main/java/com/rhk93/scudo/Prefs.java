package com.rhk93.scudo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Tutte le impostazioni dell'app, salvate solo sul telefono. */
public final class Prefs {

    public static final String ESCLUSE = "escluse";
    public static final String SENS_VISTI = "sensibili_visti";
    public static final String CONSENTITI = "consentiti";
    public static final String BLOCCATI_MIEI = "bloccati_miei";
    public static final String LISTE = "liste_attive";
    public static final String DNS = "dns";
    public static final String CIFRATO = "cifrato";
    public static final String DNS_URL = "dns_url";
    public static final String DNS_IP = "dns_ip";
    public static final String TEMA = "tema";
    public static final String ACCENTO = "accento";
    public static final String COLORE_MIO = "colore_mio";
    public static final String SFONDO = "sfondo";
    public static final String FONT = "font";
    public static final String SCALA = "scala";
    public static final String REGISTRO = "registro";
    public static final String INIZIALIZZATO = "inizializzato";

    public static final int ACCENTO_PREDEFINITO = 0xFF007AFF;

    private static SharedPreferences sp;

    private Prefs() {}

    public static synchronized SharedPreferences sp(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("scudo", Context.MODE_PRIVATE);
        }
        return sp;
    }

    public static Set<String> set(Context c, String chiave) {
        Set<String> s = sp(c).getStringSet(chiave, null);
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    public static void salvaSet(Context c, String chiave, Set<String> valori) {
        sp(c).edit().putStringSet(chiave, new HashSet<>(valori)).apply();
    }

    public static void metti(Context c, String chiave, Object v) {
        SharedPreferences.Editor e = sp(c).edit();
        if (v instanceof Integer) e.putInt(chiave, (Integer) v);
        else if (v instanceof Boolean) e.putBoolean(chiave, (Boolean) v);
        else if (v instanceof Float) e.putFloat(chiave, (Float) v);
        else if (v instanceof Long) e.putLong(chiave, (Long) v);
        else if (v instanceof String) e.putString(chiave, (String) v);
        e.apply();
    }

    public static Set<String> escluse(Context c) { return set(c, ESCLUSE); }

    public static String dns(Context c) { return sp(c).getString(DNS, "quad9"); }

    public static boolean cifrato(Context c) { return sp(c).getBoolean(CIFRATO, true); }

    public static String dnsUrl(Context c) { return sp(c).getString(DNS_URL, ""); }

    public static String dnsIp(Context c) { return sp(c).getString(DNS_IP, ""); }

    public static int tema(Context c) { return sp(c).getInt(TEMA, 0); }

    public static int accento(Context c) { return sp(c).getInt(ACCENTO, ACCENTO_PREDEFINITO); }

    public static int coloreMio(Context c) { return sp(c).getInt(COLORE_MIO, 0xFFFF8800); }

    public static int sfondo(Context c) { return sp(c).getInt(SFONDO, 0); }

    public static int font(Context c) { return sp(c).getInt(FONT, 0); }

    public static float scala(Context c) { return sp(c).getFloat(SCALA, 1f); }

    public static boolean registro(Context c) { return sp(c).getBoolean(REGISTRO, true); }

    /** Riporta l'aspetto ai valori iniziali (non tocca le altre impostazioni). */
    public static void ripristinaAspetto(Context c) {
        sp(c).edit()
                .remove(TEMA).remove(ACCENTO).remove(SFONDO)
                .remove(FONT).remove(SCALA)
                .apply();
    }
}
