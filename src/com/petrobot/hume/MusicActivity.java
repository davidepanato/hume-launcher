package com.petrobot.hume;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Music: choose what to play from Music Assistant (through Home Assistant) and where. The top of
 * the screen holds everything you press often: sections, view (grid / list / large covers),
 * search, the player, and a strip with what is playing and its controls. "Ora in riproduzione"
 * opens a full-screen view with the artwork and the song's lyrics (synchronised when available,
 * from LRCLIB), highlighting the line being sung.
 */
public class MusicActivity extends Activity {
    static final String[] TABS = {"Playlist", "Album", "Artisti", "Preferiti", "Radio"};
    static final String[] LAYOUTS = {"Griglia", "Elenco", "Copertine grandi"};
    static final String[] LAYOUT_GLYPHS = {"view_grid", "view_list", "view_big"};
    static final String K_PLAYER = "mass_player", K_ENTRY = "mass_entry";
    static final String DEFAULT_PLAYER = "media_player.dac";

    /** A playable thing from Music Assistant. */
    static final class MItem {
        String type = "", uri = "", name = "", sub = "", image = "";
    }

    static final class Player {
        String id, name, state;
    }

    /** What the chosen player is playing right now. */
    static final class Now {
        String title = "", artist = "", album = "", picture = "", state = "";
        double duration, position;
        long positionAt;  // when `position` was true (ms, TV clock)
        boolean playing;
    }

    FrameLayout root;
    LinearLayout content;
    ScrollView scroll;
    TextView playerPill, searchPill;
    final List<TextView> tabViews = new ArrayList<>();
    final List<FrameLayout> layoutButtons = new ArrayList<>();
    final List<View> cards = new ArrayList<>();
    int tab = 0, layout = 0;
    boolean searching;
    String query = "";
    List<String> shownTitles = new ArrayList<>();
    List<List<MItem>> shownGroups = new ArrayList<>();
    final ExecutorService net = Executors.newFixedThreadPool(3);
    final Handler handler = new Handler();
    final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    int loadId;

    // player and what is playing
    String playerId = "", playerName = "";
    List<Player> players = new ArrayList<>();
    final Now now = new Now();
    ImageView npCover;
    TextView npTitle, npSub, npVolume;
    FrameLayout npPlay;
    float npVol = -1f;
    boolean alive, demo;

