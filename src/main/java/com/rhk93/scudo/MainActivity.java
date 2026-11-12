package com.rhk93.scudo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

public class MainActivity extends Activity {

    private static final int RICHIESTA_VPN = 10;

    /** Le 4 app sensibili scelte dall'utente: {nome mostrato, parola da cercare}. */
    private static final String[][] SENSIBILI = {
            {"BPER Banca", "bper"},
            {"WhatsApp", "whatsapp"},
            {"Telegram", "telegram"},
            {"PosteID", "posteid"},
    };
    /** Pacchetti noti, esclusi fin dal primo avvio anche prima di leggere l'elenco app. */
    private static final String[] PACCHETTI_NOTI = {
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.thunderdog.challegram",
            "posteitaliane.posteapp.appposteid",
    };

    static final class AppInfo {
        String pkg;
        String nome;
        Drawable icona;
    }

    private static List<AppInfo> appCache;
    private static boolean caricandoApp;

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout contenuto;
    private LinearLayout barra;
    private ScrollView scroll;
    private int tab = 0;
    private String sotto = null;
    private boolean statoMostrato;
    private TextView tvOggi, tvTotale;
    private LinearLayout listaApp;
    private String filtroApp = "";

    private final Runnable riavvio = () -> {
        if (ScudoVpnService.attivo) avviaServizio(false);
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            aggiornaNumeri();
            h.postDelayed(this, 1000);
        }
    };

    // ================================================================== ciclo di vita

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Statistiche.carica(this);
        primoAvvio();
        final Context app = getApplicationContext();
        new Thread(() -> {
            Filtro.carica(app);
            h.post(() -> {
                if (sotto == null && tab == 0) mostraMantieni();
            });
        }).start();
        Stile.aggiorna(this);
        costruisci();
        caricaAppAsync();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        Statistiche.salva(this);
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        Stile.aggiorna(this);
        costruisci();
    }

    @Override
    public void onBackPressed() {
        if (sotto != null) {
            sotto = null;
            mostra();
        } else if (tab != 0) {
            tab = 0;
            mostra();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RICHIESTA_VPN) {
            if (res == RESULT_OK) avviaServizio(true);
            else toast("Senza il permesso VPN Scudo non può filtrare la pubblicità");
        }
    }

    private void primoAvvio() {
        if (Prefs.sp(this).getBoolean(Prefs.INIZIALIZZATO, false)) return;
        Set<String> esc = Prefs.escluse(this);
        for (String p : PACCHETTI_NOTI) esc.add(p);
        Prefs.salvaSet(this, Prefs.ESCLUSE, esc);
        Prefs.metti(this, Prefs.INIZIALIZZATO, true);
    }

    // ================================================================== struttura

    private void costruisci() {
        Window w = getWindow();
        w.setStatusBarColor(Stile.sfondo);
        w.setNavigationBarColor(Stile.barra);
        int flag = 0;
        if (!Stile.scuro) {
            flag |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flag);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Stile.sfondo);
        contenuto = new FrameLayout(this);
        root.addView(contenuto, new LinearLayout.LayoutParams(-1, 0, 1f));
        View linea = new View(this);
        linea.setBackgroundColor(Stile.separatore);
        root.addView(linea, new LinearLayout.LayoutParams(-1, 1));
        barra = new LinearLayout(this);
        barra.setBackgroundColor(Stile.barra);
        root.addView(barra, new LinearLayout.LayoutParams(-1, dp(58)));
        setContentView(root);
        mostra();
    }

    private void mostra() {
        contenuto.removeAllViews();
        tvOggi = null;
        tvTotale = null;
        listaApp = null;
        View v;
        if (sotto != null) {
            switch (sotto) {
                case "dns": v = paginaDns(); break;
                case "liste": v = paginaListe(); break;
                case "aspetto": v = paginaAspetto(); break;
                case "bloccati_oggi": v = paginaBloccati(true); break;
                case "bloccati_totale": v = paginaBloccati(false); break;
                default: v = paginaPrivacy(); break;
            }
        } else {
            switch (tab) {
                case 0: v = paginaHome(); break;
                case 1: v = paginaApp(); break;
                case 2: v = paginaRegistro(); break;
                default: v = paginaImpostazioni(); break;
            }
        }
        contenuto.addView(v, new FrameLayout.LayoutParams(-1, -1));
        costruisciBarra();
    }

    /** Ricostruisce la pagina senza perdere il punto in cui eri arrivato scorrendo. */
    private void mostraMantieni() {
        final int y = scroll != null ? scroll.getScrollY() : 0;
        mostra();
        final ScrollView s = scroll;
        if (s != null) s.post(() -> s.scrollTo(0, y));
    }

    private void applicaAspetto() {
        final int y = scroll != null ? scroll.getScrollY() : 0;
        Stile.aggiorna(this);
        costruisci();
        final ScrollView s = scroll;
        if (s != null) s.post(() -> s.scrollTo(0, y));
    }

    private void costruisciBarra() {
        barra.removeAllViews();
        int[] icone = {R.drawable.ic_scudo, R.drawable.ic_app, R.drawable.ic_registro, R.drawable.ic_impostazioni};
        String[] nomi = {"Scudo", "App escluse", "Registro", "Impostazioni"};
        for (int i = 0; i < 4; i++) {
            final int indice = i;
            boolean sel = tab == i;
            int col = sel ? Stile.accento : Stile.testo2;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageResource(icone[i]);
            iv.setColorFilter(col);
            b.addView(iv, new LinearLayout.LayoutParams(dp(25), dp(25)));
            TextView t = new TextView(this);
            t.setText(nomi[i]);
            t.setTextColor(col);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            t.setTypeface(sel ? Stile.fontGrassetto : Stile.font);
            t.setPadding(dp(2), dp(2), dp(2), 0);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.addView(t, new LinearLayout.LayoutParams(-1, -2));
            b.setOnClickListener(v -> {
                tab = indice;
                sotto = null;
                mostra();
            });
            barra.addView(b, new LinearLayout.LayoutParams(0, -1, 1f));
        }
    }

    // ================================================================== mattoncini grafici

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView t(String s, float size, int colore, boolean grassetto) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(colore);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * Stile.scala);
        v.setTypeface(grassetto ? Stile.fontGrassetto : Stile.font);
        return v;
    }

    private GradientDrawable arrotondato(int colore, float raggioDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colore);
        g.setCornerRadius(dp(raggioDp));
        return g;
    }

    private Drawable premibile() {
        int c = Stile.scuro ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(c), null, new ColorDrawable(Color.WHITE));
    }

    private LinearLayout pagina(String titolo, String indietro, View azioneDestra) {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Stile.sfondo);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), dp(36));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        if (indietro != null) {
            TextView b = t("‹ " + indietro, 17, Stile.accento, false);
            b.setPadding(0, dp(10), dp(20), dp(2));
            b.setOnClickListener(v -> {
                sotto = null;
                mostra();
            });
            col.addView(b, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(indietro == null ? 22 : 4), 0, dp(6));
        top.addView(t(titolo, 34, Stile.testo, true), new LinearLayout.LayoutParams(0, -2, 1f));
        if (azioneDestra != null) top.addView(azioneDestra);
        col.addView(top);
        return col;
    }

    private LinearLayout sezione(LinearLayout parent, String titolo, String nota) {
        if (titolo != null) {
            TextView h = t(titolo.toUpperCase(Locale.ITALY), 13, Stile.testo2, false);
            h.setPadding(dp(16), dp(22), dp(16), dp(7));
            parent.addView(h);
        } else {
            parent.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(20)));
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(arrotondato(Stile.scheda, 12));
        card.setClipToOutline(true);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        if (nota != null) {
            TextView n = t(nota, 13, Stile.testo2, false);
            n.setPadding(dp(16), dp(7), dp(16), 0);
            parent.addView(n);
        }
        return card;
    }

    private void aggiungi(LinearLayout card, View riga) {
        if (card.getChildCount() > 0) {
            View s = new View(this);
            s.setBackgroundColor(Stile.separatore);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            lp.leftMargin = dp(16);
            card.addView(s, lp);
        }
        card.addView(riga);
    }

    private LinearLayout riga(View icona, String titolo, String sottotitolo, View destra, View.OnClickListener click) {
        return riga(icona, titolo, Stile.testo, sottotitolo, destra, click);
    }

    private LinearLayout riga(View icona, String titolo, int coloreTitolo, String sottotitolo,
                              View destra, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(48));
        r.setPadding(dp(16), dp(10), dp(14), dp(10));
        if (icona != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.rightMargin = dp(14);
            r.addView(icona, lp);
        }
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(t(titolo, 17, coloreTitolo, false));
        if (sottotitolo != null) {
            TextView s = t(sottotitolo, 13, Stile.testo2, false);
            s.setPadding(0, dp(2), 0, 0);
            tx.addView(s);
        }
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        if (destra != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(10);
            r.addView(destra, lp);
        }
        if (click != null) {
            r.setOnClickListener(click);
            r.setBackground(premibile());
        }
        return r;
    }

    private ImageView tessera(int risorsa, int colore) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(risorsa);
        iv.setColorFilter(Color.WHITE);
        iv.setBackground(arrotondato(colore, 7));
        iv.setPadding(dp(5), dp(5), dp(5), dp(5));
        return iv;
    }

    private ImageView iconaApp(AppInfo a) {
        ImageView iv = new ImageView(this);
        if (a != null && a.icona != null) {
            iv.setImageDrawable(a.icona);
        } else {
            iv.setImageResource(R.drawable.ic_app);
            iv.setColorFilter(Color.WHITE);
            iv.setBackground(arrotondato(0xFFC7C7CC, 7));
            iv.setPadding(dp(5), dp(5), dp(5), dp(5));
        }
        return iv;
    }

    private TextView freccia(String valore) {
        String s = (valore == null || valore.isEmpty()) ? "›" : valore + "  ›";
        TextView v = t(s, 17, Stile.testo2, false);
        v.setMaxLines(1);
        return v;
    }

    private TextView spunta(boolean sel) {
        return t(sel ? "✓" : " ", 19, Stile.accento, true);
    }

    private TextView etichetta(String testo, int colore) {
        TextView v = t(testo, 12, colore, true);
        v.setBackground(arrotondato(Stile.conAlfa(colore, 0x26), 10));
        v.setPadding(dp(9), dp(3), dp(9), dp(3));
        return v;
    }

    private TextView bottoneTesto(String testo, int colore, View.OnClickListener click) {
        TextView v = t(testo, 15, colore, false);
        v.setPadding(dp(8), dp(6), dp(4), dp(6));
        v.setOnClickListener(click);
        return v;
    }

    private View segmentato(String[] opzioni, int selezionato, IntConsumer azione) {
        LinearLayout box = new LinearLayout(this);
        box.setBackground(arrotondato(Stile.segmento, 9));
        box.setPadding(dp(2), dp(2), dp(2), dp(2));
        for (int i = 0; i < opzioni.length; i++) {
            final int indice = i;
            boolean sel = i == selezionato;
            TextView o = t(opzioni[i], 14, Stile.testo, sel);
            o.setGravity(Gravity.CENTER);
            o.setPadding(0, dp(7), 0, dp(7));
            if (sel) {
                o.setBackground(arrotondato(Stile.segmentoSel, 7));
                o.setElevation(dp(1));
            }
            o.setOnClickListener(v -> azione.accept(indice));
            box.addView(o, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(12), dp(12), dp(12), dp(12));
        f.addView(box);
        return f;
    }

    private EditText campo(String suggerimento, String valore) {
        EditText e = new EditText(this);
        e.setHint(suggerimento);
        e.setText(valore);
        e.setSingleLine(true);
        e.setTextColor(Stile.testo);
        e.setHintTextColor(Stile.testo2);
        e.setTypeface(Stile.font);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17 * Stile.scala);
        e.setBackground(arrotondato(Stile.campo, 10));
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String numero(long n) {
        return String.format(Locale.ITALY, "%,d", n);
    }

    private AlertDialog.Builder dialogo() {
        return new AlertDialog.Builder(this, Stile.scuro
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private void chiediDominio(String titolo, String spiegazione, java.util.function.Consumer<String> fatto) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        EditText e = campo("esempio.com", "");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(e);
        dialogo().setTitle(titolo).setMessage(spiegazione).setView(box)
                .setPositiveButton("Aggiungi", (d, w) -> {
                    String dom = Filtro.pulisci(e.getText().toString());
                    if (dom == null) toast("Dominio non valido");
                    else fatto.accept(dom);
                })
                .setNegativeButton("Annulla", null).show();
    }

    // ================================================================== servizio VPN

    private void commuta() {
        if (ScudoVpnService.attivo) {
            Intent i = new Intent(this, ScudoVpnService.class).setAction(ScudoVpnService.FERMA);
            startService(i);
            h.postDelayed(this::mostraMantieni, 400);
        } else {
            Intent i = VpnService.prepare(this);
            if (i != null) startActivityForResult(i, RICHIESTA_VPN);
            else avviaServizio(true);
        }
    }

    private void avviaServizio(boolean aggiornaPagina) {
        try {
            startService(new Intent(this, ScudoVpnService.class).setAction(ScudoVpnService.AVVIA));
        } catch (Exception e) {
            toast("Impossibile avviare la protezione");
        }
        if (aggiornaPagina) h.postDelayed(this::mostraMantieni, 500);
    }

    private void programmaRiavvio() {
        h.removeCallbacks(riavvio);
        h.postDelayed(riavvio, 700);
    }

    private void aggiornaNumeri() {
        if (tvOggi != null) tvOggi.setText(numero(Statistiche.oggi(this)));
        if (tvTotale != null) tvTotale.setText(numero(Statistiche.totale(this)));
        if (sotto == null && tab == 0 && statoMostrato != ScudoVpnService.attivo) mostraMantieni();
    }

    private boolean dnsPrivatoForzato() {
        try {
            String m = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            return "hostname".equals(m);
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================== elenco app

    private void caricaAppAsync() {
        if (caricandoApp) return;
        caricandoApp = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            List<AppInfo> l = leggiApp(app);
            h.post(() -> {
                appCache = l;
                caricandoApp = false;
                segnaSensibili();
                if (sotto == null && tab == 1 && listaApp != null) riempiListaApp();
                else if (sotto == null && tab == 0) mostraMantieni();
            });
        }).start();
    }

    private static List<AppInfo> leggiApp(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> trovate = pm.queryIntentActivities(i, 0);
        Map<String, AppInfo> m = new HashMap<>();
        for (ResolveInfo ri : trovate) {
            String p = ri.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || m.containsKey(p)) continue;
            AppInfo a = new AppInfo();
            a.pkg = p;
            a.nome = String.valueOf(ri.loadLabel(pm));
            try {
                a.icona = ri.loadIcon(pm);
            } catch (Exception e) {
                a.icona = null;
            }
            m.put(p, a);
        }
        List<AppInfo> l = new ArrayList<>(m.values());
        l.sort((x, y) -> x.nome.compareToIgnoreCase(y.nome));
        return l;
    }

    private static String chiaveSensibile(AppInfo a) {
        String nome = a.nome.toLowerCase(Locale.ROOT).replace(" ", "");
        String p = a.pkg.toLowerCase(Locale.ROOT);
        for (String[] s : SENSIBILI) {
            if (nome.contains(s[1]) || p.contains(s[1])) return s[1];
        }
        return null;
    }

    /** Le app sensibili trovate per la prima volta vengono escluse da sole. */
    private void segnaSensibili() {
        if (appCache == null) return;
        Set<String> visti = Prefs.set(this, Prefs.SENS_VISTI);
        Set<String> esc = Prefs.escluse(this);
        boolean cambiato = false;
        for (AppInfo a : appCache) {
            if (chiaveSensibile(a) != null && !visti.contains(a.pkg)) {
                visti.add(a.pkg);
                esc.add(a.pkg);
                cambiato = true;
            }
        }
        if (cambiato) {
            Prefs.salvaSet(this, Prefs.SENS_VISTI, visti);
            Prefs.salvaSet(this, Prefs.ESCLUSE, esc);
            programmaRiavvio();
        }
    }

    // ================================================================== pagina: Scudo (home)

    private View paginaHome() {
        LinearLayout c = pagina("Scudo", null, null);
        final boolean on = ScudoVpnService.attivo;
        statoMostrato = on;

        FrameLayout cerchio = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                on ? new int[]{Stile.mescola(Stile.accento, Color.WHITE, 0.25f), Stile.accento}
                        : new int[]{Stile.scuro ? 0xFF3A3A3C : 0xFFD1D1D6, Stile.scuro ? 0xFF2C2C2E : 0xFFAEAEB2});
        g.setShape(GradientDrawable.OVAL);
        cerchio.setBackground(g);
        cerchio.setElevation(on ? dp(10) : dp(2));
        ImageView scudo = new ImageView(this);
        scudo.setImageResource(R.drawable.ic_scudo);
        scudo.setColorFilter(Color.WHITE);
        cerchio.addView(scudo, new FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER));
        cerchio.setOnClickListener(v -> {
            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()).start();
            commuta();
        });
        LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(dp(180), dp(180));
        lpc.gravity = Gravity.CENTER_HORIZONTAL;
        lpc.topMargin = dp(16);
        lpc.bottomMargin = dp(8);
        c.addView(cerchio, lpc);

        TextView stato = t(on ? "Protezione attiva" : "Protezione disattivata", 22, Stile.testo, true);
        stato.setGravity(Gravity.CENTER);
        stato.setPadding(0, dp(12), 0, dp(2));
        c.addView(stato, new LinearLayout.LayoutParams(-1, -2));
        TextView sottoStato = t(on ? "La pubblicità viene bloccata in tutte le app non escluse"
                : "Tocca lo scudo per attivare", 15, Stile.testo2, false);
        sottoStato.setGravity(Gravity.CENTER);
        c.addView(sottoStato, new LinearLayout.LayoutParams(-1, -2));

        if (dnsPrivatoForzato()) {
            LinearLayout avviso = sezione(c, null, null);
            avviso.setBackground(arrotondato(Stile.conAlfa(Stile.ARANCIO, 0x30), 12));
            aggiungi(avviso, riga(null, "DNS privato attivo", Stile.ARANCIO,
                    "Nelle impostazioni del telefono è attivo un «DNS privato»: scavalca Scudo. "
                            + "Tocca qui, poi Rete e Internet → DNS privato → Disattivato.",
                    null, v -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                        } catch (Exception e) {
                            toast("Apri Impostazioni → Rete e Internet → DNS privato");
                        }
                    }));
        }

        // contatori
        LinearLayout numeri = new LinearLayout(this);
        LinearLayout.LayoutParams lpn = new LinearLayout.LayoutParams(-1, -2);
        lpn.topMargin = dp(22);
        c.addView(numeri, lpn);
        tvOggi = tesseraNumero(numeri, "Bloccate oggi  ›", Statistiche.oggi(this), true, v -> {
            sotto = "bloccati_oggi";
            mostra();
        });
        tvTotale = tesseraNumero(numeri, "In totale  ›", Statistiche.totale(this), false, v -> {
            sotto = "bloccati_totale";
            mostra();
        });

        LinearLayout card = sezione(c, "Riepilogo", null);
        aggiungi(card, riga(tessera(R.drawable.ic_globo, 0xFF007AFF), "Server DNS", null,
                freccia(ServerDns.attuale(this).nome), v -> {
                    tab = 3;
                    sotto = "dns";
                    mostra();
                }));
        aggiungi(card, riga(tessera(R.drawable.ic_registro, 0xFFFF9500), "Domini nella lista nera", null,
                freccia(numero(Filtro.totale())), v -> {
                    tab = 3;
                    sotto = "liste";
                    mostra();
                }));
        aggiungi(card, riga(tessera(R.drawable.ic_app, 0xFF34C759), "App escluse", null,
                freccia(String.valueOf(Prefs.escluse(this).size() - pacchettiNonInstallati())), v -> {
                    tab = 1;
                    mostra();
                }));

        card = sezione(c, "Le tue app sensibili",
                "Restano sempre fuori dal filtro. Puoi aggiungerne altre dalla pagina «App escluse».");
        Set<String> esc = Prefs.escluse(this);
        Set<String> mie = Prefs.set(this, Prefs.SENS_MIE);
        Set<String> rimosse = Prefs.set(this, Prefs.SENS_RIMOSSE);
        View.OnClickListener vaiApp = v -> {
            tab = 1;
            mostra();
        };
        for (String[] s : SENSIBILI) {
            if (appCache == null) {
                aggiungi(card, riga(iconaApp(null), s[0], null, etichetta("…", Stile.testo2), vaiApp));
                continue;
            }
            boolean installata = false;
            for (AppInfo a : appCache) {
                if (!s[1].equals(chiaveSensibile(a))) continue;
                installata = true;
                if (rimosse.contains(a.pkg) && !mie.contains(a.pkg)) continue;
                aggiungi(card, riga(iconaApp(a), a.nome, null, etichettaStato(esc.contains(a.pkg)), vaiApp));
            }
            if (!installata) {
                aggiungi(card, riga(iconaApp(null), s[0], null, etichetta("Non installata", Stile.testo2), vaiApp));
            }
        }
        if (appCache != null) {
            for (AppInfo a : appCache) {
                if (mie.contains(a.pkg) && chiaveSensibile(a) == null) {
                    aggiungi(card, riga(iconaApp(a), a.nome, null, etichettaStato(esc.contains(a.pkg)), vaiApp));
                }
            }
        }
        return scroll;
    }

    private int pacchettiNonInstallati() {
        if (appCache == null) return 0;
        int n = 0;
        for (String p : Prefs.escluse(this)) {
            boolean c = false;
            for (AppInfo a : appCache) {
                if (a.pkg.equals(p)) {
                    c = true;
                    break;
                }
            }
            if (!c) n++;
        }
        return n;
    }

    private TextView tesseraNumero(LinearLayout parent, String titolo, long valore, boolean primo,
                                   View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(arrotondato(Stile.scheda, 14));
        box.setClipToOutline(true);
        box.setForeground(premibile());
        box.setOnClickListener(click);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView n = t(numero(valore), 30, Stile.accento, true);
        box.addView(n);
        box.addView(t(titolo, 13, Stile.testo2, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (primo) lp.rightMargin = dp(6);
        else lp.leftMargin = dp(6);
        parent.addView(box, lp);
        return n;
    }

    // ================================================================== pagina: App escluse

    private View paginaApp() {
        LinearLayout c = pagina("App escluse", null, null);
        TextView info = t("Le app escluse non passano dal filtro: funzionano come sempre, ma con la loro pubblicità.",
                15, Stile.testo2, false);
        c.addView(info);

        EditText cerca = campo("Cerca app", filtroApp);
        cerca.setCompoundDrawablePadding(dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        c.addView(cerca, lp);
        cerca.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void afterTextChanged(Editable s) {
                filtroApp = s.toString();
                riempiListaApp();
            }
        });

        listaApp = new LinearLayout(this);
        listaApp.setOrientation(LinearLayout.VERTICAL);
        c.addView(listaApp);
        riempiListaApp();
        if (appCache == null) caricaAppAsync();
        return scroll;
    }

    private void riempiListaApp() {
        if (listaApp == null) return;
        listaApp.removeAllViews();
        if (appCache == null) {
            TextView t = t("Caricamento delle app…", 15, Stile.testo2, false);
            t.setPadding(dp(16), dp(24), 0, 0);
            listaApp.addView(t);
            return;
        }
        Set<String> esc = Prefs.escluse(this);
        Set<String> mie = Prefs.set(this, Prefs.SENS_MIE);
        Set<String> rimosse = Prefs.set(this, Prefs.SENS_RIMOSSE);
        String f = filtroApp.trim().toLowerCase(Locale.ROOT);
        List<AppInfo> sens = new ArrayList<>();
        List<AppInfo> escl = new ArrayList<>();
        List<AppInfo> altre = new ArrayList<>();
        int sensEscluse = 0;
        for (AppInfo a : appCache) {
            boolean sensibile = sensibile(a, mie, rimosse);
            if (sensibile && esc.contains(a.pkg)) sensEscluse++;
            if (!f.isEmpty() && !a.nome.toLowerCase(Locale.ROOT).contains(f) && !a.pkg.contains(f)) continue;
            if (sensibile) sens.add(a);
            else if (esc.contains(a.pkg)) escl.add(a);
            else altre.add(a);
        }

        LinearLayout card = sezione(listaApp, "Le tue app sensibili · " + sensEscluse + " escluse",
                "Le app sensibili vengono escluse appena le aggiungi. Tocca il nome di un'app per aggiungerla o toglierla dalle sensibili.");
        for (AppInfo a : sens) aggiungi(card, rigaApp(a, esc, true));
        if (f.isEmpty()) {
            aggiungi(card, riga(null, "Aggiungi un'app sensibile…", Stile.accento, null, null,
                    v -> scegliSensibile()));
        }
        if (!escl.isEmpty()) {
            card = sezione(listaApp, "Altre app escluse (" + escl.size() + ")", null);
            for (AppInfo a : escl) aggiungi(card, rigaApp(a, esc, false));
        }
        if (!altre.isEmpty()) {
            card = sezione(listaApp, "App filtrate (" + altre.size() + ")",
                    "Accendi l'interruttore per escludere un'app che non funziona bene col filtro.");
            for (AppInfo a : altre) aggiungi(card, rigaApp(a, esc, false));
        }
        if (!f.isEmpty() && sens.isEmpty() && escl.isEmpty() && altre.isEmpty()) {
            TextView t = t("Nessuna app trovata", 15, Stile.testo2, false);
            t.setPadding(dp(16), dp(24), 0, 0);
            listaApp.addView(t);
        }
    }

    private boolean sensibile(AppInfo a, Set<String> mie, Set<String> rimosse) {
        if (mie.contains(a.pkg)) return true;
        return chiaveSensibile(a) != null && !rimosse.contains(a.pkg);
    }

    private int verdeTesto() {
        return Stile.scuro ? 0xFF30D158 : 0xFF1E7A36;
    }

    private TextView etichettaStato(boolean esclusa) {
        return esclusa ? etichetta("Esclusa", verdeTesto())
                : etichetta("Filtrata", Stile.scuro ? 0xFFFF9F0A : 0xFFB33A00);
    }

    private String testoStato(boolean sensibile, boolean esclusa) {
        String st = esclusa ? "Esclusa" : "Filtrata";
        return sensibile ? "App sensibile · " + st : st;
    }

    private View rigaApp(AppInfo a, Set<String> esc, boolean sensibile) {
        boolean esclusa = esc.contains(a.pkg);
        Stile.Interruttore sw = new Stile.Interruttore(this);
        sw.imposta(esclusa, false, false);
        LinearLayout r = riga(iconaApp(a), a.nome, testoStato(sensibile, esclusa), sw, v -> menuApp(a, sensibile));
        // la scritta sotto il nome (Esclusa / Filtrata) si aggiorna con l'interruttore
        final TextView stato = (TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(1);
        stato.setTextColor(esclusa ? verdeTesto() : Stile.testo2);
        sw.setAscoltatore(v -> {
            Set<String> s = Prefs.escluse(this);
            if (v) s.add(a.pkg);
            else s.remove(a.pkg);
            Prefs.salvaSet(this, Prefs.ESCLUSE, s);
            programmaRiavvio();
            stato.setText(testoStato(sensibile, v));
            stato.setTextColor(v ? verdeTesto() : Stile.testo2);
            if (sensibile && !v) toast(a.nome + " ora passa dal filtro");
        });
        return r;
    }

    private void menuApp(AppInfo a, boolean sensibile) {
        String voce = sensibile ? "Togli dalle app sensibili" : "Aggiungi alle app sensibili";
        dialogo().setTitle(a.nome)
                .setItems(new String[]{voce}, (d, w) -> {
                    if (sensibile) togliSensibile(a);
                    else aggiungiSensibile(a);
                })
                .setNegativeButton("Annulla", null).show();
    }

    private void aggiungiSensibile(AppInfo a) {
        Set<String> mie = Prefs.set(this, Prefs.SENS_MIE);
        Set<String> rimosse = Prefs.set(this, Prefs.SENS_RIMOSSE);
        Set<String> esc = Prefs.escluse(this);
        mie.add(a.pkg);
        rimosse.remove(a.pkg);
        esc.add(a.pkg);
        Prefs.salvaSet(this, Prefs.SENS_MIE, mie);
        Prefs.salvaSet(this, Prefs.SENS_RIMOSSE, rimosse);
        Prefs.salvaSet(this, Prefs.ESCLUSE, esc);
        programmaRiavvio();
        toast(a.nome + " aggiunta alle app sensibili ed esclusa");
        riempiListaApp();
    }

    private void togliSensibile(AppInfo a) {
        Set<String> mie = Prefs.set(this, Prefs.SENS_MIE);
        Set<String> rimosse = Prefs.set(this, Prefs.SENS_RIMOSSE);
        mie.remove(a.pkg);
        if (chiaveSensibile(a) != null) rimosse.add(a.pkg);
        Prefs.salvaSet(this, Prefs.SENS_MIE, mie);
        Prefs.salvaSet(this, Prefs.SENS_RIMOSSE, rimosse);
        toast(a.nome + " non è più tra le sensibili (resta esclusa finché non la spegni)");
        riempiListaApp();
    }

    private void scegliSensibile() {
        if (appCache == null) return;
        Set<String> mie = Prefs.set(this, Prefs.SENS_MIE);
        Set<String> rimosse = Prefs.set(this, Prefs.SENS_RIMOSSE);
        final List<AppInfo> candidate = new ArrayList<>();
        for (AppInfo a : appCache) if (!sensibile(a, mie, rimosse)) candidate.add(a);
        String[] nomi = new String[candidate.size()];
        for (int i = 0; i < nomi.length; i++) nomi[i] = candidate.get(i).nome;
        dialogo().setTitle("Aggiungi un'app sensibile")
                .setItems(nomi, (d, w) -> aggiungiSensibile(candidate.get(w)))
                .setNegativeButton("Annulla", null).show();
    }

    // ================================================================== pagina: Registro

    private View paginaRegistro() {
        TextView aggiorna = bottoneTesto("Aggiorna", Stile.accento, v -> mostraMantieni());
        LinearLayout c = pagina("Registro", null, aggiorna);
        boolean reg = Prefs.registro(this);

        LinearLayout card = sezione(c, null,
                "L'elenco dei siti bloccati resta solo sul telefono, in un file privato di Scudo: non esce mai e non va nel backup.");
        Stile.Interruttore sw = new Stile.Interruttore(this);
        sw.imposta(reg, false, false);
        sw.setAscoltatore(v -> {
            Prefs.metti(this, Prefs.REGISTRO, v);
            mostraMantieni();
        });
        aggiungi(card, riga(null, "Registra i domini bloccati", null, sw, null));
        aggiungi(card, riga(null, "Svuota registro", Stile.ROSSO, null, null, v -> {
            dialogo().setTitle("Svuotare il registro?")
                    .setMessage("L'elenco dei siti bloccati verrà cancellato. I due contatori restano.")
                    .setPositiveButton("Svuota", (d, w) -> {
                        Statistiche.svuota(this);
                        mostraMantieni();
                    })
                    .setNegativeButton("Annulla", null).show();
        }));

        List<Statistiche.Voce> voci = Statistiche.recenti(this);
        card = sezione(c, "Bloccati di recente (" + voci.size() + ")",
                "Se un'app non funziona bene, cerca qui il suo dominio e tocca Consenti.");
        if (voci.isEmpty()) {
            aggiungi(card, riga(null, ScudoVpnService.attivo ? "Ancora nessun blocco" : "La protezione è spenta",
                    Stile.testo2, null, null, null));
        }
        SimpleDateFormat ora = new SimpleDateFormat("d MMM, HH:mm:ss", Locale.ITALY);
        int max = Math.min(voci.size(), 150);
        for (int i = 0; i < max; i++) {
            Statistiche.Voce v = voci.get(i);
            String info = ora.format(new Date(v.ultima)) + " · " + numero(v.totale) + (v.totale == 1 ? " volta" : " volte");
            final String dom = v.dominio;
            aggiungi(card, riga(null, dom, info, bottoneTesto("Consenti", Stile.accento, x -> consenti(dom)), null));
        }

        Set<String> cons = Prefs.set(this, Prefs.CONSENTITI);
        card = sezione(c, "Domini consentiti", "Questi domini non vengono mai bloccati, anche se sono in lista.");
        aggiungi(card, riga(null, "Consenti un dominio…", Stile.accento, null, null,
                v -> chiediDominio("Consenti un dominio", "Non verrà mai bloccato.", this::consenti)));
        List<String> ordinati = new ArrayList<>(cons);
        ordinati.sort(String::compareTo);
        for (String d : ordinati) {
            aggiungi(card, riga(null, d, null, bottoneTesto("Rimuovi", Stile.ROSSO, x -> {
                Set<String> s = Prefs.set(this, Prefs.CONSENTITI);
                s.remove(d);
                Prefs.salvaSet(this, Prefs.CONSENTITI, s);
                Filtro.impostaConsentiti(s);
                mostraMantieni();
            }), null));
        }
        return scroll;
    }

    private void consenti(String dominio) {
        Set<String> s = Prefs.set(this, Prefs.CONSENTITI);
        s.add(dominio);
        Prefs.salvaSet(this, Prefs.CONSENTITI, s);
        Filtro.impostaConsentiti(s);
        toast(dominio + " consentito");
        mostraMantieni();
    }

    // ================================================================== sottopagina: cosa è stato bloccato

    private View paginaBloccati(boolean diOggi) {
        LinearLayout c = pagina(diOggi ? "Bloccate oggi" : "Bloccate in totale", "Scudo", null);
        View seg = segmentato(new String[]{"Oggi", "In totale"}, diOggi ? 0 : 1, i -> {
            sotto = i == 0 ? "bloccati_oggi" : "bloccati_totale";
            mostra();
        });
        seg.setPadding(0, dp(10), 0, dp(4));
        c.addView(seg);

        long n = diOggi ? Statistiche.oggi(this) : Statistiche.totale(this);
        List<Statistiche.Voce> voci = diOggi ? Statistiche.diOggi(this) : Statistiche.perTotale(this);
        TextView riass = t(numero(n) + (n == 1 ? " pubblicità bloccata" : " pubblicità bloccate")
                + (diOggi ? " oggi" : " da quando usi Scudo")
                + ", da " + voci.size() + (voci.size() == 1 ? " sito diverso." : " siti diversi."), 15, Stile.testo2, false);
        riass.setPadding(dp(4), dp(8), dp(4), 0);
        c.addView(riass);

        if (!Prefs.registro(this)) {
            LinearLayout avviso = sezione(c, null, null);
            avviso.setBackground(arrotondato(Stile.conAlfa(Stile.ARANCIO, 0x30), 12));
            aggiungi(avviso, riga(null, "Il registro è spento", Stile.ARANCIO,
                    "I blocchi vengono contati ma i siti non vengono annotati. Tocca qui per accenderlo.",
                    null, v -> {
                        Prefs.metti(this, Prefs.REGISTRO, true);
                        mostraMantieni();
                    }));
        }

        LinearLayout card = sezione(c, "Siti pubblicitari bloccati",
                "Il numero indica quante volte è stato bloccato. Tocca Consenti se un'app smette di funzionare bene.");
        if (voci.isEmpty()) {
            aggiungi(card, riga(null, diOggi ? "Oggi ancora nessun blocco" : "Ancora nessun blocco",
                    Stile.testo2, null, null, null));
        }
        SimpleDateFormat ora = new SimpleDateFormat("HH:mm", Locale.ITALY);
        SimpleDateFormat giorno = new SimpleDateFormat("d MMM, HH:mm", Locale.ITALY);
        int max = Math.min(voci.size(), 300);
        for (int i = 0; i < max; i++) {
            Statistiche.Voce v = voci.get(i);
            long volte = diOggi ? v.oggi : v.totale;
            String info = numero(volte) + (volte == 1 ? " volta" : " volte") + " · ultima "
                    + (diOggi ? "alle " + ora.format(new Date(v.ultima)) : "il " + giorno.format(new Date(v.ultima)));
            final String dom = v.dominio;
            aggiungi(card, riga(null, dom, info, bottoneTesto("Consenti", Stile.accento, x -> consenti(dom)), null));
        }
        if (voci.size() > max) {
            TextView altri = t("…e altri " + numero(voci.size() - max) + " siti", 13, Stile.testo2, false);
            altri.setPadding(dp(16), dp(7), dp(16), 0);
            c.addView(altri);
        }
        return scroll;
    }

    // ================================================================== pagina: Impostazioni

    private View paginaImpostazioni() {
        LinearLayout c = pagina("Impostazioni", null, null);

        LinearLayout card = sezione(c, "Protezione",
                "DNS cifrato: operatore telefonico e reti Wi-Fi non vedono quali siti visiti.");
        aggiungi(card, riga(tessera(R.drawable.ic_globo, 0xFF007AFF), "Server DNS", null,
                freccia(ServerDns.attuale(this).nome), v -> {
                    sotto = "dns";
                    mostra();
                }));
        Stile.Interruttore cif = new Stile.Interruttore(this);
        cif.imposta(Prefs.cifrato(this), false, false);
        cif.setAscoltatore(v -> {
            Prefs.metti(this, Prefs.CIFRATO, v);
            programmaRiavvio();
        });
        aggiungi(card, riga(tessera(R.drawable.ic_lucchetto, 0xFF34C759), "DNS cifrato", null, cif, null));
        aggiungi(card, riga(tessera(R.drawable.ic_registro, 0xFFFF9500), "Liste di blocco", null,
                freccia(numero(Filtro.totale())), v -> {
                    sotto = "liste";
                    mostra();
                }));

        card = sezione(c, "Aspetto", null);
        aggiungi(card, riga(tessera(R.drawable.ic_tavolozza, 0xFFAF52DE), "Colori, carattere e testo", null,
                freccia(null), v -> {
                    sotto = "aspetto";
                    mostra();
                }));

        card = sezione(c, "Privacy e sicurezza",
                "Per far partire Scudo da solo all'accensione: tocca «Avvio automatico», poi l'ingranaggio "
                        + "accanto a Scudo e attiva «VPN sempre attiva».");
        aggiungi(card, riga(tessera(R.drawable.ic_scudo, 0xFF5856D6), "Come sono protetti i tuoi dati", null,
                freccia(null), v -> {
                    sotto = "privacy";
                    mostra();
                }));
        aggiungi(card, riga(tessera(R.drawable.ic_impostazioni, 0xFF8E8E93), "Avvio automatico", null,
                freccia(null), v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
                    } catch (Exception e) {
                        toast("Apri Impostazioni → Rete e Internet → VPN");
                    }
                }));

        TextView ver = t("Scudo 1.1 · nessun dato raccolto, nessun account", 13, Stile.testo2, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(28), 0, 0);
        c.addView(ver, new LinearLayout.LayoutParams(-1, -2));
        return scroll;
    }

    // ================================================================== sottopagina: DNS

    private View paginaDns() {
        LinearLayout c = pagina("Server DNS", "Impostazioni", null);
        TextView intro = t("Il server DNS risponde solo per i siti che Scudo non blocca. "
                + "Scegli quello di cui ti fidi di più.", 15, Stile.testo2, false);
        c.addView(intro);
        String sel = ServerDns.attuale(this).id;
        LinearLayout card = sezione(c, null,
                "Consigliato: Quad9. I server «Antipubblicità» bloccano già da soli: insieme a Scudo fanno doppio filtro.");
        for (ServerDns s : ServerDns.TUTTI) {
            aggiungi(card, riga(null, s.nome, s.descrizione, spunta(s.id.equals(sel)), v -> {
                Prefs.metti(this, Prefs.DNS, s.id);
                programmaRiavvio();
                mostraMantieni();
            }));
        }
        ServerDns attuale = ServerDns.attuale(this);
        String descr = ServerDns.PERSONALIZZATO.equals(attuale.id) ? attuale.descrizione
                : "Inserisci tu l'indirizzo di un altro server";
        aggiungi(card, riga(null, "Personalizzato", descr, spunta(ServerDns.PERSONALIZZATO.equals(sel)),
                v -> dialogoPersonalizzato()));
        return scroll;
    }

    private void dialogoPersonalizzato() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        EditText url = campo("https://…/dns-query", Prefs.dnsUrl(this));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText ip = campo("Indirizzo IP, es. 9.9.9.9", Prefs.dnsIp(this));
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(t("Indirizzo cifrato (DoH)", 13, Stile.testo2, false));
        box.addView(url);
        TextView l2 = t("Indirizzo normale", 13, Stile.testo2, false);
        l2.setPadding(0, dp(12), 0, 0);
        box.addView(l2);
        box.addView(ip);
        dialogo().setTitle("Server personalizzato").setView(box)
                .setPositiveButton("Salva", (d, w) -> {
                    String u = url.getText().toString().trim();
                    String i = ip.getText().toString().trim();
                    boolean urlOk = u.isEmpty() || u.startsWith("https://");
                    boolean ipOk = i.isEmpty() || i.matches("\\d{1,3}(\\.\\d{1,3}){3}");
                    if (!urlOk || !ipOk || (u.isEmpty() && i.isEmpty())) {
                        toast("Indirizzo non valido");
                        return;
                    }
                    Prefs.metti(this, Prefs.DNS_URL, u);
                    Prefs.metti(this, Prefs.DNS_IP, i);
                    Prefs.metti(this, Prefs.DNS, ServerDns.PERSONALIZZATO);
                    programmaRiavvio();
                    mostraMantieni();
                })
                .setNegativeButton("Annulla", null).show();
    }

    // ================================================================== sottopagina: Liste

    private View paginaListe() {
        LinearLayout c = pagina("Liste di blocco", "Impostazioni", null);

        LinearLayout card = sezione(c, "Sempre attiva", null);
        aggiungi(card, riga(null, "Lista di base di Scudo",
                Filtro.BASE.length + " reti pubblicitarie e di tracciamento, comprese quelle dei telefoni Xiaomi, Samsung, Oppo.",
                etichetta("Attiva", Stile.VERDE), null));

        Set<String> attive = Prefs.set(this, Prefs.LISTE);
        card = sezione(c, "Liste aggiuntive",
                "Si scaricano solo quando lo chiedi tu, da indirizzi fissi e con connessione cifrata. "
                        + "Non viene inviato nessun tuo dato. Tocca il nome per scaricare o aggiornare.");
        SimpleDateFormat giorno = new SimpleDateFormat("d MMM yyyy", Locale.ITALY);
        for (String[] l : Filtro.LISTE) {
            final String id = l[0];
            final int n = Prefs.sp(this).getInt("conta_" + id, 0);
            long quando = Prefs.sp(this).getLong("data_" + id, 0);
            String stato = n > 0 ? numero(n) + " domini · " + giorno.format(new Date(quando)) : "Non ancora scaricata";
            Stile.Interruttore sw = new Stile.Interruttore(this);
            sw.imposta(attive.contains(id) && n > 0, false, false);
            sw.setAscoltatore(v -> {
                if (v && n == 0) {
                    scarica(id, true);
                    return;
                }
                Set<String> s = Prefs.set(this, Prefs.LISTE);
                if (v) s.add(id);
                else s.remove(id);
                Prefs.salvaSet(this, Prefs.LISTE, s);
                ricaricaFiltro();
            });
            aggiungi(card, riga(null, l[1], l[2] + "\n" + stato, sw, v -> scarica(id, false)));
        }

        Set<String> miei = Prefs.set(this, Prefs.BLOCCATI_MIEI);
        card = sezione(c, "Bloccati da te", "Domini che vuoi bloccare tu, oltre alle liste.");
        aggiungi(card, riga(null, "Blocca un dominio…", Stile.accento, null, null,
                v -> chiediDominio("Blocca un dominio", "Verrà bloccato insieme ai suoi sottodomini.", d -> {
                    Set<String> s = Prefs.set(this, Prefs.BLOCCATI_MIEI);
                    s.add(d);
                    Prefs.salvaSet(this, Prefs.BLOCCATI_MIEI, s);
                    ricaricaFiltro();
                })));
        List<String> ordinati = new ArrayList<>(miei);
        ordinati.sort(String::compareTo);
        for (String d : ordinati) {
            aggiungi(card, riga(null, d, null, bottoneTesto("Rimuovi", Stile.ROSSO, x -> {
                Set<String> s = Prefs.set(this, Prefs.BLOCCATI_MIEI);
                s.remove(d);
                Prefs.salvaSet(this, Prefs.BLOCCATI_MIEI, s);
                ricaricaFiltro();
            }), null));
        }
        return scroll;
    }

    private void ricaricaFiltro() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            Filtro.carica(app);
            h.post(this::mostraMantieni);
        }).start();
    }

    private void scarica(String id, boolean attiva) {
        toast("Scaricamento in corso…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                int n = Filtro.scarica(app, id);
                Prefs.sp(app).edit()
                        .putInt("conta_" + id, n)
                        .putLong("data_" + id, System.currentTimeMillis())
                        .apply();
                if (attiva) {
                    Set<String> s = Prefs.set(app, Prefs.LISTE);
                    s.add(id);
                    Prefs.salvaSet(app, Prefs.LISTE, s);
                }
                Filtro.carica(app);
                h.post(() -> {
                    toast("Scaricati " + numero(n) + " domini");
                    mostraMantieni();
                });
            } catch (Exception e) {
                h.post(() -> {
                    toast("Download non riuscito: controlla la connessione");
                    mostraMantieni();
                });
            }
        }).start();
    }

    // ================================================================== sottopagina: Aspetto

    private View paginaAspetto() {
        LinearLayout c = pagina("Aspetto", "Impostazioni", null);

        // anteprima dal vivo
        LinearLayout card = sezione(c, "Anteprima", null);
        LinearLayout ant = new LinearLayout(this);
        ant.setOrientation(LinearLayout.VERTICAL);
        ant.setPadding(dp(16), dp(14), dp(16), dp(16));
        ant.addView(t("Titolo di esempio", 22, Stile.testo, true));
        TextView corpo = t("Così appaiono i testi dell'app con il carattere e la dimensione scelti.",
                15, Stile.testo2, false);
        corpo.setPadding(0, dp(4), 0, dp(12));
        ant.addView(corpo);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView bott = t("Pulsante", 15, Color.WHITE, true);
        bott.setBackground(arrotondato(Stile.accento, 20));
        bott.setPadding(dp(18), dp(8), dp(18), dp(8));
        r.addView(bott);
        r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        Stile.Interruttore demo = new Stile.Interruttore(this);
        demo.imposta(true, false, false);
        r.addView(demo);
        ant.addView(r);
        card.addView(ant);

        // tema
        card = sezione(c, "Tema", "«Automatico» segue il tema chiaro o scuro del telefono.");
        card.addView(segmentato(Stile.TEMI, Prefs.tema(this), i -> {
            Prefs.metti(this, Prefs.TEMA, i);
            applicaAspetto();
        }));

        // colori preimpostati
        card = sezione(c, "Colore principale", null);
        LinearLayout griglia = new LinearLayout(this);
        griglia.setOrientation(LinearLayout.VERTICAL);
        griglia.setPadding(dp(10), dp(12), dp(10), dp(12));
        int attuale = Prefs.accento(this);
        int mio = Prefs.coloreMio(this);
        boolean preimpostato = false;
        for (int col : Stile.ACCENTI) if (col == attuale) preimpostato = true;
        LinearLayout rigaColori = null;
        int tot = Stile.ACCENTI.length + 1;
        for (int i = 0; i < tot; i++) {
            if (i % 7 == 0) {
                rigaColori = new LinearLayout(this);
                griglia.addView(rigaColori);
            }
            final boolean personal = i == Stile.ACCENTI.length;
            final int colore = personal ? mio : Stile.ACCENTI[i];
            boolean sel = personal ? !preimpostato : colore == attuale;
            FrameLayout cella = new FrameLayout(this);
            View pallino = new View(this);
            GradientDrawable gd;
            if (personal) {
                gd = new GradientDrawable();
                gd.setGradientType(GradientDrawable.SWEEP_GRADIENT);
                gd.setColors(new int[]{0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, 0xFF5856D6, 0xFFFF2D55, 0xFFFF3B30});
            } else {
                gd = new GradientDrawable();
                gd.setColor(colore);
            }
            gd.setShape(GradientDrawable.OVAL);
            if (sel) gd.setStroke(dp(3), Stile.scheda);
            pallino.setBackground(gd);
            if (sel) {
                GradientDrawable anello = new GradientDrawable();
                anello.setShape(GradientDrawable.OVAL);
                anello.setStroke(dp(2), personal ? mio : colore);
                cella.setBackground(anello);
            }
            cella.addView(pallino, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            cella.setOnClickListener(v -> {
                Prefs.metti(this, Prefs.ACCENTO, colore);
                applicaAspetto();
            });
            LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lpc.setMargins(dp(2), dp(2), dp(2), dp(2));
            rigaColori.addView(cella, lpc);
        }
        // riempie l'ultima riga per tenere i pallini allineati
        while (rigaColori != null && rigaColori.getChildCount() < 7) {
            rigaColori.addView(new View(this), new LinearLayout.LayoutParams(0, dp(40), 1f));
        }
        card.addView(griglia);
        String nomeColore = "Personalizzato " + Stile.esadecimale(attuale);
        for (int i = 0; i < Stile.ACCENTI.length; i++) {
            if (Stile.ACCENTI[i] == attuale) nomeColore = Stile.NOMI_ACCENTI[i];
        }
        TextView nc = t(nomeColore, 13, Stile.testo2, false);
        nc.setPadding(dp(16), 0, dp(16), dp(12));
        card.addView(nc);

        // colore personalizzato
        card = sezione(c, "Colore personalizzato", "Muovi i cursori o scrivi il codice del colore (es. #FF8800), poi tocca «Usa».");
        aggiungiEditorColore(card, mio);

        // sfondo
        card = sezione(c, "Sfondo", "«Colorato» tinge lo sfondo con il colore principale; «Caldo» usa toni carta.");
        card.addView(segmentato(Stile.SFONDI, Prefs.sfondo(this), i -> {
            Prefs.metti(this, Prefs.SFONDO, i);
            applicaAspetto();
        }));

        // carattere
        card = sezione(c, "Carattere", null);
        int fontSel = Prefs.font(this);
        for (int i = 0; i < Stile.FONT_NOMI.length; i++) {
            final int indice = i;
            LinearLayout rr = riga(null, Stile.FONT_NOMI[i], null, spunta(i == fontSel), v -> {
                Prefs.metti(this, Prefs.FONT, indice);
                applicaAspetto();
            });
            TextView titolo = (TextView) ((LinearLayout) rr.getChildAt(0)).getChildAt(0);
            titolo.setTypeface(Typeface.create(Stile.FONT_FAMIGLIE[i], Typeface.NORMAL));
            aggiungi(card, rr);
        }

        // dimensione
        card = sezione(c, "Dimensione del testo", null);
        LinearLayout dim = new LinearLayout(this);
        dim.setGravity(Gravity.CENTER_VERTICAL);
        dim.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView piccola = new TextView(this);
        piccola.setText("A");
        piccola.setTextColor(Stile.testo);
        piccola.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dim.addView(piccola);
        SeekBar sb = new SeekBar(this);
        sb.setMax(14);
        sb.setProgress(Math.round((Stile.scala - 0.8f) / 0.05f));
        sb.setProgressTintList(ColorStateList.valueOf(Stile.accento));
        sb.setThumbTintList(ColorStateList.valueOf(Stile.accento));
        dim.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView grande = new TextView(this);
        grande.setText("A");
        grande.setTextColor(Stile.testo);
        grande.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        dim.addView(grande);
        card.addView(dim);
        TextView perc = t(Math.round(Stile.scala * 100) + "%", 13, Stile.testo2, false);
        perc.setGravity(Gravity.CENTER);
        perc.setPadding(0, 0, 0, dp(12));
        card.addView(perc);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean daUtente) {
                perc.setText(Math.round((0.8f + p * 0.05f) * 100) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Prefs.metti(MainActivity.this, Prefs.SCALA, 0.8f + s.getProgress() * 0.05f);
                applicaAspetto();
            }
        });

        // ripristino
        card = sezione(c, null, null);
        aggiungi(card, riga(null, "Ripristina aspetto predefinito", Stile.ROSSO, null, null, v -> {
            Prefs.ripristinaAspetto(this);
            applicaAspetto();
        }));
        return scroll;
    }

    private void aggiungiEditorColore(LinearLayout card, int iniziale) {
        final int[] rgb = {(iniziale >> 16) & 0xFF, (iniziale >> 8) & 0xFF, iniziale & 0xFF};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout alto = new LinearLayout(this);
        alto.setGravity(Gravity.CENTER_VERTICAL);
        View campione = new View(this);
        GradientDrawable gc = new GradientDrawable();
        gc.setShape(GradientDrawable.OVAL);
        gc.setColor(iniziale);
        campione.setBackground(gc);
        alto.addView(campione, new LinearLayout.LayoutParams(dp(40), dp(40)));
        EditText hex = campo("#RRGGBB", Stile.esadecimale(iniziale));
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams lph = new LinearLayout.LayoutParams(0, -2, 1f);
        lph.leftMargin = dp(12);
        lph.rightMargin = dp(12);
        alto.addView(hex, lph);
        TextView usa = t("Usa", 15, Color.WHITE, true);
        usa.setBackground(arrotondato(Stile.accento, 18));
        usa.setPadding(dp(16), dp(8), dp(16), dp(8));
        alto.addView(usa);
        box.addView(alto);

        String[] nomi = {"Rosso", "Verde", "Blu"};
        int[] tinte = {0xFFFF3B30, 0xFF34C759, 0xFF007AFF};
        final SeekBar[] barre = new SeekBar[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            LinearLayout rr = new LinearLayout(this);
            rr.setGravity(Gravity.CENTER_VERTICAL);
            rr.setPadding(0, dp(10), 0, 0);
            TextView nome = t(nomi[i], 14, Stile.testo2, false);
            rr.addView(nome, new LinearLayout.LayoutParams(dp(56), -2));
            SeekBar s = new SeekBar(this);
            s.setMax(255);
            s.setProgress(rgb[i]);
            s.setProgressTintList(ColorStateList.valueOf(tinte[i]));
            s.setThumbTintList(ColorStateList.valueOf(tinte[i]));
            barre[i] = s;
            rr.addView(s, new LinearLayout.LayoutParams(0, -2, 1f));
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean daUtente) {
                    rgb[k] = p;
                    int col = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                    gc.setColor(col);
                    if (daUtente) hex.setText(Stile.esadecimale(col));
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {}

                @Override
                public void onStopTrackingTouch(SeekBar sb) {}
            });
            box.addView(rr);
        }

        Runnable leggiHex = () -> {
            String s = hex.getText().toString().trim().replace("#", "");
            if (s.matches("[0-9a-fA-F]{6}")) {
                int col = 0xFF000000 | Integer.parseInt(s, 16);
                rgb[0] = (col >> 16) & 0xFF;
                rgb[1] = (col >> 8) & 0xFF;
                rgb[2] = col & 0xFF;
                for (int i = 0; i < 3; i++) barre[i].setProgress(rgb[i]);
                gc.setColor(col);
            }
        };
        hex.setOnEditorActionListener((v, azione, ev) -> {
            leggiHex.run();
            return false;
        });
        usa.setOnClickListener(v -> {
            leggiHex.run();
            int col = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            Prefs.metti(this, Prefs.COLORE_MIO, col);
            Prefs.metti(this, Prefs.ACCENTO, col);
            applicaAspetto();
        });
        card.addView(box);
    }

    // ================================================================== sottopagina: Privacy

    private View paginaPrivacy() {
        LinearLayout c = pagina("I tuoi dati", "Impostazioni", null);
        TextView intro = t("Scudo è fatto per non far uscire nulla di tuo dal telefono. Ecco come.",
                15, Stile.testo2, false);
        c.addView(intro);

        LinearLayout card = sezione(c, "Cosa fa Scudo", null);
        aggiungi(card, riga(tessera(R.drawable.ic_scudo, 0xFF007AFF), "Tutto resta sul telefono",
                "Il filtro lavora dentro l'app. Non esistono server di Scudo, account o iscrizioni.", null, null));
        aggiungi(card, riga(tessera(R.drawable.ic_globo, 0xFF5856D6), "Vede solo i nomi dei siti",
                "Nel filtro entrano solo le domande «dove si trova questo sito?». Password, messaggi, foto e pagine non passano mai da Scudo.",
                null, null));
        aggiungi(card, riga(tessera(R.drawable.ic_app, 0xFF34C759), "App sensibili escluse",
                "BPER, PosteID, WhatsApp e Telegram sono fuori dal filtro: Scudo non vede neanche i nomi dei loro siti.",
                null, null));
        aggiungi(card, riga(tessera(R.drawable.ic_lucchetto, 0xFFFF9500), "DNS cifrato",
                "Le domande che Scudo inoltra al server DNS viaggiano cifrate: operatore e Wi-Fi non le leggono.",
                null, null));

        card = sezione(c, "Protezioni tecniche", null);
        aggiungi(card, riga(tessera(R.drawable.ic_lucchetto, 0xFFFF3B30), "Solo certificati ufficiali",
                "L'app accetta solo connessioni cifrate verificate dai certificati del sistema: nessuno può intercettarla con un certificato falso.",
                null, null));
        aggiungi(card, riga(tessera(R.drawable.ic_impostazioni, 0xFF8E8E93), "Nessun backup nel cloud",
                "Impostazioni e contatori non vengono copiati nel backup Google né trasferiti ad altri telefoni.",
                null, null));
        aggiungi(card, riga(tessera(R.drawable.ic_registro, 0xFFAF52DE), "Registro solo sul telefono",
                "I siti pubblicitari bloccati restano in un file privato di Scudo, mai nel backup. Puoi svuotarlo o disattivarlo.",
                null, null));
        aggiungi(card, riga(tessera(R.drawable.ic_tavolozza, 0xFF32ADE6), "Nessuna libreria esterna",
                "Niente pubblicità, statistiche o codice di terzi. Il codice è tutto nel tuo GitHub.",
                null, null));

        card = sezione(c, "Cosa esce dal telefono", null);
        aggiungi(card, riga(null, "Nomi dei siti → server DNS scelto",
                "Solo per le app non escluse e solo per i siti non bloccati. È quello che fa già oggi il tuo telefono, ma cifrato.",
                null, null));
        aggiungi(card, riga(null, "Download delle liste",
                "Solo quando lo chiedi tu, da indirizzi fissi. Non viene inviato nessun tuo dato.",
                null, null));
        return scroll;
    }
}
