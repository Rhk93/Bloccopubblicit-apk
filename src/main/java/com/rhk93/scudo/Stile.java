package com.rhk93.scudo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Colori, carattere e dimensione del testo scelti dall'utente (stile Apple). */
public final class Stile {

    public static boolean scuro;
    public static int accento;
    public static int sfondo;
    public static int scheda;
    public static int testo;
    public static int testo2;
    public static int separatore;
    public static int barra;
    public static int campo;
    public static int segmento;
    public static int segmentoSel;
    public static final int ROSSO = 0xFFFF3B30;
    public static final int VERDE = 0xFF34C759;
    public static final int ARANCIO = 0xFFFF9500;
    public static Typeface font = Typeface.DEFAULT;
    public static Typeface fontGrassetto = Typeface.DEFAULT_BOLD;
    public static float scala = 1f;

    /** Colori preimpostati (i colori di sistema di iPhone). */
    public static final int[] ACCENTI = {
            0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFFFF3B30, 0xFFFF9500,
            0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF32ADE6, 0xFFA2845E, 0xFF8E8E93,
    };
    public static final String[] NOMI_ACCENTI = {
            "Blu", "Indaco", "Viola", "Rosa", "Rosso", "Arancione",
            "Giallo", "Verde", "Menta", "Azzurro", "Marrone", "Grafite",
    };

    public static final String[] FONT_NOMI = {
            "Sistema", "Leggero", "Medio", "Condensato", "Serif", "Monospazio", "Informale", "Corsivo",
    };
    public static final String[] FONT_FAMIGLIE = {
            "sans-serif", "sans-serif-light", "sans-serif-medium", "sans-serif-condensed",
            "serif", "monospace", "casual", "cursive",
    };

    public static final String[] TEMI = {"Automatico", "Chiaro", "Scuro"};
    public static final String[] SFONDI = {"Standard", "Colorato", "Caldo"};

    private Stile() {}

    public static void aggiorna(Context c) {
        int tema = Prefs.tema(c);
        boolean notte = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        scuro = tema == 2 || (tema == 0 && notte);
        accento = Prefs.accento(c);

        if (scuro) {
            sfondo = 0xFF000000;
            scheda = 0xFF1C1C1E;
            testo = 0xFFFFFFFF;
            testo2 = 0xFF98989F;
            separatore = 0xFF38383A;
            barra = 0xFF121212;
            campo = 0xFF1C1C1E;
            segmento = 0xFF2C2C2E;
            segmentoSel = 0xFF636366;
        } else {
            sfondo = 0xFFF2F2F7;
            scheda = 0xFFFFFFFF;
            testo = 0xFF000000;
            testo2 = 0xFF6C6C70;
            separatore = 0xFFD1D1D6;
            barra = 0xFFF9F9F9;
            campo = 0xFFE3E3E8;
            segmento = 0xFFE3E3E8;
            segmentoSel = 0xFFFFFFFF;
        }

        int sf = Prefs.sfondo(c);
        if (sf == 1) {
            sfondo = mescola(sfondo, accento, scuro ? 0.16f : 0.08f);
            scheda = mescola(scheda, accento, scuro ? 0.07f : 0.02f);
            barra = mescola(barra, accento, scuro ? 0.10f : 0.05f);
        } else if (sf == 2) {
            if (scuro) {
                sfondo = 0xFF1A1714;
                scheda = 0xFF2A2622;
                barra = 0xFF1F1B18;
                separatore = 0xFF3D3833;
                campo = 0xFF2A2622;
            } else {
                sfondo = 0xFFF4EFE6;
                scheda = 0xFFFFFCF6;
                barra = 0xFFF8F3EA;
                separatore = 0xFFDCD3C4;
                campo = 0xFFE9E2D6;
            }
        }

        int f = Prefs.font(c);
        if (f < 0 || f >= FONT_FAMIGLIE.length) f = 0;
        font = Typeface.create(FONT_FAMIGLIE[f], Typeface.NORMAL);
        fontGrassetto = Typeface.create(FONT_FAMIGLIE[f], Typeface.BOLD);
        scala = Prefs.scala(c);
        if (scala < 0.8f || scala > 1.5f) scala = 1f;
    }

    public static int mescola(int a, int b, float t) {
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * t);
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    public static int conAlfa(int colore, int alfa) {
        return (colore & 0x00FFFFFF) | (alfa << 24);
    }

    public static String esadecimale(int colore) {
        return String.format("#%06X", colore & 0xFFFFFF);
    }

    /** Interruttore in stile iPhone, disegnato a mano. */
    public static class Interruttore extends View {

        public interface Ascoltatore {
            void cambiato(boolean acceso);
        }

        private boolean acceso;
        private float pos;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int coloreOn;
        private Ascoltatore ascoltatore;
        private ValueAnimator anim;

        public Interruttore(Context c) {
            super(c);
            coloreOn = Stile.accento;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setClickable(true);
            setOnClickListener(v -> imposta(!acceso, true, true));
        }

        public void setAscoltatore(Ascoltatore a) {
            ascoltatore = a;
        }

        public boolean acceso() {
            return acceso;
        }

        public void imposta(boolean v, boolean animato, boolean avvisa) {
            boolean cambiato = v != acceso;
            acceso = v;
            if (anim != null) anim.cancel();
            if (animato) {
                anim = ValueAnimator.ofFloat(pos, v ? 1f : 0f);
                anim.setDuration(220);
                anim.addUpdateListener(x -> {
                    pos = (float) x.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            } else {
                pos = v ? 1f : 0f;
                invalidate();
            }
            if (cambiato && avvisa && ascoltatore != null) ascoltatore.cambiato(v);
        }

        @Override
        protected void onMeasure(int w, int h) {
            float d = getResources().getDisplayMetrics().density;
            setMeasuredDimension((int) (51 * d), (int) (31 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            int spento = Stile.scuro ? 0xFF39393D : 0xFFE9E9EA;
            p.clearShadowLayer();
            p.setColor(mescola(spento, coloreOn, pos));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            float raggio = r - 2 * d;
            float cx = r + pos * (w - 2 * r);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(2.5f * d, 0, 1f * d, 0x40000000);
            c.drawCircle(cx, r, raggio, p);
            p.clearShadowLayer();
        }
    }
}
