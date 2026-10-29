package com.petrobot.hume;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * News app: the latest stories from la Repubblica and ANSA (Italy, your city and the world),
 * newest first, in three views: a grid of cards, a single column, or full screen like the carousel
 * (Left/Right to move, OK to read). The reader shows the article's text (ANSA's is free; Repubblica
 * shows its opening paragraph, the rest is for subscribers) and can read it aloud with the TV's
 * Italian voice.
 */
public class NewsActivity extends Activity {
    static final int COLS = 3;
    static final String[] MODES = {"Griglia", "Colonna", "Schermo intero"};
    /** The last list, kept for a few minutes so reopening the app is instant. */
    static List<Suggestions.Item> lastList;
    static long lastAt;

    FrameLayout root;
    ScrollView listScroll;
    LinearLayout grid;
    TextView status;
    TextView refreshPill;
    final List<ViewButton> headerViews = new ArrayList<>(), fullViews = new ArrayList<>();
    final ExecutorService net = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 10)) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    boolean loading;
    int mode;
    List<Suggestions.Item> items = new ArrayList<>();
    /** The story last focused (list views) or on screen (full screen). */
    int cur;
    final List<View> cards = new ArrayList<>();

    // full screen
    FrameLayout fullLayer;
    View holder;
    LinearLayout fullPills;
    ImageView fullImg;
    TextView fullLabel, fullTitle, fullSummary, fullCount;
    TextView fullRefreshPill;

    // reader
    FrameLayout reader;
    ScrollView readerScroll;
    TextView listenBtn;
    String readerLink = "";
    final List<String> speakParts = new ArrayList<>();

    // read aloud
    TextToSpeech tts;
    boolean ttsOk, speaking;
    String lastUtterance = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Clicks.init(this);
        Motion.init(this);
        mode = prefs().getInt("view", 0);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B10);
        setContentView(root);
        Clicks.quiet(root);

        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipChildren(false);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(Ui.px(this, 40), Ui.px(this, 36), Ui.px(this, 40), Ui.px(this, 40));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        listScroll.addView(page);
        root.addView(listScroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);  // the focus zoom of the buttons must not be cut
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Notizie", 30, "display", 1f));
        status = text("", 12, "regular", 0.6f);
        titles.addView(status);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        refreshPill = pill("Aggiorna", v -> load(true));
        head.addView(viewButtons(headerViews), pillLp());
        head.addView(refreshPill, pillLp());
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.bottomMargin = Ui.px(this, 18);
        hlp.leftMargin = Ui.px(this, 8);
        hlp.rightMargin = Ui.px(this, 8);
        page.addView(head, hlp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        page.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        load(false);
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        stopSpeaking();
        super.onStop();
    }

    SharedPreferences prefs() {
        return getSharedPreferences("news", MODE_PRIVATE);
    }

    LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.px(this, 10);
        return lp;
    }

    // ---------------------------------------------------------------- loading

    void load(boolean force) {
        if (!force && lastList != null && System.currentTimeMillis() - lastAt < 5 * 60000L) {
            items = lastList;
            render();
            return;
        }
        if (loading) return;
        loading = true;
        status.setText("Aggiornamento…");
        new Thread(() -> {
            List<Suggestions.Item> fresh = News.fetchForApp(this, 60);
            Collections.sort(fresh, (a, b) -> Long.compare(b.published, a.published));  // newest first
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing()) return;
                if (fresh.isEmpty()) {
                    status.setText(lastList == null ? "Nessuna notizia: controlla la connessione." : "Non riesco ad aggiornare.");
                    if (lastList != null) {
                        items = lastList;
                        render();
                    }
                    return;
                }
                lastList = fresh;
                lastAt = System.currentTimeMillis();
                items = fresh;
                cur = 0;
                render();
            });
        }).start();
    }

    // ---------------------------------------------------------------- views

    /** One of the three view buttons (grid, column, full screen). */
    final class ViewButton {
        final int m;
        final FrameLayout view;
        final ImageView icon;
        boolean focused;

        ViewButton(int m, FrameLayout view, ImageView icon) {
            this.m = m;
            this.view = view;
            this.icon = icon;
        }

        void style() {
            boolean selected = m == mode;
            view.setBackground(rounded(focused ? 0xFFF4F4F6 : selected ? 0x66FFFFFF : 0x26FFFFFF, Ui.px(NewsActivity.this, 40)));
            icon.setImageBitmap(Glyphs.get(GLYPHS[m], Ui.px(NewsActivity.this, 20), focused ? 0xFF111114 : Color.WHITE));
            icon.setAlpha(focused || selected ? 1f : 0.6f);
        }
    }

    static final String[] GLYPHS = {"view_grid", "view_list", "view_full"};

    /** Three icon buttons that switch straight to a view: grid, column, full screen. */
    LinearLayout viewButtons(List<ViewButton> into) {
        into.clear();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        int ic = Ui.px(this, 20);
        for (int i = 0; i < MODES.length; i++) {
            int m = i;
            FrameLayout b = new FrameLayout(this);
            b.setFocusable(true);
            b.setClickable(true);
            ImageView iv = new ImageView(this);
            b.addView(iv, new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));
            ViewButton vb = new ViewButton(m, b, iv);
            into.add(vb);
            b.setOnFocusChangeListener((v, has) -> {
                vb.focused = has;
                vb.style();
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(Motion.d(140)).start();
            });
            b.setOnClickListener(v -> setMode(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 36));
            if (i > 0) lp.leftMargin = Ui.px(this, 8);
            row.addView(b, lp);
            vb.style();
        }
        return row;
    }

    /** Switches to a view at once; focus stays on its button so the others are one press away. */
    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        prefs().edit().putInt("view", mode).apply();
        render();
        for (ViewButton vb : headerViews) vb.style();
        for (ViewButton vb : fullViews) vb.style();
        List<ViewButton> shown = mode == 2 ? fullViews : headerViews;
        if (shown.size() > mode) shown.get(mode).view.requestFocus();
    }

    /** Draws the stories in the chosen view. */
    void render() {
        if (items.isEmpty()) return;
        String city = News.city(this);
        status.setText("la Repubblica · ANSA" + (city.isEmpty() ? "" : "  ·  " + city) + "  ·  " + items.size() + " notizie");
        cur = Math.max(0, Math.min(cur, items.size() - 1));
        if (fullLayer != null) {
            root.removeView(fullLayer);
            fullLayer = null;
        }
        if (mode == 2) {
            listScroll.setVisibility(View.GONE);
            buildFull();
            return;
        }
        listScroll.setVisibility(View.VISIBLE);
        grid.removeAllViews();
        cards.clear();
        if (mode == 0) buildGrid();
        else buildColumn();
        if (reader == null && cards.size() > cur) cards.get(cur).requestFocus();
    }

    void buildGrid() {
        int gap = Ui.px(this, 16);
        int w = Ui.px(this, (960 - 80 - 16 - 2 * 16) / (float) COLS);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.bottomMargin = gap;
                grid.addView(row, rlp);
            }
            View card = card(items.get(i), i, w);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, -2);
            clp.rightMargin = gap;
            clp.leftMargin = i % COLS == 0 ? Ui.px(this, 8) : 0;
            row.addView(card, clp);
            cards.add(card);
        }
    }

    void buildColumn() {
        for (int i = 0; i < items.size(); i++) {
            View r = rowCard(items.get(i), i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.leftMargin = Ui.px(this, 8);
            lp.rightMargin = Ui.px(this, 8);
            lp.bottomMargin = Ui.px(this, 12);
            grid.addView(r, lp);
            cards.add(r);
        }
    }

    GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        g.setColor(color);
        return g;
    }

    ImageView picture(Suggestions.Item it, int w, int h, float r, boolean topOnly) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0x22FFFFFF);
        img.setClipToOutline(true);
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + (topOnly ? (int) r : 0), r);
            }
        });
        img.setTag(it);
        loadImage(it, img, w, false);
        return img;
    }

    /** A grid card: picture on top, headline and source below. */
    View card(Suggestions.Item it, int index, int w) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setClipChildren(false);
        float r = Ui.px(this, 12);
        GradientDrawable idle = rounded(0x1AFFFFFF, r), lit = rounded(0xFFF4F4F6, r);
        c.setBackground(idle);
        c.addView(picture(it, w, w * 9 / 16, r, true), new LinearLayout.LayoutParams(w, w * 9 / 16));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(this, 11);
        body.setPadding(pad, pad - 2, pad, pad);
        TextView t = text(it.title, 13, "semibold", 1f);
        t.setMaxLines(3);
        t.setMinLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(t);
        TextView m = text(meta(it), 10.5f, "regular", 0.62f);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = Ui.px(this, 6);
        body.addView(m, mlp);
        c.addView(body, new LinearLayout.LayoutParams(-1, -2));
        focusStyle(c, index, idle, lit, 1.05f, t, m, null);
        c.setOnClickListener(v -> openReader(it));
        return c;
    }

    /** A column row: small picture on the left; headline, summary and source on the right. */
    View rowCard(Suggestions.Item it, int index) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setClipChildren(false);
        float r = Ui.px(this, 12);
        GradientDrawable idle = rounded(0x1AFFFFFF, r), lit = rounded(0xFFF4F4F6, r);
        c.setBackground(idle);
        int pad = Ui.px(this, 10);
        c.setPadding(pad, pad, pad, pad);
        int w = Ui.px(this, 224), h = Ui.px(this, 126);
        c.addView(picture(it, w, h, Ui.px(this, 9), false), new LinearLayout.LayoutParams(w, h));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(it.title, 16, "semibold", 1f);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(t);
        TextView s = text(it.overview == null ? "" : it.overview, 12.5f, "regular", 0.7f);
        s.setMaxLines(3);
        s.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.px(this, 5);
        body.addView(s, slp);
        TextView m = text(meta(it), 11, "regular", 0.6f);
        m.setSingleLine(true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = Ui.px(this, 6);
        body.addView(m, mlp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, h, 1f);
        blp.leftMargin = Ui.px(this, 16);
        c.addView(body, blp);
        focusStyle(c, index, idle, lit, 1.02f, t, m, s);
        c.setOnClickListener(v -> openReader(it));
        return c;
    }

    void focusStyle(View c, int index, GradientDrawable idle, GradientDrawable lit, float zoom, TextView t, TextView m, TextView s) {
        c.setOnFocusChangeListener((v, has) -> {
            if (has) cur = index;
            c.setBackground(has ? lit : idle);
            t.setTextColor(has ? 0xFF111114 : Color.WHITE);
            m.setTextColor(has ? 0x99111114 : 0x9EFFFFFF);
            if (s != null) s.setTextColor(has ? 0xB3111114 : Color.WHITE);
            v.animate().scaleX(has ? zoom : 1f).scaleY(has ? zoom : 1f).translationZ(has ? Ui.px(this, 10) : 0)
                    .setDuration(Motion.d(160)).start();
        });
    }

    String meta(Suggestions.Item it) {
        String age = News.age(it.published);
        return it.genres + (age.isEmpty() ? "" : "  ·  " + age);
    }

    // ---------------------------------------------------------------- full screen

    /** One story fills the screen, like the carousel: Left/Right move, OK reads, Up reaches the buttons. */
    void buildFull() {
        fullLayer = new FrameLayout(this);
        fullLayer.setBackgroundColor(Color.BLACK);
        fullImg = new ImageView(this);
        fullImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fullLayer.addView(fullImg, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xB3000000, 0x00000000}));
        fullLayer.addView(shade, new FrameLayout.LayoutParams(-1, Ui.px(this, 400), Gravity.BOTTOM));
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
        fullLayer.addView(top, new FrameLayout.LayoutParams(-1, Ui.px(this, 110), Gravity.TOP));

        holder = new View(this);
        holder.setFocusable(true);
        fullLayer.addView(holder, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(this, 56);
        text.setPadding(pad, 0, pad, Ui.px(this, 44));
        fullLabel = text("", 14, "medium", 0.85f);
        text.addView(fullLabel);
        fullTitle = text("", 34, "display", 1f);
        fullTitle.setMaxLines(3);
        fullTitle.setEllipsize(TextUtils.TruncateAt.END);
        fullTitle.setLineSpacing(0, 1.02f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.px(this, 6);
        text.addView(fullTitle, tlp);
        fullSummary = text("", 16, "regular", 0.85f);
        fullSummary.setMaxLines(3);
        fullSummary.setEllipsize(TextUtils.TruncateAt.END);
        fullSummary.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.px(this, 760), -2);
        slp.topMargin = Ui.px(this, 10);
        text.addView(fullSummary, slp);
        fullCount = text("", 12, "regular", 0.55f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.px(this, 14);
        text.addView(fullCount, clp);
        fullLayer.addView(text, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        fullPills = new LinearLayout(this);
        fullPills.setOrientation(LinearLayout.HORIZONTAL);
        fullPills.setClipChildren(false);
        fullRefreshPill = pill("Aggiorna", v -> load(true));
        fullPills.addView(viewButtons(fullViews), pillLp());
        fullPills.addView(fullRefreshPill, pillLp());
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = Ui.px(this, 28);
        plp.rightMargin = Ui.px(this, 48);
        fullLayer.addView(fullPills, plp);

        root.addView(fullLayer, new FrameLayout.LayoutParams(-1, -1));
        showFull(0, false);
        if (reader == null) holder.requestFocus();
    }

    void showFull(int dir, boolean animate) {
        if (items.isEmpty() || fullLayer == null) return;
        cur = (cur + dir + items.size()) % items.size();
        Suggestions.Item it = items.get(cur);
        fullLabel.setText(it.genres);
        fullTitle.setText(it.title);
        fullSummary.setText(it.overview == null ? "" : it.overview);
        String age = News.age(it.published);
        fullCount.setText((cur + 1) + " / " + items.size() + (age.isEmpty() ? "" : "  ·  " + age)
                + "  ·  ◀ ▶ per scorrere  ·  OK per leggere");
        fullImg.setTag(it);
        fullImg.setImageBitmap(null);
        loadImage(it, fullImg, Ui.screen(this).x, true);
        // Warm the neighbours so Left/Right show their picture at once.
        for (int d = -1; d <= 1; d += 2) {
            Suggestions.Item n = items.get((cur + d + items.size()) % items.size());
            ImageView warm = new ImageView(this);
            warm.setTag(n);
            loadImage(n, warm, Ui.screen(this).x, false);
        }
        if (animate) {
            View[] v = {fullLabel, fullTitle, fullSummary};
            for (View x : v) {
                x.setAlpha(0f);
                x.setTranslationX(Ui.px(this, 24) * dir);
                x.animate().alpha(x == fullLabel || x == fullSummary ? 0.85f : 1f).translationX(0)
                        .setDuration(Motion.d(320)).start();
            }
        }
    }

    // ---------------------------------------------------------------- pictures

    /** Picture for a card or the reader: the feed's image, or (ANSA) the article's own, cached on disk. */
    void loadImage(Suggestions.Item it, ImageView into, int width, boolean fade) {
        net.execute(() -> {
            String url = it.backdrop;
            Bitmap bmp = null;
            try {
                if (url == null || url.isEmpty()) {
                    url = ogImage(link(it));
                    if (url != null) it.backdrop = url;
                }
                if (url != null && !url.isEmpty()) {
                    String key = url + "@" + width;
                    bmp = bitmaps.get(key);
                    if (bmp == null) {
                        bmp = Suggestions.image(this, url, "", width);
                        if (bmp != null) bitmaps.put(key, bmp);
                    }
                }
            } catch (Exception ignored) {
                // no picture
            }
            Bitmap done = bmp;
            if (done != null) {
                runOnUiThread(() -> {
                    if (into.getTag() != it) return;  // the view moved on to another story
                    into.setImageBitmap(done);
                    if (fade) {
                        into.setAlpha(0f);
                        into.animate().alpha(1f).setDuration(Motion.d(260)).start();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- article

    static String link(Suggestions.Item it) {
        return it.link == null ? "" : it.link.replace("?rss", "").replace("?rssimage", "");
    }

    static final class Article {
        final List<String> paragraphs = new ArrayList<>();
        /** Only the opening of the story is public (subscriber content continues on the site). */
        boolean partial;
    }

    static String download(String url, int maxBytes) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < maxBytes) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    static String meta(String html, String prop) {
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=\"" + prop + "\"[^>]+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return android.text.Html.fromHtml(m.group(1)).toString().trim();
        m = Pattern.compile("<meta[^>]+content=\"([^\"]*)\"[^>]+(?:property|name)=\"" + prop + "\"", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? android.text.Html.fromHtml(m.group(1)).toString().trim() : "";
    }

    /** The article's own picture (og:image), read from the top of the page. */
    static String ogImage(String url) throws Exception {
        String s = meta(download(url, 60000), "og:image");
        return s.isEmpty() ? null : s;
    }

    /** The story's text from its page. */
    static Article parse(String html) {
        Article a = new Article();
        // ANSA: the story is in <div class="... news-txt">, paragraphs separated by <br>.
        int i = html.indexOf("news-txt");
        if (i >= 0) {
            int s = html.indexOf('>', i) + 1;
            int e = html.indexOf("article-copyright", s);
            String body = html.substring(s, e > s ? e : Math.min(html.length(), s + 60000));
            body = body.replaceAll("(?i)<br\\s*/?>|</p>", "\u0001").replaceAll("(?s)<[^>]*>", " ");
            addParagraphs(a, android.text.Html.fromHtml(body).toString());
        }
        // la Repubblica: the page's JSON carries the public opening of the story.
        if (a.paragraphs.isEmpty()) {
            Matcher m = Pattern.compile("\"articleBody\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(html);
            if (m.find()) {
                try {
                    String text = new JSONObject("{\"a\":\"" + m.group(1) + "\"}").getString("a");
                    a.partial = text.trim().endsWith("…") || text.trim().endsWith("...");
                    addParagraphs(a, text.replace("\n", "\u0001"));
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        // Anything else: the long paragraphs of the page.
        if (a.paragraphs.isEmpty()) {
            Matcher m = Pattern.compile("(?s)<p[^>]*>(.*?)</p>").matcher(html);
            while (m.find() && a.paragraphs.size() < 30) {
                String t = android.text.Html.fromHtml(m.group(1)).toString().replaceAll("\\s+", " ").trim();
                if (t.length() > 90) a.paragraphs.add(t);
            }
            a.partial = true;
        }
        return a;
    }

    static void addParagraphs(Article a, String raw) {
        for (String p : raw.split("\u0001")) {
            String t = p.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) a.paragraphs.add(t);
        }
    }

    // ---------------------------------------------------------------- reader

    void openReader(Suggestions.Item it) {
        if (reader != null) return;
        readerLink = link(it);
        speakParts.clear();
        speakParts.add(it.title);
        String lead = it.overview == null ? "" : it.overview;
        if (!lead.isEmpty()) speakParts.add(lead);

        reader = new FrameLayout(this);
        reader.setBackgroundColor(0xFF0B0B10);
        reader.setClickable(true);
        readerScroll = new ScrollView(this);
        readerScroll.setVerticalScrollBarEnabled(false);
        readerScroll.setFocusable(false);
        reader.addView(readerScroll, new FrameLayout.LayoutParams(Ui.px(this, 700), -1, Gravity.CENTER_HORIZONTAL));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.px(this, 32), 0, Ui.px(this, 60));
        readerScroll.addView(col);

        int w = Ui.px(this, 700);
        col.addView(picture(it, w, Ui.px(this, 215), Ui.px(this, 14), false), new LinearLayout.LayoutParams(w, Ui.px(this, 215)));
        TextView title = text(it.title, 25, "display", 1f);
        title.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.px(this, 18);
        col.addView(title, tlp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = Ui.px(this, 6);
        col.addView(text(meta(it), 12, "regular", 0.62f), mlp);

        listenBtn = pill("Ascolta", v -> toggleListen());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.px(this, 14);
        blp.leftMargin = Ui.px(this, 10);  // room for the focus zoom
        col.addView(listenBtn, blp);

        if (!lead.isEmpty()) {
            TextView summary = text(lead, 15, "semibold", 0.95f);
            summary.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = Ui.px(this, 18);
            col.addView(summary, slp);
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(text("Carico l’articolo…", 13, "regular", 0.55f));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, -2);
        xlp.topMargin = Ui.px(this, 14);
        col.addView(body, xlp);

        root.addView(reader, new FrameLayout.LayoutParams(-1, -1));
        reader.setAlpha(0f);
        reader.animate().alpha(1f).setDuration(Motion.d(180)).start();
        listenBtn.requestFocus();

        net.execute(() -> {
            Article a = null;
            try {
                a = parse(download(readerLink, 700000));
            } catch (Exception ignored) {
                // shown below
            }
            Article art = a;
            runOnUiThread(() -> {
                if (reader == null || isFinishing()) return;
                body.removeAllViews();
                if (art == null || art.paragraphs.isEmpty()) {
                    body.addView(text("Non riesco a caricare il testo dell’articolo.", 13, "regular", 0.7f));
                    return;
                }
                for (String p : art.paragraphs) {
                    TextView t = text(p, 15, "regular", 0.92f);
                    t.setLineSpacing(0, 1.3f);
                    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
                    plp.bottomMargin = Ui.px(this, 12);
                    body.addView(t, plp);
                    speakParts.add(p);
                }
                if (art.partial) {
                    body.addView(text("Il resto dell’articolo è riservato agli abbonati e non è disponibile qui.",
                            12.5f, "medium", 0.6f));
                }
            });
        });
    }

    void closeReader() {
        if (reader == null) return;
        stopSpeaking();
        root.removeView(reader);
        reader = null;
        readerScroll = null;
        listenBtn = null;
        if (mode == 2 && holder != null) holder.requestFocus();
        else if (cards.size() > cur) cards.get(cur).requestFocus();
    }

    // ---------------------------------------------------------------- read aloud

    void toggleListen() {
        if (speaking) {
            stopSpeaking();
            return;
        }
        if (tts == null) {
            Toast.makeText(this, "Preparo la voce…", Toast.LENGTH_SHORT).show();
            tts = new TextToSpeech(this, st -> runOnUiThread(() -> {
                if (st != TextToSpeech.SUCCESS) {
                    Toast.makeText(this, "La sintesi vocale non è disponibile su questo TV", Toast.LENGTH_LONG).show();
                    tts = null;
                    return;
                }
                int r = tts.setLanguage(Locale.ITALY);
                ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsOk) {
                    Toast.makeText(this, "Voce italiana non disponibile: installala da Impostazioni → Sintesi vocale",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                tts.setSpeechRate(0.95f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) {
                        if (id.equals(lastUtterance)) runOnUiThread(() -> setSpeaking(false));
                    }
                    @Override public void onError(String id) {
                        if (id.equals(lastUtterance)) runOnUiThread(() -> setSpeaking(false));
                    }
                });
                startSpeaking();
            }));
            return;
        }
        startSpeaking();
    }

    void startSpeaking() {
        if (!ttsOk || tts == null) return;
        int max = Math.max(500, Math.min(3500, TextToSpeech.getMaxSpeechInputLength() - 100));
        int n = 0;
        for (String part : speakParts) {
            String rest = part;
            while (!rest.isEmpty()) {
                String chunk = rest;
                if (rest.length() > max) {
                    int cut = rest.lastIndexOf(". ", max);
                    chunk = rest.substring(0, cut > 0 ? cut + 1 : max);
                }
                rest = rest.substring(chunk.length()).trim();
                lastUtterance = "n" + n;
                tts.speak(chunk, n == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, lastUtterance);
                n++;
            }
        }
        setSpeaking(n > 0);
    }

    void stopSpeaking() {
        if (tts != null) tts.stop();
        setSpeaking(false);
    }

    void setSpeaking(boolean on) {
        speaking = on;
        if (listenBtn != null) listenBtn.setText(on ? "Ferma lettura" : "Ascolta");
    }

    // ---------------------------------------------------------------- keys

    @Override
    public void onBackPressed() {
        if (reader != null) closeReader();
        else super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            int k = ev.getKeyCode();
            if (reader != null && readerScroll != null) {
                if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_UP) {
                    readerScroll.smoothScrollBy(0, (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) * Ui.px(this, 120));
                    return true;
                }
            } else if (mode == 2 && fullLayer != null && holder != null) {
                View f = getCurrentFocus();
                if (f == holder) {
                    if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        Clicks.move(this, k);
                        showFull(1, true);
                        return true;
                    }
                    if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                        Clicks.move(this, k);
                        showFull(-1, true);
                        return true;
                    }
                    if (k == KeyEvent.KEYCODE_DPAD_UP) {
                        fullViews.get(mode).view.requestFocus();
                        return true;
                    }
                    if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                        Clicks.select(this);
                        openReader(items.get(cur));
                        return true;
                    }
                } else if (f != null && inside(f, fullPills) && k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    holder.requestFocus();
                    return true;
                }
            }
        }
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev) || Clicks.moveFocus(getWindow(), ev);
    }

    // ---------------------------------------------------------------- bits

    static boolean inside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    TextView text(String s, float size, String weight, float alpha) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setTypeface(Ui.font(this, weight));
        Ui.textSize(t, size);
        return t;
    }

    /** A glass-style pill button: white when focused. */
    TextView pill(String label, View.OnClickListener click) {
        TextView t = text(label, 13, "semibold", 1f);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        int ph = Ui.px(this, 18), pv = Ui.px(this, 9);
        t.setPadding(ph, pv, ph, pv);
        GradientDrawable idle = rounded(0x40FFFFFF, Ui.px(this, 40)), lit = rounded(0xFFF4F4F6, Ui.px(this, 40));
        t.setBackground(idle);
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(has ? lit : idle);
            t.setTextColor(has ? 0xFF111114 : Color.WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(Motion.d(140)).start();
        });
        t.setOnClickListener(click);
        return t;
    }
}
