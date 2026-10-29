package com.petrobot.hume;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The clock opened from the status row: a large time and date, this month's calendar (days with
 * something on them carry a dot) and the coming events, from the calendars chosen in Settings
 * → Home Assistant. Without Home Assistant it is just the clock and the month.
 */
public class CalendarActivity extends Activity {
    static final int[] PALETTE = {0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF453A, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFFD60A, 0xFFFF6482};
    static final int DAYS_AHEAD = 14, MAX_EVENTS = 40;

    static final class Ev {
        long start, end;
        boolean allDay;
        String title = "", where = "", cal = "";
        int color;
    }

    FrameLayout root;
    TextView dateView, status;
    MonthView month;
    ScrollView listScroll;
    LinearLayout list;
    final Handler handler = new Handler();
    final List<Ev> events = new ArrayList<>();
    boolean loading;
    /** Months away from the current one (0 = this month), and what each month holds. */
    int shift;
    TextView[] monthBtns;
    final java.util.Map<Integer, List<Ev>> monthCache = new java.util.HashMap<>();
    int monthLoadId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Clicks.init(this);
        Motion.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B10);
        setContentView(root);
        Clicks.quiet(root);

        // Left: time, date, month.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextColor(Color.WHITE);
        time.setTypeface(Ui.font(this, "display"));
        Ui.textSize(time, 78);
        time.setIncludeFontPadding(false);
        left.addView(time);
        dateView = text("", 20, "medium", 0.9f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = Ui.px(this, 4);
        left.addView(dateView, dlp);
        // Month controls: previous, back to today, next.
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setClipChildren(false);
        TextView prev = pill("\u2039", v -> shiftMonth(-1)), todayBtn = pill("Oggi", v -> shiftMonth(0)),
                next = pill("\u203a", v -> shiftMonth(1));
        for (TextView nb : new TextView[]{prev, todayBtn, next}) {
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.rightMargin = Ui.px(this, 8);
            nav.addView(nb, nlp);
        }
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-2, -2);
        navLp.topMargin = Ui.px(this, 22);
        nav.setPadding(Ui.px(this, 6), Ui.px(this, 6), Ui.px(this, 6), Ui.px(this, 6));  // room for the focus zoom
        left.addView(nav, navLp);
        left.setClipChildren(false);
        monthBtns = new TextView[]{prev, todayBtn, next};
        month = new MonthView(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.px(this, 340), Ui.px(this, 250));
        mlp.topMargin = Ui.px(this, 10);
        left.addView(month, mlp);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(Ui.px(this, 340), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        llp.leftMargin = Ui.px(this, 64);
        root.addView(left, llp);

        // Right: coming events.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(text("Prossimi eventi", 22, "display", 1f));
        status = text("", 12, "regular", 0.55f);
        right.addView(status);
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setFocusable(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ui.px(this, 14);
        right.addView(listScroll, slp);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(Ui.px(this, 470), -1, Gravity.END);
        rlp.rightMargin = Ui.px(this, 60);
        rlp.topMargin = Ui.px(this, 48);
        rlp.bottomMargin = Ui.px(this, 40);
        root.addView(right, rlp);

        // A divider between the two halves.
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(Ui.px(this, 1), -1, Gravity.START);
        vlp.topMargin = Ui.px(this, 60);
        vlp.bottomMargin = Ui.px(this, 60);
        vlp.leftMargin = Ui.px(this, 417);  // between the month and the list
        root.addView(line, vlp);

        refreshDate();
        load();
        monthBtns[1].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDate();
        handler.postDelayed(minuteTick, 30000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(minuteTick);
        super.onPause();
    }

    /** Keeps the date (and the highlighted day) right across midnight. */
    final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            refreshDate();
            handler.postDelayed(this, 30000);
        }
    };

    void refreshDate() {
        String d = new SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(new Date());
        dateView.setText(d.substring(0, 1).toUpperCase(Locale.ITALY) + d.substring(1));
        month.invalidate();
    }

    // ---------------------------------------------------------------- data

    static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** "2026-10-31T10:10:00+01:00" or a date "2026-10-31" (all day, local midnight). */
    static long parse(JSONObject o, boolean[] allDay) {
        try {
            String dt = o.optString("dateTime", "");
            if (!dt.isEmpty()) {
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(dt.replaceAll("\\.\\d+", "")).getTime();
            }
            allDay[0] = true;
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(o.getString("date")).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** First instant of the month {@code months} away from now. */
    static long monthStart(int months) {
        Calendar from = Calendar.getInstance();
        from.set(Calendar.DAY_OF_MONTH, 1);
        from.set(Calendar.HOUR_OF_DAY, 0);
        from.set(Calendar.MINUTE, 0);
        from.set(Calendar.SECOND, 0);
        from.set(Calendar.MILLISECOND, 0);
        from.add(Calendar.MONTH, months);
        return from.getTimeInMillis();
    }

    /** Events of every chosen calendar between two instants (blocking; call off the UI thread). */
    static List<Ev> fetch(Context c, long fromMs, long endMs) throws Exception {
        List<Ev> found = new ArrayList<>();
        int idx = 0;
        for (String[] cal : HomeAssistant.calendars(c)) {
            if (!HomeAssistant.calendarOn(c, cal[0], cal[1])) continue;
            int color = PALETTE[idx++ % PALETTE.length];
            try {
                JSONArray a = HomeAssistant.calendarEvents(c, cal[0], utc(fromMs), utc(endMs));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Ev e = new Ev();
                    boolean[] all = {false};
                    e.start = parse(o.getJSONObject("start"), all);
                    e.allDay = all[0];
                    boolean[] ignore = {false};
                    e.end = parse(o.getJSONObject("end"), ignore);
                    e.title = o.optString("summary", "");
                    e.where = o.isNull("location") ? "" : o.optString("location", "");
                    e.cal = cal[1];
                    e.color = color;
                    if (e.start > 0) found.add(e);
                }
            } catch (Exception ignored) {
                // one calendar failing does not hide the others
            }
        }
        Collections.sort(found, (x, y) -> Long.compare(x.start, y.start));
        return found;
    }

    /** Previous / next month (+1 / -1), or back to this one (0). */
    void shiftMonth(int delta) {
        shift = delta == 0 ? 0 : shift + delta;
        month.setShift(shift);
        if (shift == 0) {
            month.setEvents(events);
            renderList();
            return;
        }
        List<Ev> cached = monthCache.get(shift);
        if (cached != null) {
            month.setEvents(cached);
            renderMonthList(cached);
            return;
        }
        if (!HomeAssistant.configured(this)) {
            month.setEvents(new ArrayList<>());
            renderMonthList(new ArrayList<>());
            return;
        }
        final int id = ++monthLoadId, at = shift;
        month.setEvents(new ArrayList<>());
        list.removeAllViews();
        status.setText("Carico gli eventi\u2026");
        final Context c = getApplicationContext();
        new Thread(() -> {
            List<Ev> found = new ArrayList<>();
            try {
                found = fetch(c, monthStart(at), monthStart(at + 1));
            } catch (Exception ignored) {
                // shows an empty month
            }
            List<Ev> res = found;
            runOnUiThread(() -> {
                if (isFinishing() || id != monthLoadId) return;
                monthCache.put(at, res);
                if (shift == at) {
                    month.setEvents(res);
                    renderMonthList(res);
                }
            });
        }).start();
    }

    /** The events of another month, grouped by day. */
    void renderMonthList(List<Ev> evs) {
        list.removeAllViews();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(monthStart(shift));
        String m = new SimpleDateFormat("MMMM yyyy", Locale.ITALY).format(cal.getTime());
        status.setText(evs.isEmpty() ? "Niente in programma a " + m : evs.size() + " eventi \u00b7 " + m);
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE d MMMM", Locale.ITALY);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ITALY);
        long lastDay = -1;
        for (int i = 0; i < evs.size() && i < 80; i++) {
            Ev e = evs.get(i);
            long d = dayStart(e.start);
            if (d != lastDay) {
                lastDay = d;
                String label = dayFmt.format(new Date(d));
                TextView h = text(label.substring(0, 1).toUpperCase(Locale.ITALY) + label.substring(1), 14, "semibold", 0.9f);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
                hlp.topMargin = Ui.px(this, i == 0 ? 0 : 14);
                hlp.bottomMargin = Ui.px(this, 6);
                list.addView(h, hlp);
            }
            list.addView(eventRow(e, e.allDay ? "Tutto il giorno" : hm.format(new Date(e.start))
                    + (e.end > e.start ? " \u2013 " + hm.format(new Date(e.end)) : "")));
        }
        listScroll.scrollTo(0, 0);
    }

    void load() {
        if (!HomeAssistant.configured(this)) {
            status.setText("Collega Home Assistant (Impostazioni \u2192 Home Assistant) per vedere gli eventi.");
            return;
        }
        if (loading) return;
        loading = true;
        status.setText("Carico gli eventi\u2026");
        final Context c = getApplicationContext();
        new Thread(() -> {
            List<Ev> found = new ArrayList<>();
            String err = null;
            try {
                found = fetch(c, monthStart(0), System.currentTimeMillis() + 45L * 86400000L);
            } catch (Exception e) {
                err = e.getMessage();
            }
            List<Ev> done = found;
            String error = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing()) return;
                if (error != null) status.setText("Non riesco a leggere i calendari: " + error);
                events.clear();
                events.addAll(done);
                if (shift == 0) {
                    month.setEvents(events);
                    renderList();
                }
            });
        }).start();
    }

    // ---------------------------------------------------------------- list

    static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    void renderList() {
        list.removeAllViews();
        long now = System.currentTimeMillis(), today = dayStart(now), horizon = today + DAYS_AHEAD * 86400000L;
        List<Ev> coming = new ArrayList<>();
        for (Ev e : events) {
            boolean upcoming = e.allDay ? e.start >= today : (e.end > now || e.start >= now);
            if (upcoming && e.start < horizon) coming.add(e);
        }
        if (coming.isEmpty()) {
            status.setText(HomeAssistant.configured(this) ? "Niente in programma nei prossimi " + DAYS_AHEAD + " giorni." : status.getText().toString());
            return;
        }
        status.setText(coming.size() + " eventi nei prossimi " + DAYS_AHEAD + " giorni");
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE d MMMM", Locale.ITALY);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ITALY);
        long lastDay = -1;
        for (int i = 0; i < coming.size() && i < MAX_EVENTS; i++) {
            Ev e = coming.get(i);
            long d = dayStart(e.start);
            if (d != lastDay) {
                lastDay = d;
                String label = d == today ? "Oggi" : d == today + 86400000L ? "Domani" : dayFmt.format(new Date(d));
                label = label.substring(0, 1).toUpperCase(Locale.ITALY) + label.substring(1);
                TextView h = text(label, 14, "semibold", 0.9f);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
                hlp.topMargin = Ui.px(this, i == 0 ? 0 : 14);
                hlp.bottomMargin = Ui.px(this, 6);
                list.addView(h, hlp);
            }
            list.addView(eventRow(e, e.allDay ? "Tutto il giorno" : hm.format(new Date(e.start))
                    + (e.end > e.start ? " – " + hm.format(new Date(e.end)) : "")));
        }
    }

    View eventRow(Ev e, String when) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.px(this, 10));
        bg.setColor(0x14FFFFFF);
        row.setBackground(bg);
        int pad = Ui.px(this, 10);
        row.setPadding(0, pad, pad, pad);
        View bar = new View(this);
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(Ui.px(this, 3));
        bb.setColor(e.color);
        bar.setBackground(bb);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.px(this, 4), -1);
        blp.leftMargin = Ui.px(this, 10);
        blp.rightMargin = Ui.px(this, 12);
        row.addView(bar, blp);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(e.title.isEmpty() ? "(senza titolo)" : e.title, 14, "semibold", 1f);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(t);
        String sub = when + (e.cal.isEmpty() ? "" : "  ·  " + e.cal);
        TextView s = text(sub, 11.5f, "regular", 0.65f);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(s);
        if (!e.where.isEmpty()) {
            TextView w = text(e.where.replace('\n', ' '), 11, "regular", 0.45f);
            w.setSingleLine(true);
            w.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(w);
        }
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.px(this, 6);
        row.setLayoutParams(lp);
        return row;
    }

    // ---------------------------------------------------------------- keys and bits

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            int k = ev.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_UP) {
                listScroll.smoothScrollBy(0, (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) * Ui.px(this, 130));
                return true;
            }
        }
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev);
    }

    /** A round-ended button, white when focused (like the rest of Hume). */
    TextView pill(String label, View.OnClickListener click) {
        TextView t = text(label, label.length() == 1 ? 20 : 13, "semibold", 1f);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        int ph = Ui.px(this, label.length() == 1 ? 16 : 18), pv = Ui.px(this, 5);
        t.setPadding(ph, pv, ph, pv);
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(Ui.px(this, 40));
        idle.setColor(0x40FFFFFF);
        GradientDrawable lit = new GradientDrawable();
        lit.setCornerRadius(Ui.px(this, 40));
        lit.setColor(0xFFF4F4F6);
        t.setBackground(idle);
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(has ? lit : idle);
            t.setTextColor(has ? 0xFF111114 : Color.WHITE);
        });
        t.setOnClickListener(click);
        return t;
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

    /** This month: weekday letters, days (today in a white disc) and a dot under days with events. */
    static final class MonthView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Set<Integer> busy = new HashSet<>();   // day of month
        int shift;

        void setShift(int months) {
            shift = months;
            busy.clear();
            invalidate();
        }

        Calendar shown() {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.MONTH, shift);
            return c;
        }

        MonthView(Context c) {
            super(c);
        }

        void setEvents(List<Ev> events) {
            busy.clear();
            Calendar now = shown(), c = Calendar.getInstance();
            for (Ev e : events) {
                c.setTimeInMillis(e.start);
                if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)) {
                    busy.add(c.get(Calendar.DAY_OF_MONTH));
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float u = Ui.unit(getContext()), w = getWidth(), colW = w / 7f;
            Calendar now = shown();
            int today = shift == 0 ? Calendar.getInstance().get(Calendar.DAY_OF_MONTH) : -1;
            Calendar first = (Calendar) now.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday first
            int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);

            String title = new SimpleDateFormat("MMMM yyyy", Locale.ITALY).format(now.getTime());
            title = title.substring(0, 1).toUpperCase(Locale.ITALY) + title.substring(1);
            p.setTypeface(Ui.font(getContext(), "semibold"));
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Color.WHITE);
            p.setTextSize(15 * u);
            cv.drawText(title, 2 * u, 16 * u, p);

            String[] heads = {"L", "M", "M", "G", "V", "S", "D"};
            p.setTypeface(Ui.font(getContext(), "medium"));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(11 * u);
            p.setColor(0x80FFFFFF);
            for (int i = 0; i < 7; i++) cv.drawText(heads[i], colW * (i + 0.5f), 44 * u, p);

            float rowH = 32 * u, top = 52 * u;
            p.setTextSize(14 * u);
            for (int d = 1; d <= days; d++) {
                int cell = offset + d - 1, col = cell % 7, row = cell / 7;
                float cx = colW * (col + 0.5f), cy = top + rowH * row + rowH / 2f;
                if (d == today) {
                    p.setColor(Color.WHITE);
                    cv.drawCircle(cx, cy - 2 * u, 13 * u, p);
                    p.setColor(0xFF111114);
                } else {
                    p.setColor(col >= 5 ? 0xB3FFFFFF : Color.WHITE);
                }
                cv.drawText(String.valueOf(d), cx, cy + 4 * u, p);
                if (busy.contains(d)) {
                    p.setColor(d == today ? 0xFF0A84FF : 0xFFFF9F0A);
                    cv.drawCircle(cx, cy + 14 * u, 2.2f * u, p);
                }
            }
        }
    }
}
