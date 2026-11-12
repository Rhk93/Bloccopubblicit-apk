package com.rhk93.scudo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Decide se un dominio va bloccato.
 * Un dominio è bloccato se lui o un suo "genitore" è in lista
 * (es. bloccando doubleclick.net si blocca anche ads.g.doubleclick.net).
 * I domini consentiti dall'utente vincono sempre.
 */
public final class Filtro {

    /** Lista di base, sempre attiva, divisa in gruppi: {nome, descrizione, domini...}. */
    public static final String[][] GRUPPI = {
            {"Pubblicità di Google", "Banner, video e statistiche pubblicitarie di Google nelle app e sui siti.",
                    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                    "adservice.google.com", "googletagservices.com", "admob.com", "google-analytics.com",
                    "app-measurement.com", "ads.youtube.com"},
            {"Pubblicità nelle app e nei giochi", "Le reti che mostrano i video e i banner dentro giochi e app gratuite.",
                    "applovin.com", "applvn.com", "unityads.unity3d.com", "supersonicads.com",
                    "ironsrc.mobi", "vungle.com", "chartboost.com", "adcolony.com", "inmobi.com",
                    "mintegral.net", "rayjump.com", "pangle.io", "pangleglobal.com", "fyber.com",
                    "inner-active.mobi", "tapjoy.com", "tapjoyads.com", "startappservice.com",
                    "startapp.com", "appnext.com", "mopub.com", "smaato.net", "adsmogo.com",
                    "an.facebook.com", "ads.tiktok.com", "analytics.tiktok.com", "ads-api.tiktok.com",
                    "ads.yahoo.com", "analytics.yahoo.com", "ads.linkedin.com", "ads.pinterest.com",
                    "ads.twitter.com", "ads-api.twitter.com", "ads.reddit.com"},
            {"Pubblicità sui siti", "Banner, popup e «articoli sponsorizzati» delle pagine web.",
                    "amazon-adsystem.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
                    "casalemedia.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "teads.tv",
                    "adform.net", "smartadserver.com", "seedtag.com", "triplelift.com", "3lift.com",
                    "sharethrough.com", "bidswitch.net", "adsrvr.org", "mathtag.com", "media.net",
                    "yieldlab.net", "adition.com", "advertising.com", "serving-sys.com", "flashtalking.com",
                    "spotxchange.com", "springserve.com", "smartclip.net", "stickyadstv.com", "zemanta.com",
                    "moatads.com", "doubleverify.com", "adsafeprotected.com", "revcontent.com", "mgid.com",
                    "propellerads.com", "popads.net", "popcash.net", "adcash.com", "exoclick.com",
                    "juicyads.com", "trafficjunky.net", "hilltopads.net", "adsterra.com", "onclickads.net",
                    "admaven.com", "clickadu.com", "33across.com", "gumgum.com", "undertone.com",
                    "sovrn.com", "lijit.com", "contextweb.com", "yieldmo.com", "districtm.io", "emxdgt.com",
                    "adkernel.com", "betweendigital.com", "onetag-sys.com", "richaudience.com"},
            {"Tracciamento e profilazione", "Servizi che seguono cosa fai per costruire un tuo profilo pubblicitario.",
                    "scorecardresearch.com", "quantserve.com", "quantcount.com", "demdex.net",
                    "everesttech.net", "bluekai.com", "krxd.net", "exelator.com", "tapad.com", "agkn.com",
                    "rlcdn.com", "adjust.com", "appsflyer.com", "kochava.com", "mixpanel.com", "hotjar.com",
                    "mouseflow.com", "fullstory.com", "clarity.ms", "bat.bing.com", "nr-data.net",
                    "crwdcntrl.net", "omtrdc.net", "2o7.net", "chartbeat.com", "chartbeat.net",
                    "pixel.facebook.com", "analytics.google.com", "stats.g.doubleclick.net", "segment.io",
                    "amplitude.com", "comscore.com", "webtrends.com", "statcounter.com", "histats.com",
                    "addthis.com", "sharethis.com"},
            {"Pubblicità dei produttori di telefoni", "Pubblicità e statistiche di Xiaomi, Samsung, Huawei, Oppo, Vivo, Realme.",
                    "api.ad.xiaomi.com", "sdkconfig.ad.xiaomi.com", "ad.mi.com", "ad.xiaomi.com",
                    "tracking.miui.com", "data.mistat.xiaomi.com", "tracking.intl.miui.com",
                    "samsungads.com", "ads.samsung.com", "ads.huawei.com", "adx.ads.oppomobile.com",
                    "adsfs.oppomobile.com", "ads.vivo.com", "adsx.realme.com"},
    };

    /** Tutti i domini della lista di base, in un unico elenco. */
    public static final String[] BASE = unisciGruppi();

