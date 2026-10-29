package com.petrobot.hume;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home Assistant over its REST API (a long-lived access token on the local network): a weather
 * source for the Home weather card, and a panel of buttons (scenes, scripts, lights, switches) and
 * read-only status tiles (sensors). Everything is off until a server and token are set in
 * Settings → Home Assistant; nothing is sent anywhere else.
 */
final class HomeAssistant {
    static final String PREFS = "homeassistant";
    static final String K_URL = "url", K_TOKEN = "token", K_WEATHER = "use_weather", K_WEATHER_ENTITY = "weather_entity",
            K_ITEMS = "items";
    static final int MAX_ITEMS = 10;

    private HomeAssistant() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String url(Context c) {
        String u = prefs(c).getString(K_URL, "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static boolean configured(Context c) {
        return !url(c).isEmpty() && !prefs(c).getString(K_TOKEN, "").isEmpty();
    }

    /** Saves the address ("192.168.1.5:8123" gets http:// in front). */
    static void setUrl(Context c, String u) {
        u = u.trim();
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        prefs(c).edit().putString(K_URL, u).apply();
        Weather.changed(c);
    }

    static void setToken(Context c, String t) {
        prefs(c).edit().putString(K_TOKEN, t.trim()).apply();
        Weather.changed(c);
    }

    static void disconnect(Context c) {
        prefs(c).edit().clear().apply();
        STATES.clear();
        Weather.changed(c);
    }

    static String weatherEntity(Context c) {
        return prefs(c).getString(K_WEATHER_ENTITY, "");
    }

    /** The weather card follows Home Assistant when it is on and an entity is chosen. */
    static boolean useForWeather(Context c) {
        return configured(c) && prefs(c).getBoolean(K_WEATHER, true) && !weatherEntity(c).isEmpty();
    }

    // ---------------------------------------------------------------- panel items

    static final class Item {
        final String id, name;

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String domain() {
            return id.substring(0, Math.max(0, id.indexOf('.')));
        }
    }

    static List<Item> items(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(K_ITEMS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(o.getString("id"), o.optString("name", o.getString("id"))));
            }
        } catch (Exception ignored) {
            // no items
        }
        return out;
    }

    static void setItems(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item x : items) a.put(new JSONObject().put("id", x.id).put("name", x.name));
        } catch (Exception ignored) {
            // keep what was built
        }
        prefs(c).edit().putString(K_ITEMS, a.toString()).apply();
    }

    /** Scenes, scripts and buttons run once; lights, switches and covers toggle; anything else is a status tile. */
    static boolean isAction(String domain) {
        switch (domain) {
            case "scene": case "script": case "button": case "input_button":
            case "light": case "switch": case "input_boolean": case "fan": case "cover":
                return true;
            default:
                return false;
        }
    }

    static boolean isToggle(String domain) {
        return domain.equals("light") || domain.equals("switch") || domain.equals("input_boolean")
                || domain.equals("fan") || domain.equals("cover");
    }

    static String glyph(String domain) {
        switch (domain) {
            case "light": return "bulb";
            case "switch": case "input_boolean": case "fan": case "cover": return "controls";
            case "scene": case "script": case "button": case "input_button": return "present";
            case "sensor": case "binary_sensor": return "sensor";
            default: return "house";
        }
    }

    // ---------------------------------------------------------------- state cache

    /** Last known state of each entity: {state, unit, friendly name}. */
    static final Map<String, String[]> STATES = new HashMap<>();

    /** What a tile shows under its name. */
    static String status(Item it) {
        String[] s = STATES.get(it.id);
        if (s == null) return it.domain().equals("scene") || it.domain().equals("script") ? "Run" : "…";
        String d = it.domain();
        if (d.equals("scene") || d.equals("script") || d.equals("button") || d.equals("input_button")) return "Run";
        String v = s[0];
        if (v.equals("unavailable") || v.equals("unknown")) return "Unavailable";
        if (v.equals("on")) return d.equals("binary_sensor") ? "Detected" : "On";
        if (v.equals("off")) return d.equals("binary_sensor") ? "Clear" : "Off";
        return v + (s[1].isEmpty() ? "" : " " + s[1]);
    }

    interface Done {
        void run();
    }

    /** Reads the states of the panel's entities in the background, then runs {@code done} on the main thread. */
    static void refreshStates(Context ctx, List<Item> items, Done done) {
        Context c = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            for (Item it : items) {
                try {
                    JSONObject o = new JSONObject(request(c, "GET", "/api/states/" + it.id, null));
                    STATES.put(it.id, new String[]{o.optString("state"),
                            o.getJSONObject("attributes").optString("unit_of_measurement", ""),
                            o.getJSONObject("attributes").optString("friendly_name", it.name)});
                } catch (Exception ignored) {
                    // leave the old state
                }
            }
            if (done != null) main.post(done::run);
        }).start();
    }

    /** Runs a scene or script, presses a button, or toggles a light or switch. Optimistic: flips the cached state first. */
    static void activate(Context ctx, Item it, Done done) {
        Context c = ctx.getApplicationContext();
        String d = it.domain();
        String svc;
        switch (d) {
            case "scene": svc = "scene/turn_on"; break;
            case "script": svc = "script/turn_on"; break;
            case "button": svc = "button/press"; break;
            case "input_button": svc = "input_button/press"; break;
            case "cover": svc = "cover/toggle"; break;
            default:
                if (!isToggle(d)) {
                    refreshStates(c, java.util.Collections.singletonList(it), done);
                    return;
                }
                svc = d + "/toggle";
        }
        String[] st = STATES.get(it.id);
        if (isToggle(d) && st != null && (st[0].equals("on") || st[0].equals("off"))) {
            st[0] = st[0].equals("on") ? "off" : "on";
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                request(c, "POST", "/api/services/" + svc, new JSONObject().put("entity_id", it.id).toString());
                Thread.sleep(700);
            } catch (Exception ignored) {
                // the refresh below shows the real state
            }
            refreshStates(c, java.util.Collections.singletonList(it), done);
        }).start();
        if (done != null) main.post(done::run);
    }

    // ---------------------------------------------------------------- entities for pickers

    static final class Entity {
        String id, name, group;
    }

    /** All entities a person might want on the panel, or weather entities, sorted by name. */
    static List<Entity> entities(Context c) throws Exception {
        JSONArray all = new JSONArray(request(c, "GET", "/api/states", null));
        List<Entity> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            Entity e = new Entity();
            e.id = o.getString("entity_id");
            e.name = o.getJSONObject("attributes").optString("friendly_name", e.id);
            String d = e.id.substring(0, e.id.indexOf('.'));
            switch (d) {
                case "scene": case "script": case "button": case "input_button": e.group = "actions"; break;
                case "light": case "switch": case "input_boolean": case "fan": case "cover": e.group = "devices"; break;
                case "sensor": case "binary_sensor": case "climate": case "lock": case "person": e.group = "status"; break;
                case "weather": e.group = "weather"; break;
                default: continue;
            }
            out.add(e);
        }
        java.util.Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** "Connected, Home Assistant 2025.1.0" or an error message. */
    static String test(Context c) {
        try {
            JSONObject o = new JSONObject(request(c, "GET", "/api/config", null));
            return "Connected to " + o.optString("location_name", "Home Assistant") + " (" + o.optString("version") + ")";
        } catch (Exception e) {
            return "Can't connect: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- weather

    /** The chosen weather entity in the launcher's own forecast format (current, hourly and daily). */
    static Weather.Data fetchWeather(Context c, boolean f) throws Exception {
        String id = weatherEntity(c);
        JSONObject st = new JSONObject(request(c, "GET", "/api/states/" + id, null));
        JSONObject a = st.getJSONObject("attributes");
        Weather.Data d = new Weather.Data();
        d.source = "ha";
        d.place = a.optString("friendly_name", "Home");
        d.windUnit = f ? "mph" : "km/h";
        String cond = st.optString("state");
        d.code = wmo(cond);
        d.day = !cond.equals("clear-night");
        if (d.day) {
            try {
                d.day = !new JSONObject(request(c, "GET", "/api/states/sun.sun", null)).optString("state").equals("below_horizon");
            } catch (Exception ignored) {
                // keep the guess
            }
        }
        boolean srcF = a.optString("temperature_unit", "°C").contains("F");
        d.temp = temp(a.optDouble("temperature", 0), srcF, f);
        d.feels = a.has("apparent_temperature") ? temp(a.optDouble("apparent_temperature"), srcF, f) : d.temp;
        d.humidity = (int) Math.round(a.optDouble("humidity", 0));
        d.wind = wind(a.optDouble("wind_speed", 0), a.optString("wind_speed_unit", "km/h"), f);
        try {
            readForecast(c, id, "hourly", a, srcF, f, d);
        } catch (Exception ignored) {
            // current conditions only
        }
        try {
            readForecast(c, id, "daily", a, srcF, f, d);
        } catch (Exception ignored) {
            // current conditions only
        }
        return d;
    }

    private static void readForecast(Context c, String id, String type, JSONObject attrs, boolean srcF, boolean f,
                                     Weather.Data d) throws Exception {
        JSONArray fc;
        try {
            JSONObject r = new JSONObject(request(c, "POST", "/api/services/weather/get_forecasts?return_response",
                    new JSONObject().put("entity_id", id).put("type", type).toString()));
            fc = r.getJSONObject("service_response").getJSONObject(id).getJSONArray("forecast");
        } catch (Exception e) {
            // Older Home Assistant keeps the forecast in the entity's attributes (daily only).
            if (!type.equals("daily") || !attrs.has("forecast")) throw e;
            fc = attrs.getJSONArray("forecast");
        }
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        // Some integrations send local times with no offset ("2026-10-29T14:00:00").
        SimpleDateFormat naive = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        for (int i = 0; i < fc.length(); i++) {
            JSONObject o = fc.getJSONObject(i);
            String when = o.getString("datetime");
            if (type.equals("hourly")) {
                Weather.Hour h = new Weather.Hour();
                String w = when.replaceAll("\\.\\d+", "");
                boolean offset = w.endsWith("Z") || w.matches(".*[+-]\\d\\d:?\\d\\d$");
                h.time = (offset ? iso : naive).parse(w).getTime();
                h.temp = temp(o.optDouble("temperature", 0), srcF, f);
                String cond = o.optString("condition");
                h.code = wmo(cond);
                int hr = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(new Date(h.time)));
                h.day = !cond.equals("clear-night") && hr >= 6 && hr < 19;
                d.hours.add(h);
            } else {
                Weather.Day x = new Weather.Day();
                x.date = when.substring(0, 10);
                x.code = wmo(o.optString("condition"));
                x.max = temp(o.optDouble("temperature", 0), srcF, f);
                x.min = o.has("templow") ? temp(o.optDouble("templow"), srcF, f) : x.max;
                x.rain = o.has("precipitation_probability") && !o.isNull("precipitation_probability")
                        ? o.optInt("precipitation_probability") : -1;
                d.days.add(x);
            }
        }
    }

    /** Home Assistant weather condition → WMO weather code (what the icons and wording use). */
    static int wmo(String cond) {
        switch (cond) {
            case "sunny": case "clear-night": return 0;
            case "partlycloudy": case "windy": case "windy-variant": return 2;
            case "fog": return 45;
            case "rainy": return 61;
            case "pouring": return 65;
            case "snowy": return 71;
            case "snowy-rainy": return 67;
            case "lightning": case "lightning-rainy": return 95;
            case "hail": return 96;
            default: return 3;  // cloudy, exceptional, unknown
        }
    }

    private static int temp(double v, boolean srcF, boolean wantF) {
        if (srcF == wantF) return (int) Math.round(v);
        return (int) Math.round(wantF ? v * 9 / 5 + 32 : (v - 32) * 5 / 9);
    }

    private static int wind(double v, String unit, boolean wantMph) {
        double kmh;
        switch (unit) {
            case "m/s": kmh = v * 3.6; break;
            case "mph": kmh = v * 1.609; break;
            case "kn": kmh = v * 1.852; break;
            default: kmh = v;
        }
        return (int) Math.round(wantMph ? kmh / 1.609 : kmh);
    }

    // ---------------------------------------------------------------- http

    static String request(Context c, String method, String path, String body) throws Exception {
        SharedPreferences p = prefs(c);
        String base = url(c);
        if (base.isEmpty()) throw new Exception("No server set");
        HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(10000);
        con.setRequestMethod(method);
        con.setRequestProperty("Authorization", "Bearer " + p.getString(K_TOKEN, ""));
        con.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = con.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
            }
            int code = con.getResponseCode();
            if (code == 401) throw new Exception("token refused");
            if (code >= 400) throw new Exception("HTTP " + code);
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
}
