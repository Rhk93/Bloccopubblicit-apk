package com.rhk93.scudo;

import android.content.Context;

/** Elenco dei server DNS selezionabili, con una breve descrizione. */
public final class ServerDns {

    public final String id;
    public final String nome;
    public final String descrizione;
    public final String doh;   // indirizzo cifrato (DNS-over-HTTPS)
    public final String ip;    // indirizzo normale

    public ServerDns(String id, String nome, String descrizione, String doh, String ip) {
        this.id = id;
        this.nome = nome;
        this.descrizione = descrizione;
        this.doh = doh;
        this.ip = ip;
    }

    public static final String PERSONALIZZATO = "personalizzato";

    public static final ServerDns[] TUTTI = {
            new ServerDns("quad9", "Quad9",
                    "Consigliato. Fondazione no-profit svizzera: non registra il tuo indirizzo e blocca i siti truffa e malware.",
                    "https://dns.quad9.net/dns-query", "9.9.9.9"),
            new ServerDns("cloudflare", "Cloudflare",
                    "Il più veloce. Cancella i dati entro 24 ore, verificato da revisori esterni. Nessun filtro.",
                    "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
            new ServerDns("cloudflare_sic", "Cloudflare Sicurezza",
                    "Come Cloudflare, in più blocca malware e phishing.",
                    "https://security.cloudflare-dns.com/dns-query", "1.1.1.2"),
            new ServerDns("cloudflare_fam", "Cloudflare Famiglia",
                    "Blocca malware e siti per adulti. Adatto ai telefoni dei ragazzi.",
                    "https://family.cloudflare-dns.com/dns-query", "1.1.1.3"),
            new ServerDns("mullvad", "Mullvad",
                    "Svedese, nessun registro. Molto attento alla privacy.",
                    "https://dns.mullvad.net/dns-query", "194.242.2.2"),
            new ServerDns("mullvad_ads", "Mullvad Antipubblicità",
                    "Come Mullvad, in più blocca pubblicità e tracciamento (doppio filtro).",
                    "https://adblock.dns.mullvad.net/dns-query", "194.242.2.3"),
            new ServerDns("adguard", "AdGuard",
                    "Blocca già lui pubblicità e tracciamento: doppio filtro insieme a Scudo.",
                    "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
            new ServerDns("adguard_fam", "AdGuard Famiglia",
                    "Pubblicità, tracciamento, siti per adulti e ricerca sicura.",
                    "https://family.adguard-dns.com/dns-query", "94.140.14.15"),
            new ServerDns("dns4eu", "DNS4EU Protezione",
                    "Servizio europeo sostenuto dall'UE, server in Europa. Blocca i siti pericolosi.",
                    "https://protective.joindns4.eu/dns-query", "86.54.11.1"),
            new ServerDns("dns4eu_ads", "DNS4EU Antipubblicità",
                    "Come DNS4EU Protezione, in più blocca la pubblicità.",
                    "https://noads.joindns4.eu/dns-query", "86.54.11.13"),
            new ServerDns("controld", "Control D Antipubblicità",
                    "Gratuito, blocca pubblicità e tracciamento.",
                    "https://freedns.controld.com/p2", "76.76.2.2"),
            new ServerDns("google", "Google",
                    "Veloce e affidabile, ma Google conserva alcuni dati per un periodo.",
                    "https://dns.google/dns-query", "8.8.8.8"),
            new ServerDns("opendns", "OpenDNS (Cisco)",
                    "Storico e stabile, gestito da Cisco.",
                    "https://doh.opendns.com/dns-query", "208.67.222.222"),
    };

    /** Il server scelto dall'utente (Quad9 se non ha scelto). */
    public static ServerDns attuale(Context c) {
        String id = Prefs.dns(c);
        if (PERSONALIZZATO.equals(id)) {
            String url = Prefs.dnsUrl(c);
            String ip = Prefs.dnsIp(c);
            String descr = !url.isEmpty() ? url : (!ip.isEmpty() ? ip : "Non impostato");
            return new ServerDns(PERSONALIZZATO, "Personalizzato", descr, url, ip);
        }
        for (ServerDns s : TUTTI) {
            if (s.id.equals(id)) return s;
        }
        return TUTTI[0];
    }
}