    private static String[] unisciGruppi() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        for (String[] g : GRUPPI) {
            for (int i = 2; i < g.length; i++) l.add(g[i]);
        }
        return l.toArray(new String[0]);
    }

    /** Liste scaricabili: {id, nome, descrizione, indirizzo}. Solo indirizzi fissi e cifrati. */
    public static final String[][] LISTE = {
            {"stevenblack", "StevenBlack unificata",
                    "Pubblicità e malware, la più usata al mondo (circa 100.000 domini).",
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"},
            {"adguard", "AdGuard DNS",
                    "Pubblicità e tracciamento, pensata apposta per i filtri DNS.",
                    "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"},
            {"hagezi", "HaGeZi Light",
                    "Lista leggera e prudente: blocca tanto e rompe pochissime app.",
                    "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt"},
    };

    private static volatile Set<String> bloccati = new HashSet<>(Arrays.asList(BASE));
    private static volatile Set<String> consentiti = new HashSet<>();
    private static volatile boolean caricato = false;

    private Filtro() {}

    public static void caricaSeServe(Context c) {
        if (!caricato) carica(c);
    }

    /** Ricarica tutto: lista di base + liste scaricate attive + domini scelti dall'utente. */
    public static synchronized void carica(Context c) {
        Context app = c.getApplicationContext();
        Set<String> s = new HashSet<>(Arrays.asList(BASE));
        for (String id : Prefs.set(app, Prefs.LISTE)) {
            File f = file(app, id);
            if (!f.exists()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String riga;
                while ((riga = r.readLine()) != null) {
                    if (!riga.isEmpty()) s.add(riga);
                }
            } catch (Exception ignorata) {
                // file rovinato: lo saltiamo
            }
        }
        s.addAll(Prefs.set(app, Prefs.BLOCCATI_MIEI));
        bloccati = s;
        consentiti = Prefs.set(app, Prefs.CONSENTITI);
        caricato = true;
    }

    public static void impostaConsentiti(Set<String> s) {
        consentiti = new HashSet<>(s);
    }

    public static int totale() {
        return bloccati.size();
    }

    public static boolean bloccato(String dominio) {
        if (corrisponde(consentiti, dominio)) return false;
        return corrisponde(bloccati, dominio);
    }

    private static boolean corrisponde(Set<String> insieme, String dominio) {
        if (insieme.isEmpty()) return false;
        String d = dominio;
        while (true) {
            if (insieme.contains(d)) return true;
            int i = d.indexOf('.');
            if (i < 0) return false;
            d = d.substring(i + 1);
        }
    }

    private static File file(Context c, String id) {
        return new File(c.getFilesDir(), "lista_" + id + ".txt");
    }

    public static String[] lista(String id) {
        for (String[] l : LISTE) if (l[0].equals(id)) return l;
        return null;
    }

    /** Scarica una lista (solo se lo chiede l'utente). Restituisce il numero di domini. */
    public static int scarica(Context c, String id) throws Exception {
        String[] l = lista(id);
        if (l == null) throw new IllegalArgumentException("lista sconosciuta");
        Context app = c.getApplicationContext();
        File tmp = new File(app.getFilesDir(), "lista_" + id + ".tmp");
        int conta = 0;
        HttpsURLConnection con = (HttpsURLConnection) new URL(l[3]).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "Scudo");
        try {
            if (con.getResponseCode() != 200) throw new Exception("errore " + con.getResponseCode());
            HashSet<String> visti = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
                 BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
                String riga;
                while ((riga = r.readLine()) != null && conta < 600000) {
                    String d = analizza(riga);
                    if (d != null && visti.add(d)) {
                        w.write(d);
                        w.write('\n');
                        conta++;
                    }
                }
            }
        } finally {
            con.disconnect();
        }
        if (conta == 0) {
            tmp.delete();
            throw new Exception("lista vuota");
        }
        File definitivo = file(app, id);
        definitivo.delete();
        if (!tmp.renameTo(definitivo)) throw new Exception("salvataggio non riuscito");
        return conta;
    }

    public static void elimina(Context c, String id) {
        file(c.getApplicationContext(), id).delete();
    }

    /** Legge una riga di lista in formato hosts, AdGuard (||dominio^) o semplice. */
    static String analizza(String riga) {
        String s = riga.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || s.startsWith("@@") || s.startsWith("[")) {
            return null;
        }
        int hash = s.indexOf('#');
        if (hash > 0) s = s.substring(0, hash).trim();
        String d;
        if (s.startsWith("||")) {
            int fine = s.indexOf('^');
            if (fine < 0) return null;
            String resto = s.substring(fine + 1);
            if (!resto.isEmpty() && !resto.equals("$important")) return null;
            d = s.substring(2, fine);
        } else {
            String[] parti = s.split("\\s+");
            if (parti.length >= 2 && (parti[0].equals("0.0.0.0") || parti[0].equals("127.0.0.1"))) {
                d = parti[1];
            } else if (parti.length == 1) {
                d = parti[0];
            } else {
                return null;
            }
        }
        d = d.toLowerCase(Locale.ROOT);
        if (d.startsWith("*.")) d = d.substring(2);
        if (d.startsWith(".")) d = d.substring(1);
        if (!valido(d)) return null;
        return d;
    }

    static boolean valido(String d) {
        if (d.length() < 4 || d.length() > 253 || d.indexOf('.') < 0) return false;
        if (d.equals("localhost") || d.equals("0.0.0.0") || d.endsWith(".local")
                || d.equals("localhost.localdomain") || d.equals("broadcasthost")) return false;
        boolean soloNumeri = true;
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.' || ch == '_';
            if (!ok) return false;
            if (ch != '.' && (ch < '0' || ch > '9')) soloNumeri = false;
        }
        return !soloNumeri && !d.startsWith(".") && !d.endsWith(".");
    }

    /** Pulisce un dominio scritto a mano dall'utente (toglie https://, percorsi, spazi). */
    public static String pulisci(String testo) {
        String d = testo.trim().toLowerCase(Locale.ROOT);
        int i = d.indexOf("://");
        if (i >= 0) d = d.substring(i + 3);
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        int due = d.indexOf(':');
        if (due >= 0) d = d.substring(0, due);
        if (d.startsWith("*.")) d = d.substring(2);
        return valido(d) ? d : null;
    }
}