    // full screen "now playing" with lyrics
    FrameLayout full;
    ImageView fullCover, fullBackdrop;
    TextView fullTitle, fullArtist, fullAlbum, fullTime, fullStatus, fullVolume;
    View fullFill;
    FrameLayout fullPlay;
    ScrollView lyricScroll;
    LinearLayout lyricBox;
    TextView[] lyricViews = new TextView[0];
    long[] lyricTimes = new long[0];
    boolean lyricSynced;
    String lyricKey = "", fullPictureKey = "";
    int lyricNow = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Clicks.init(this);
        Motion.init(this);
        playerId = HomeAssistant.prefs(this).getString(K_PLAYER, "");
        layout = prefs().getInt("layout", 0);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B10);
        setContentView(root);
        Clicks.quiet(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Row 1: title, sections, view, player, search.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        head.setPadding(Ui.px(this, 40), Ui.px(this, 18), Ui.px(this, 40), Ui.px(this, 6));
        head.addView(text("Musica", 24, "display", 1f));
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            TextView c = pill(TABS[i], v -> selectTab(t));
            tabViews.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = Ui.px(this, i == 0 ? 20 : 6);
            head.addView(c, lp);
        }
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        for (int i = 0; i < LAYOUTS.length; i++) {
            FrameLayout lb = layoutButton(i);
            layoutButtons.add(lb);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(this, 40), Ui.px(this, 32));
            lp.leftMargin = Ui.px(this, 6);
            head.addView(lb, lp);
        }
        playerPill = pill("Player…", v -> choosePlayer());
        searchPill = pill("Cerca", v -> askSearch());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = Ui.px(this, 14);
        head.addView(playerPill, plp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = Ui.px(this, 6);
        head.addView(searchPill, slp);
        page.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Row 2: what is playing, with its controls.
        page.addView(nowPlayingStrip(), new LinearLayout.LayoutParams(-1, Ui.px(this, 66)));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.px(this, 32), Ui.px(this, 10), Ui.px(this, 32), Ui.px(this, 10));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        styleLayoutButtons();

        if (!HomeAssistant.configured(this)) {
            message("Collega Home Assistant in Impostazioni → Home Assistant.");
            return;
        }
        loadPlayers(true);
        selectTab(0);
        // adb screenshots: --es demo_track "Artist~Title~Album~seconds~position" shows a made-up song (no player is touched).
        String demoTrack = getIntent().getStringExtra("demo_track");
        if (demoTrack != null) {
            String[] d = demoTrack.split("~");
            demo = true;
            Now n = new Now();
            n.artist = d[0];
            n.title = d.length > 1 ? d[1] : "";
            n.album = d.length > 2 ? d[2] : "";
            n.duration = d.length > 3 ? Double.parseDouble(d[3]) : 200;
            n.position = d.length > 4 ? Double.parseDouble(d[4]) : 0;
            n.positionAt = System.currentTimeMillis();
            n.playing = true;
            n.state = "playing";
            n.picture = "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/15/c7/55/15c755bc-7603-3ce3-e243-e59f1f9863ea/602527724171.jpg/1000x1000bb.jpg";
            applyNow(n, 0.1f);
            if (getIntent().getBooleanExtra("demo_full", false)) openFull();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        handler.post(poll);
        if (full != null) handler.post(tick);
    }

    @Override
    protected void onPause() {
        alive = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    SharedPreferences prefs() {
        return getSharedPreferences("music", MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- players

    /** The Music Assistant players in Home Assistant (those with a Music Assistant player type). */
    void loadPlayers(boolean pickDefault) {
        net.execute(() -> {
            List<Player> found = new ArrayList<>();
            try {
                JSONArray all = new JSONArray(HomeAssistant.request(this, "GET", "/api/states", null));
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.getJSONObject(i);
                    String id = o.getString("entity_id");
                    JSONObject a = o.getJSONObject("attributes");
                    if (!id.startsWith("media_player.") || !a.has("mass_player_type")) continue;
                    String st = o.optString("state");
                    if (st.equals("unavailable")) continue;
                    Player p = new Player();
                    p.id = id;
                    p.name = a.optString("friendly_name", id);
                    p.state = st;
                    found.add(p);
                }
            } catch (Exception ignored) {
                // keep the old list
            }
            runOnUiThread(() -> {
                if (!found.isEmpty()) players = found;
                if (pickDefault && !players.isEmpty()) {
                    boolean known = false;
                    for (Player p : players) if (p.id.equals(playerId)) known = true;
                    if (!known) {
                        // The default is the DAC (change it with the "Su:" button); otherwise the first player.
                        Player best = players.get(0);
                        for (Player p : players) if (p.id.equals(DEFAULT_PLAYER)) best = p;
                        setPlayer(best);
                    }
                }
                for (Player p : players) if (p.id.equals(playerId)) playerName = p.name;
                playerPill.setText(playerName.isEmpty() ? "Scegli il player" : "Su: " + playerName);
            });
        });
    }

    void setPlayer(Player p) {
        playerId = p.id;
        playerName = p.name;
        HomeAssistant.prefs(this).edit().putString(K_PLAYER, p.id).apply();
        playerPill.setText("Su: " + p.name);
        now.picture = "";
        poll.run();
    }

    void choosePlayer() {
        net.execute(() -> {
            loadPlayers(false);
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                // go on
            }
            runOnUiThread(() -> {
                if (players.isEmpty()) {
                    toast("Nessun player di Music Assistant trovato");
                    return;
                }
                String[] names = new String[players.size()];
                int sel = 0;
                for (int i = 0; i < names.length; i++) {
                    Player p = players.get(i);
                    names[i] = p.name + "  ·  " + stateIt(p.state);
                    if (p.id.equals(playerId)) sel = i;
                }
                Clicks.dialog(this).setTitle("Riproduci su").setSingleChoiceItems(names, sel, (d, w) -> {
                    setPlayer(players.get(w));
                    d.dismiss();
                }).show();
            });
        });
    }

    static String stateIt(String s) {
        switch (s) {
            case "playing": return "in riproduzione";
            case "paused": return "in pausa";
            case "idle": return "inattivo";
            case "off": return "spento";
            default: return s;
        }
    }

    // ---------------------------------------------------------------- music assistant

    String entry() throws Exception {
        String e = HomeAssistant.prefs(this).getString(K_ENTRY, "");
        if (!e.isEmpty()) return e;
        JSONArray a = new JSONArray(HomeAssistant.request(this, "GET", "/api/config/config_entries/entry?domain=music_assistant", null));
        if (a.length() == 0) throw new Exception("Music Assistant non è collegato a Home Assistant");
        e = a.getJSONObject(0).getString("entry_id");
        HomeAssistant.prefs(this).edit().putString(K_ENTRY, e).apply();
        return e;
    }

    JSONObject service(String name, JSONObject data) throws Exception {
        String r = HomeAssistant.request(this, "POST", "/api/services/music_assistant/" + name + "?return_response", data.toString());
        return new JSONObject(r).getJSONObject("service_response");
    }

    static MItem parse(JSONObject o) {
        MItem m = new MItem();
        m.type = o.optString("media_type");
        m.uri = o.optString("uri");
        m.name = o.optString("name");
        m.image = o.optString("image", "");
        if (m.image.equals("null")) m.image = "";
        List<String> sub = new ArrayList<>();
        JSONArray ar = o.optJSONArray("artists");
        if (ar != null) for (int i = 0; i < ar.length() && i < 2; i++) sub.add(ar.optJSONObject(i).optString("name"));
        JSONObject al = o.optJSONObject("album");
        if (m.type.equals("track")) {
            if (al != null) sub.add(al.optString("name"));
        } else if (sub.isEmpty()) {
            sub.add(m.type.equals("artist") ? "Artista" : m.type.equals("playlist") ? "Playlist" : m.type.equals("radio") ? "Radio" : "");
        }
        m.sub = TextUtils.join("  ·  ", sub);
        return m;
    }

    static List<MItem> items(JSONArray a) {
        List<MItem> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            MItem m = parse(a.optJSONObject(i));
            if (!m.uri.isEmpty() && !m.name.isEmpty()) out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- sections and layouts

    void selectTab(int t) {
        tab = t;
        searching = false;
        for (int i = 0; i < tabViews.size(); i++) tabViews.get(i).setBackground(tabBg(i == t, false));
        final int id = ++loadId;
        message("Caricamento…");
        net.execute(() -> {
            List<MItem> list = new ArrayList<>();
            String err = null;
            try {
                JSONObject d = new JSONObject().put("config_entry_id", entry()).put("limit", 60);
                switch (t) {
                    case 0: d.put("media_type", "playlist").put("order_by", "last_played_desc"); break;
                    case 1: d.put("media_type", "album").put("order_by", "last_played_desc"); break;
                    case 2: d.put("media_type", "artist").put("order_by", "last_played_desc"); break;
                    case 3: d.put("media_type", "track").put("favorite", true); break;
                    default: d.put("media_type", "radio"); break;
                }
                list = items(service("get_library", d).optJSONArray("items"));
            } catch (Exception e) {
                err = e.getMessage();
            }
            List<MItem> res = list;
            String error = err;
            runOnUiThread(() -> {
                if (id != loadId || isFinishing()) return;
                if (error != null) message("Non riesco a leggere la libreria: " + error);
                else if (res.isEmpty()) message("Niente qui.");
                else showSections(java.util.Collections.singletonList(""), java.util.Collections.singletonList(res));
            });
        });
    }

    void askSearch() {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("Artista, album, brano…");
        in.setText(query);
        in.setSelection(in.getText().length());
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = Ui.px(this, 14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        Clicks.dialog(this).setTitle("Cerca in Music Assistant").setView(box)
                .setPositiveButton("Cerca", (d, w) -> {
                    query = in.getText().toString().trim();
                    if (!query.isEmpty()) runSearch();
                }).setNegativeButton("Annulla", null).show();
        in.requestFocus();
    }

    void runSearch() {
        searching = true;
        for (TextView t : tabViews) t.setBackground(tabBg(false, false));
        final int id = ++loadId;
        message("Cerco “" + query + "”…");
        net.execute(() -> {
            List<String> titles = new ArrayList<>();
            List<List<MItem>> groups = new ArrayList<>();
            String err = null;
            try {
                JSONObject d = new JSONObject().put("config_entry_id", entry()).put("name", query).put("limit", 10)
                        .put("media_type", new JSONArray().put("artist").put("album").put("track").put("playlist").put("radio"));
                JSONObject r = service("search", d);
                String[][] kinds = {{"artists", "Artisti"}, {"albums", "Album"}, {"tracks", "Brani"}, {"playlists", "Playlist"}, {"radio", "Radio"}};
                for (String[] k : kinds) {
                    List<MItem> l = items(r.optJSONArray(k[0]));
                    if (!l.isEmpty()) {
                        titles.add(k[1]);
                        groups.add(l);
                    }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            String error = err;
            runOnUiThread(() -> {
                if (id != loadId || isFinishing()) return;
                if (error != null) message("Ricerca non riuscita: " + error);
                else if (groups.isEmpty()) message("Nessun risultato per “" + query + "”.");
                else showSections(titles, groups);
            });
        });
    }

    void message(String s) {
        content.removeAllViews();
        cards.clear();
        TextView t = text(s, 14, "regular", 0.7f);
        t.setPadding(Ui.px(this, 8), Ui.px(this, 16), 0, 0);
        content.addView(t);
    }

    /** Titled sections of items, drawn in the chosen layout. */
    void showSections(List<String> titles, List<List<MItem>> groups) {
        shownTitles = titles;
        shownGroups = groups;
        content.removeAllViews();
        cards.clear();
        scroll.scrollTo(0, 0);
        int cols = layout == 0 ? 5 : layout == 2 ? 3 : 1;
        int gap = Ui.px(this, 14);
        int w = Ui.px(this, (960 - 64 - 16 - (cols - 1) * 14) / (float) cols);
        for (int g = 0; g < groups.size(); g++) {
            if (!titles.get(g).isEmpty()) {
                TextView h = text(titles.get(g), 15, "semibold", 0.8f);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
                hlp.topMargin = Ui.px(this, 8);
                hlp.bottomMargin = Ui.px(this, 8);
                hlp.leftMargin = Ui.px(this, 8);
                content.addView(h, hlp);
            }
            List<MItem> list = groups.get(g);
            LinearLayout row = null;
            for (int i = 0; i < list.size(); i++) {
                if (layout == 1) {
                    View r = rowCard(list.get(i));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.leftMargin = Ui.px(this, 8);
                    lp.rightMargin = Ui.px(this, 8);
                    lp.bottomMargin = Ui.px(this, 8);
                    content.addView(r, lp);
                    cards.add(r);
                    continue;
                }
                if (i % cols == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setClipChildren(false);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                    rlp.bottomMargin = gap;
                    content.addView(row, rlp);
                }
                View c = card(list.get(i), w);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, -2);
                clp.rightMargin = gap;
                clp.leftMargin = i % cols == 0 ? Ui.px(this, 8) : 0;
                row.addView(c, clp);
                cards.add(c);
            }
        }
        if (full == null && !cards.isEmpty() && !inHeader()) cards.get(0).requestFocus();
    }

    /** True while focus is on one of the buttons above the list (keep it there when the list reloads). */
    boolean inHeader() {
        View f = getCurrentFocus();
        return f != null && !isInside(f, scroll);
    }

    static boolean isInside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    ImageView cover(MItem it, int w, boolean round, float r, boolean topOnly) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0x22FFFFFF);
        img.setClipToOutline(true);
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                if (round) o.setOval(0, 0, v.getWidth(), v.getHeight());
                else o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + (topOnly ? (int) r : 0), r);
            }
        });
        img.setTag(it);
        loadImage(it.image, it, img, w);
        return img;
    }

    /** A cover card (grid or large covers): the artwork, then name and detail. */
    View card(MItem it, int w) {
        boolean big = layout == 2;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setClipChildren(false);
        float r = Ui.px(this, 10);
        GradientDrawable idle = rounded(0x1AFFFFFF, r), lit = rounded(0xFFF4F4F6, r);
        c.setBackground(idle);
        boolean round = it.type.equals("artist");
        int side = round ? w - Ui.px(this, 20) : w;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(side, side);
        if (round) {
            ilp.topMargin = Ui.px(this, 10);
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
        }
        c.addView(cover(it, side, round, r, true), ilp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(this, big ? 12 : 9);
        body.setPadding(pad, pad - 2, pad, pad);
        TextView t = text(it.name, big ? 15 : 12, "semibold", 1f);
        t.setMaxLines(2);
        t.setMinLines(big ? 1 : 2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(t);
        TextView s = text(it.sub, big ? 12 : 10, "regular", 0.62f);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(s);
        c.addView(body, new LinearLayout.LayoutParams(-1, -2));
        focusStyle(c, idle, lit, t, s, 1.05f);
        c.setOnClickListener(v -> chooseAction(it));
        return c;
    }

    /** A list row: small artwork, name and detail. */
    View rowCard(MItem it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setClipChildren(false);
        float r = Ui.px(this, 10);
        GradientDrawable idle = rounded(0x1AFFFFFF, r), lit = rounded(0xFFF4F4F6, r);
        c.setBackground(idle);
        int pad = Ui.px(this, 8);
        c.setPadding(pad, pad, pad, pad);
        int side = Ui.px(this, 56);
        c.addView(cover(it, side, it.type.equals("artist"), Ui.px(this, 7), false), new LinearLayout.LayoutParams(side, side));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(it.name, 15, "semibold", 1f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(t);
        TextView s = text(it.sub, 12, "regular", 0.62f);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(s);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
        blp.leftMargin = Ui.px(this, 14);
        c.addView(body, blp);
        focusStyle(c, idle, lit, t, s, 1.01f);
        c.setOnClickListener(v -> chooseAction(it));
        return c;
    }

    void focusStyle(View c, GradientDrawable idle, GradientDrawable lit, TextView t, TextView s, float zoom) {
        c.setOnFocusChangeListener((v, has) -> {
            c.setBackground(has ? lit : idle);
            t.setTextColor(has ? 0xFF111114 : Color.WHITE);
            s.setTextColor(has ? 0x99111114 : 0x9EFFFFFF);
            v.animate().scaleX(has ? zoom : 1f).scaleY(has ? zoom : 1f).translationZ(has ? Ui.px(this, 10) : 0)
                    .setDuration(Motion.d(160)).start();
        });
    }

    FrameLayout layoutButton(int m) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setClickable(true);
        int ic = Ui.px(this, 18);
        ImageView iv = new ImageView(this);
        b.addView(iv, new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(rounded(has ? 0xFFF4F4F6 : m == layout ? 0x66FFFFFF : 0x26FFFFFF, Ui.px(this, 40)));
            iv.setImageBitmap(Glyphs.get(LAYOUT_GLYPHS[m], ic, has ? 0xFF111114 : Color.WHITE));
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(Motion.d(140)).start();
        });
        b.setOnClickListener(v -> setLayout(m));
        return b;
    }

    void styleLayoutButtons() {
        int ic = Ui.px(this, 18);
        for (int i = 0; i < layoutButtons.size(); i++) {
            FrameLayout b = layoutButtons.get(i);
            b.setBackground(rounded(i == layout ? 0x66FFFFFF : 0x26FFFFFF, Ui.px(this, 40)));
            ((ImageView) b.getChildAt(0)).setImageBitmap(Glyphs.get(LAYOUT_GLYPHS[i], ic, b.isFocused() ? 0xFF111114 : Color.WHITE));
        }
    }

    /** Switches layout at once; focus stays on its button so the others are one press away. */
    void setLayout(int m) {
        if (m == layout) return;
        layout = m;
        prefs().edit().putInt("layout", m).apply();
        styleLayoutButtons();
        if (!shownGroups.isEmpty()) showSections(shownTitles, shownGroups);
        layoutButtons.get(m).requestFocus();
    }

    // ---------------------------------------------------------------- choosing what to do

    /** What to do with the chosen item on the chosen player. */
    void chooseAction(MItem it) {
        if (playerId.isEmpty()) {
            choosePlayer();
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        names.add("Riproduci ora su " + playerName);
        modes.add("replace");
        names.add("Riproduci dopo il brano attuale");
        modes.add("next");
        names.add("Aggiungi alla coda");
        modes.add("add");
        if (!it.type.equals("radio")) {
            names.add("Radio da questo (brani simili)");
            modes.add("radio");
        }
        Clicks.dialog(this).setTitle(it.name).setItems(names.toArray(new String[0]), (d, w) -> play(it, modes.get(w))).show();
    }

    void play(MItem it, String mode) {
        boolean radio = mode.equals("radio");
        toast((radio ? "Radio: " : mode.equals("replace") ? "In riproduzione: " : "Aggiunto: ") + it.name);
        net.execute(() -> {
            try {
                JSONObject d = new JSONObject().put("entity_id", playerId).put("media_id", it.uri)
                        .put("enqueue", radio ? "replace" : mode).put("radio_mode", radio);
                HomeAssistant.request(this, "POST", "/api/services/music_assistant/play_media", d.toString());
            } catch (Exception e) {
                runOnUiThread(() -> toast("Non riesco a riprodurre: " + e.getMessage()));
            }
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                // go on
            }
            runOnUiThread(poll);
        });
    }

    // ---------------------------------------------------------------- pictures

    /** The services host their covers at fixed sizes: ask for a sharp one (not a tiny thumbnail). */
    static String sharp(String url, int minWidth) {
        int size = minWidth > 700 ? 1200 : 800;
        if (url.contains("mzstatic.com")) return url.replaceAll("/\\d{3,4}x\\d{3,4}(\\w+)\\.(jpg|png)", "/" + size + "x" + size + "$1.$2");
        if (url.contains("/imageproxy/")) return url.replaceAll("size=\\d+", "size=" + (minWidth > 700 ? 1024 : 640));
        return url;
    }

    /** Loads artwork into a view (only if the view still shows the same item). */
    void loadImage(String url, Object tag, ImageView into, int width) {
        if (url == null || url.isEmpty()) return;
        String u = url.startsWith("/") ? HomeAssistant.url(this) + url : sharp(url, width);
        net.execute(() -> {
            Bitmap bmp = bitmaps.get(u + "@" + width);
            if (bmp == null) {
                try {
                    bmp = Suggestions.image(this, u, "", width);
                } catch (Exception ignored) {
                    // no cover
                }
                if (bmp != null) bitmaps.put(u + "@" + width, bmp);
            }
            Bitmap done = bmp;
            if (done != null) runOnUiThread(() -> {
                if (into.getTag() == tag) into.setImageBitmap(done);
            });
        });
    }

    // ---------------------------------------------------------------- now playing strip

    View nowPlayingStrip() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setBackgroundColor(0xFF15151B);
        bar.setPadding(Ui.px(this, 40), 0, Ui.px(this, 40), 0);

        npCover = roundedImage(Ui.px(this, 7));
        bar.addView(npCover, new LinearLayout.LayoutParams(Ui.px(this, 46), Ui.px(this, 46)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        npTitle = text("Niente in riproduzione", 13.5f, "semibold", 1f);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npSub = text("", 11, "regular", 0.65f);
        npSub.setSingleLine(true);
        npSub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(npTitle);
        texts.addView(npSub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.px(this, 12);
        bar.addView(texts, tlp);

        bar.addView(iconButton("media_prev", () -> transport("media_previous_track")), btnLp(0));
        npPlay = iconButton("media_play", () -> transport("media_play_pause"));
        bar.addView(npPlay, btnLp(6));
        bar.addView(iconButton("media_next", () -> transport("media_next_track")), btnLp(6));
        bar.addView(iconButton("vol_minus", () -> volume(-0.05f)), btnLp(22));
        npVolume = text("", 11.5f, "medium", 0.85f);
        npVolume.setGravity(Gravity.CENTER);
        bar.addView(npVolume, new LinearLayout.LayoutParams(Ui.px(this, 40), -2));
        bar.addView(iconButton("vol_plus", () -> volume(0.05f)), btnLp(0));
        bar.addView(iconButton("expand", this::openFull), btnLp(22));
        return bar;
    }

    ImageView roundedImage(float r) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0x22FFFFFF);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        return iv;
    }

    LinearLayout.LayoutParams btnLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(this, 42), Ui.px(this, 34));
        lp.leftMargin = Ui.px(this, left == 0 ? 4 : left);
        return lp;
    }

    FrameLayout iconButton(String glyph, Runnable click) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setClickable(true);
        int ic = Ui.px(this, 19);
        ImageView iv = new ImageView(this);
        iv.setTag(glyph);
        iv.setImageBitmap(Glyphs.get(glyph, ic, Color.WHITE));
        b.addView(iv, new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));
        b.setBackground(rounded(0x26FFFFFF, Ui.px(this, 40)));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(rounded(has ? 0xFFF4F4F6 : 0x26FFFFFF, Ui.px(this, 40)));
            iv.setImageBitmap(Glyphs.get((String) iv.getTag(), ic, has ? 0xFF111114 : Color.WHITE));
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(Motion.d(140)).start();
        });
        b.setOnClickListener(v -> click.run());
        return b;
    }

    void setPlayGlyph(FrameLayout b, boolean playing) {
        if (b == null) return;
        ImageView iv = (ImageView) b.getChildAt(0);
        String g = playing ? "media_pause" : "media_play";
        if (!g.equals(iv.getTag())) {
            iv.setTag(g);
            iv.setImageBitmap(Glyphs.get(g, Ui.px(this, 19), b.isFocused() ? 0xFF111114 : Color.WHITE));
        }
    }

    void transport(String service) {
        if (playerId.isEmpty()) {
            choosePlayer();
            return;
        }
        net.execute(() -> {
            try {
                HomeAssistant.request(this, "POST", "/api/services/media_player/" + service,
                        new JSONObject().put("entity_id", playerId).toString());
            } catch (Exception ignored) {
                // the refresh shows the real state
            }
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                // go on
            }
            runOnUiThread(poll);
        });
    }

    void volume(float delta) {
        if (playerId.isEmpty()) return;
        float v = Math.max(0f, Math.min(1f, (npVol < 0 ? 0.1f : npVol) + delta));
        npVol = v;
        showVolume();
        net.execute(() -> {
            try {
                HomeAssistant.request(this, "POST", "/api/services/media_player/volume_set",
                        new JSONObject().put("entity_id", playerId).put("volume_level", Math.round(v * 100) / 100.0).toString());
            } catch (Exception ignored) {
                // the next refresh shows the real volume
            }
        });
    }

    void showVolume() {
        String s = npVol < 0 ? "" : Math.round(npVol * 100) + "%";
        npVolume.setText(s);
        if (fullVolume != null) fullVolume.setText(s);
    }

    // ---------------------------------------------------------------- state polling

    static long parseTime(String s) {
        try {
            String t = s.replaceAll("\\.\\d+", "");
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(t).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Reads the chosen player's state every few seconds while the screen is open. */
    final Runnable poll = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (demo) return;
            if (!alive || playerId.isEmpty() || !HomeAssistant.configured(MusicActivity.this)) {
                if (alive) handler.postDelayed(this, 4000);
                return;
            }
            String id = playerId;
            net.execute(() -> {
                try {
                    JSONObject o = new JSONObject(HomeAssistant.request(MusicActivity.this, "GET", "/api/states/" + id, null));
                    JSONObject a = o.getJSONObject("attributes");
                    Now n = new Now();
                    n.state = o.optString("state");
                    n.playing = n.state.equals("playing");
                    n.title = a.optString("media_title", "");
                    n.artist = a.optString("media_artist", "");
                    n.album = a.optString("media_album_name", "");
                    n.picture = a.optString("entity_picture", "");
                    n.duration = a.optDouble("media_duration", 0);
                    n.position = a.optDouble("media_position", 0);
                    long at = parseTime(a.optString("media_position_updated_at", ""));
                    n.positionAt = at > 0 ? at : System.currentTimeMillis();
                    double vol = a.has("volume_level") && !a.isNull("volume_level") ? a.optDouble("volume_level") : -1;
                    runOnUiThread(() -> {
                        if (!id.equals(playerId)) return;
                        applyNow(n, (float) vol);
                    });
                } catch (Exception ignored) {
                    // try again next time
                }
            });
            if (alive) handler.postDelayed(this, 4000);
        }
    };

    void applyNow(Now n, float vol) {
        boolean pictureChanged = !n.picture.equals(now.picture);
        now.state = n.state;
        now.playing = n.playing;
        now.title = n.title;
        now.artist = n.artist;
        now.album = n.album;
        now.duration = n.duration;
        now.position = n.position;
        now.positionAt = n.positionAt;
        now.picture = n.picture;
        npVol = vol;
        npTitle.setText(n.title.isEmpty() ? (n.state.equals("off") ? playerName + " è spento" : "Niente in riproduzione") : n.title);
        npSub.setText(n.artist.isEmpty() ? playerName + "  ·  " + stateIt(n.state) : n.artist + "  ·  " + playerName);
        setPlayGlyph(npPlay, n.playing);
        if (pictureChanged) {
            npCover.setImageBitmap(null);
            Object tag = new Object();
            npCover.setTag(tag);
            loadImage(n.picture, tag, npCover, Ui.px(this, 46));
        }
        showVolume();
        if (full != null) refreshFull(pictureChanged);
    }

    // ---------------------------------------------------------------- full screen: artwork and lyrics

    void openFull() {
        if (full != null) return;
        full = new FrameLayout(this);
        full.setBackgroundColor(0xFF0B0B10);
        full.setClickable(true);
        fullBackdrop = new ImageView(this);
        fullBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fullBackdrop.setAlpha(0.55f);
        full.addView(fullBackdrop, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor(0xB30B0B10);
        full.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // Left: artwork, title, progress, controls.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setClipChildren(false);
        int lw = Ui.px(this, 330);
        fullCover = roundedImage(Ui.px(this, 14));
        left.addView(fullCover, new LinearLayout.LayoutParams(lw, lw));
        fullTitle = text("", 20, "display", 1f);
        fullTitle.setMaxLines(2);
        fullTitle.setEllipsize(TextUtils.TruncateAt.END);
        fullTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(lw, -2);
        t1.topMargin = Ui.px(this, 14);
        left.addView(fullTitle, t1);
        fullArtist = text("", 14, "medium", 0.85f);
        fullArtist.setSingleLine(true);
        fullArtist.setEllipsize(TextUtils.TruncateAt.END);
        fullArtist.setGravity(Gravity.CENTER);
        left.addView(fullArtist, new LinearLayout.LayoutParams(lw, -2));
        fullAlbum = text("", 11.5f, "regular", 0.55f);
        fullAlbum.setSingleLine(true);
        fullAlbum.setEllipsize(TextUtils.TruncateAt.END);
        fullAlbum.setGravity(Gravity.CENTER);
        left.addView(fullAlbum, new LinearLayout.LayoutParams(lw, -2));

        FrameLayout track = new FrameLayout(this);
        track.setBackground(rounded(0x33FFFFFF, Ui.px(this, 4)));
        fullFill = new View(this);
        fullFill.setBackground(rounded(0xFFFFFFFF, Ui.px(this, 4)));
        track.addView(fullFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(lw, Ui.px(this, 4));
        bl.topMargin = Ui.px(this, 14);
        left.addView(track, bl);
        fullTime = text("", 11, "regular", 0.6f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = Ui.px(this, 4);
        left.addView(fullTime, tl);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setClipChildren(false);
        controls.addView(iconButton("media_prev", () -> transport("media_previous_track")), btnLp(0));
        fullPlay = iconButton("media_play", () -> transport("media_play_pause"));
        controls.addView(fullPlay, btnLp(6));
        controls.addView(iconButton("media_next", () -> transport("media_next_track")), btnLp(6));
        controls.addView(iconButton("vol_minus", () -> volume(-0.05f)), btnLp(18));
        fullVolume = text("", 11.5f, "medium", 0.85f);
        fullVolume.setGravity(Gravity.CENTER);
        controls.addView(fullVolume, new LinearLayout.LayoutParams(Ui.px(this, 40), -2));
        controls.addView(iconButton("vol_plus", () -> volume(0.05f)), btnLp(0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = Ui.px(this, 6);
        left.addView(controls, cl);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(lw, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        llp.leftMargin = Ui.px(this, 60);
        full.addView(left, llp);

        // Right: the lyrics.
        lyricScroll = new ScrollView(this);
        lyricScroll.setVerticalScrollBarEnabled(false);
        lyricScroll.setFocusable(false);
        lyricBox = new LinearLayout(this);
        lyricBox.setOrientation(LinearLayout.VERTICAL);
        lyricBox.setPadding(0, Ui.px(this, 190), 0, Ui.px(this, 190));
        lyricScroll.addView(lyricBox);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(Ui.px(this, 440), -1, Gravity.END);
        rlp.rightMargin = Ui.px(this, 60);
        full.addView(lyricScroll, rlp);
        fullStatus = text("", 12, "regular", 0.5f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        slp.rightMargin = Ui.px(this, 60);
        slp.bottomMargin = Ui.px(this, 16);
        full.addView(fullStatus, slp);

        root.addView(full, new FrameLayout.LayoutParams(-1, -1));
        full.setAlpha(0f);
        full.animate().alpha(1f).setDuration(Motion.d(200)).start();
        fullPlay.requestFocus();
        fullPictureKey = "";
        lyricKey = "";
        refreshFull(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void closeFull() {
        if (full == null) return;
        handler.removeCallbacks(tick);
        root.removeView(full);
        full = null;
        fullVolume = null;
        if (!cards.isEmpty()) cards.get(0).requestFocus();
    }

    /** Fills the full-screen view from the current state (and starts the lyrics for a new song). */
    void refreshFull(boolean pictureChanged) {
        if (full == null) return;
        fullTitle.setText(now.title.isEmpty() ? "Niente in riproduzione" : now.title);
        fullArtist.setText(now.artist);
        fullAlbum.setText(now.album);
        setPlayGlyph(fullPlay, now.playing);
        showVolume();
        if (pictureChanged || !now.picture.equals(fullPictureKey)) {
            fullPictureKey = now.picture;
            fullCover.setImageBitmap(null);
            Object tag = new Object();
            fullCover.setTag(tag);
            loadImage(now.picture, tag, fullCover, Ui.px(this, 330));
            fullBackdrop.setImageBitmap(null);
            Object tag2 = new Object();
            fullBackdrop.setTag(tag2);
            loadImage(now.picture, tag2, fullBackdrop, Ui.px(this, 480));
        }
        String key = now.artist + "|" + now.title;
        if (!key.equals(lyricKey)) {
            lyricKey = key;
            loadLyrics(now.artist, now.title, (int) Math.round(now.duration));
        }
    }

    /** Position now, estimated from the last reading while it plays. */
    double position() {
        double p = now.position + (now.playing ? (System.currentTimeMillis() - now.positionAt) / 1000.0 : 0);
        return now.duration > 0 ? Math.min(p, now.duration) : p;
    }

    static String clock(double s) {
        int t = (int) Math.max(0, s);
        return String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }

    /** Twice a second: progress bar, time, and the lyric line being sung. */
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (full == null || !alive) return;
            double p = position();
            if (now.duration > 0) {
                int w = (int) (Ui.px(MusicActivity.this, 330) * Math.min(1.0, p / now.duration));
                ViewGroup.LayoutParams lp = fullFill.getLayoutParams();
                lp.width = w;
                fullFill.setLayoutParams(lp);
            }
            fullTime.setText(clock(p) + (now.duration > 0 ? "  /  " + clock(now.duration) : ""));
            if (lyricSynced && lyricTimes.length > 0) {
                long ms = (long) (p * 1000);
                int idx = -1;
                for (int i = 0; i < lyricTimes.length; i++) if (lyricTimes[i] <= ms) idx = i;
                if (idx != lyricNow) highlight(idx);
            }
            handler.postDelayed(this, 500);
        }
    };

    void highlight(int idx) {
        lyricNow = idx;
        for (int i = 0; i < lyricViews.length; i++) {
            boolean on = i == idx;
            lyricViews[i].setAlpha(on ? 1f : (i < idx ? 0.3f : 0.5f));
            lyricViews[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (on ? 22 : 18) * Ui.unit(this));
        }
        if (idx >= 0 && idx < lyricViews.length) {
            TextView v = lyricViews[idx];
            v.post(() -> {
                int target = v.getTop() + v.getHeight() / 2 - lyricScroll.getHeight() / 2;
                lyricScroll.smoothScrollTo(0, Math.max(0, target));
            });
        }
    }

    void showLyricMessage(String s) {
        lyricBox.setPadding(0, Ui.px(this, 190), 0, Ui.px(this, 190));
        lyricBox.removeAllViews();
        lyricViews = new TextView[0];
        lyricTimes = new long[0];
        lyricSynced = false;
        lyricNow = -1;
        lyricBox.addView(text(s, 16, "regular", 0.6f));
    }

    void loadLyrics(String artist, String title, int duration) {
        if (title.isEmpty()) {
            showLyricMessage("");
            fullStatus.setText("");
            return;
        }
        showLyricMessage("Cerco il testo…");
        fullStatus.setText("");
        final String key = lyricKey;
        net.execute(() -> {
            String synced = "", plain = "";
            try {
                // The closest match by length among the results for this artist and title.
                String q = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(title, "UTF-8")
                        + "&artist_name=" + URLEncoder.encode(artist, "UTF-8");
                String body = get(q);
                if (body != null) {
                    JSONArray arr = new JSONArray(body);
                    JSONObject best = null;
                    double bestDiff = 1e9;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        boolean has = !o.isNull("syncedLyrics") && !o.optString("syncedLyrics", "").isEmpty();
                        double diff = Math.abs(o.optDouble("duration", 0) - duration) + (has ? 0 : 1000);
                        if (best == null || diff < bestDiff) {
                            best = o;
                            bestDiff = diff;
                        }
                    }
                    if (best != null) {
                        synced = best.isNull("syncedLyrics") ? "" : best.optString("syncedLyrics", "");
                        plain = best.isNull("plainLyrics") ? "" : best.optString("plainLyrics", "");
                    }
                }
            } catch (Exception ignored) {
                // no lyrics
            }
            String s = synced, p = plain;
            runOnUiThread(() -> {
                if (!key.equals(lyricKey) || full == null) return;
                if (!s.isEmpty()) buildSynced(s);
                else if (!p.isEmpty()) buildPlain(p);
                else showLyricMessage("Testo non disponibile");
                fullStatus.setText(s.isEmpty() && p.isEmpty() ? "" : "Testi: LRCLIB");
            });
        });
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "HumeLauncher (Android TV)");
        try {
            if (con.getResponseCode() == 404) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }

    /** Timed lyrics ("[01:23.45] line"): each line lights up as it is sung. */
    void buildSynced(String lrc) {
        List<Long> times = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Pattern tag = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
        for (String line : lrc.split("\n")) {
            Matcher m = tag.matcher(line);
            List<Long> ts = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                ts.add((long) (Integer.parseInt(m.group(1)) * 60000L + Double.parseDouble(m.group(2)) * 1000));
                end = m.end();
            }
            String text = line.substring(end).trim();
            for (long t : ts) {
                times.add(t);
                lines.add(text.isEmpty() ? "♪" : text);
            }
        }
        Integer[] order = new Integer[times.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(times.get(a), times.get(b)));
        lyricBox.setPadding(0, Ui.px(this, 190), 0, Ui.px(this, 190));
        lyricBox.removeAllViews();
        lyricViews = new TextView[order.length];
        lyricTimes = new long[order.length];
        for (int i = 0; i < order.length; i++) {
            TextView t = text(lines.get(order[i]), 18, "semibold", 0.5f);
            t.setLineSpacing(0, 1.1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.px(this, 14);
            lyricBox.addView(t, lp);
            lyricViews[i] = t;
            lyricTimes[i] = times.get(order[i]);
        }
        lyricSynced = order.length > 0;
        lyricNow = -2;
        lyricScroll.scrollTo(0, 0);
    }

    /** Lyrics without timing: plain text you can scroll with Up and Down. */
    void buildPlain(String plain) {
        lyricBox.removeAllViews();
        lyricViews = new TextView[0];
        lyricTimes = new long[0];
        lyricSynced = false;
        lyricNow = -1;
        lyricBox.setPadding(0, Ui.px(this, 40), 0, Ui.px(this, 60));
        TextView t = text(plain, 16, "medium", 0.9f);
        t.setLineSpacing(0, 1.25f);
        lyricBox.addView(t);
        lyricScroll.scrollTo(0, 0);
    }

    // ---------------------------------------------------------------- keys and bits

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (full != null) closeFull();
        else super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            switch (ev.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    transport("media_play_pause");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    transport("media_next_track");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    transport("media_previous_track");
                    return true;
                default:
                    break;
            }
            // Plain lyrics scroll with Up / Down (there is nothing above or below to focus).
            if (full != null && !lyricSynced && lyricScroll != null
                    && (ev.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || ev.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)) {
                lyricScroll.smoothScrollBy(0, (ev.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) * Ui.px(this, 120));
                return true;
            }
        }
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev) || Clicks.moveFocus(getWindow(), ev);
    }

    GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        g.setColor(color);
        return g;
    }

    GradientDrawable tabBg(boolean selected, boolean focused) {
        return rounded(focused ? 0xFFF4F4F6 : selected ? 0x66FFFFFF : 0x33FFFFFF, Ui.px(this, 40));
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

    /** A glass-style pill button: white when focused (the selected section stays lighter). */
    TextView pill(String label, View.OnClickListener click) {
        TextView t = text(label, 12, "semibold", 1f);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        int ph = Ui.px(this, 14), pv = Ui.px(this, 7);
        t.setPadding(ph, pv, ph, pv);
        t.setBackground(tabBg(false, false));
        t.setOnFocusChangeListener((v, has) -> {
            int i = tabViews.indexOf(t);
            t.setBackground(tabBg(i >= 0 && i == tab && !searching, has));
            t.setTextColor(has ? 0xFF111114 : Color.WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(Motion.d(140)).start();
        });
        t.setOnClickListener(click);
        return t;
    }
}
