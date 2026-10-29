package com.petrobot.hume;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * TV inputs (HDMI, AV, component, tuner) from Android's TV input framework, and switching to
 * one the way the stock launcher does: VIEW the input's passthrough "channel" so the TV app
 * shows it. Boxes without inputs (e.g. a Mi Box) report none, and the Inputs card and button hide.
 */
final class Inputs {
    static final String K_CARD = "inputs_card", K_BUTTON = "inputs_button", K_DIRECT = "direct_id", K_DIRECT_NAME = "direct_name";

    /** Icons you can give a source: key, name, and the line glyph it uses where no picture fits. */
    static final String[] ICON_KEYS = {"auto", "wiiu", "console", "hdmi", "tv", "usb", "av", "input"};
    static final String[] ICON_NAMES = {"Automatic", "Wii U logo", "Game console", "HDMI", "TV", "USB", "AV", "Input"};
    static final String[] ICON_GLYPHS = {null, "gamepad", "gamepad", "hdmi", "antenna", "usb", "av", "input"};

    private Inputs() {
    }

    static final class Input {
        String id, label;
        int type;
        boolean passthrough, disconnected, usb;
    }

    /** File/media apps that can browse a USB drive, in order of preference. */
    static final String[] USB_APPS = {"com.droidlogic.mediacenter", "com.droidlogic.FileBrower", "com.yunfang.files",
            "nextapp.fx", "com.android.documentsui"};

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("inputs", Context.MODE_PRIVATE);
    }

    static boolean showCard(Context c) {
        return prefs(c).getBoolean(K_CARD, true);
    }

    static boolean showButton(Context c) {
        return prefs(c).getBoolean(K_BUTTON, true);
    }

    static boolean isHdmi4(Input in) {
        return !in.usb && in.label.replaceAll("\\s", "").equalsIgnoreCase("HDMI4");
    }

    /**
     * The input with its own tile on Home: the one chosen in Settings (or none). Until one is
     * chosen: HDMI 4 where the Wii U is (a TV may name it "Gioco" or similar, so failing that the
     * only HDMI input with something plugged in). That first pick is remembered, so the tile stays
     * when the console is switched off. Null when there is no such input.
     */
    static Input direct(Context c) {
        List<Input> l = list(c);
        String id = prefs(c).getString(K_DIRECT, null);
        if (id != null) {
            for (Input in : l) {
                if (!in.id.equals(id)) continue;
                // The first pick was named "Wii U": give it the Wii U logo unless an icon was chosen.
                if ("Wii U".equals(prefs(c).getString(K_DIRECT_NAME, null)) && !prefs(c).contains("icon_" + in.id)) {
                    prefs(c).edit().putString("icon_" + in.id, "wiiu").apply();
                }
                return in;
            }
            return null;
        }
        Input pick = null;
        for (Input in : l) if (isHdmi4(in)) pick = in;
        if (pick == null) {
            int plugged = 0;
            for (Input in : l) {
                if (in.usb || in.type != TvInputInfo.TYPE_HDMI || in.disconnected) continue;
                pick = in;
                plugged++;
            }
            if (plugged != 1) pick = null;
        }
        if (pick != null) {
            prefs(c).edit().putString(K_DIRECT, pick.id).putString(K_DIRECT_NAME, "Wii U")
                    .putString("icon_" + pick.id, "wiiu").apply();
        }
        return pick;
    }

    /** The tile's name: chosen in Settings, else "Wii U" for HDMI 4, else the input's own name. */
    static String directName(Context c, Input in) {
        String n = prefs(c).getString(K_DIRECT_NAME, null);
        if (n != null && !n.trim().isEmpty()) return n.trim();
        return isHdmi4(in) ? "Wii U" : in.label;
    }

    /** The icon chosen for an input: a key from ICON_KEYS, or "url:<address>" for your own picture. */
    static String icon(Context c, Input in) {
        return prefs(c).getString("icon_" + in.id, "auto");
    }

    static void setIcon(Context c, Input in, String icon) {
        prefs(c).edit().putString("icon_" + in.id, icon).apply();
    }

    static String iconName(Context c, Input in) {
        String ic = icon(c, in);
        if (ic.startsWith("url:")) return "Web image";
        for (int i = 0; i < ICON_KEYS.length; i++) if (ICON_KEYS[i].equals(ic)) return ICON_NAMES[i];
        return "Automatic";
    }

    /** Line glyph for an input, honouring the chosen icon (pictures fall back to their nearest glyph). */
    static String glyph(Context c, Input in) {
        String ic = icon(c, in);
        for (int i = 0; i < ICON_KEYS.length; i++) {
            if (ICON_KEYS[i].equals(ic) && ICON_GLYPHS[i] != null) return ICON_GLYPHS[i];
        }
        if (ic.startsWith("url:")) return "gamepad";
        return glyph(in);
    }

    /** A full-colour picture for the input (the Wii U logo, or your web image once downloaded); null otherwise. */
    static android.graphics.Bitmap picture(Context c, Input in) {
        String ic = icon(c, in);
        try {
            if (ic.equals("wiiu")) {
                try (java.io.InputStream s = c.getAssets().open("inputs/wiiu.png")) {
                    return android.graphics.BitmapFactory.decodeStream(s);
                }
            }
            if (ic.startsWith("url:")) {
                java.io.File f = cacheFile(c, ic.substring(4));
                return f.exists() ? android.graphics.BitmapFactory.decodeFile(f.getPath()) : null;
            }
        } catch (Exception ignored) {
            // no picture
        }
        return null;
    }

    static java.io.File cacheFile(Context c, String url) {
        java.io.File d = new java.io.File(c.getCacheDir(), "inputicons");
        d.mkdirs();
        return new java.io.File(d, Integer.toHexString(url.hashCode()) + ".img");
    }

    /** Downloads a web image for an icon (in the background); {@code done} runs on the main thread. */
    static void fetchIcon(Context ctx, String url, Runnable done) {
        Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(12000);
                con.setRequestProperty("User-Agent", "HumeLauncher");
                java.io.File f = cacheFile(c, url);
                try (java.io.InputStream in = con.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                // Only keep it when it really is a picture.
                if (android.graphics.BitmapFactory.decodeFile(f.getPath()) == null) f.delete();
            } catch (Exception ignored) {
                // keep the old icon
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
        }).start();
    }

    /** Hardware inputs in a natural order: HDMI first, then AV and the rest, tuner last. */
    static List<Input> list(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tm;
        try {
            tm = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (Exception e) {
            return out;
        }
        if (tm == null) return out;
        List<TvInputInfo> infos;
        try {
            infos = tm.getTvInputList();
        } catch (Exception e) {
            return out;
        }
        for (TvInputInfo info : infos) {
            try {
                if (info.isHidden(c) || info.getParentId() != null) continue;
                if (!info.isPassthroughInput() && info.getType() != TvInputInfo.TYPE_TUNER) continue;
                // Only the TV's own hardware (HDMI, AV, built-in tuner): their ids end in "/HW<n>".
                // App-provided channel sources (Mi TV+, VLC previews...) are not inputs.
                if (!info.getId().contains("/HW")) continue;
                Input in = new Input();
                in.id = info.getId();
                in.type = info.getType();
                in.passthrough = info.isPassthroughInput();
                CharSequence custom = info.loadCustomLabel(c), label = info.loadLabel(c);
                in.label = custom != null && custom.length() > 0 ? custom.toString() : label != null ? tidy(label.toString(), in.type) : "Input";
                in.disconnected = tm.getInputState(in.id) == TvInputManager.INPUT_STATE_DISCONNECTED;
                out.add(in);
            } catch (Exception ignored) {
                // skip inputs that can't be read
            }
        }
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)) != 0 ? Integer.compare(rank(a), rank(b))
                : a.label.compareToIgnoreCase(b.label));
        // A plugged-in USB drive shows as its own source (opens the TV's file or media app).
        if (!out.isEmpty()) {
            try {
                android.os.storage.StorageManager sm = c.getSystemService(android.os.storage.StorageManager.class);
                for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                    if (!v.isRemovable() || !android.os.Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                    Input in = new Input();
                    in.id = "usb:" + v.getUuid();
                    in.usb = true;
                    String d = v.getDescription(c);
                    in.label = d == null || d.isEmpty() ? "USB" : "USB \u00b7 " + d;
                    out.add(in);
                }
            } catch (Exception ignored) {
                // no storage info
            }
        }
        return out;
    }

    private static int rank(Input in) {
        switch (in.type) {
            case TvInputInfo.TYPE_HDMI: return 0;
            case TvInputInfo.TYPE_DISPLAY_PORT: return 1;
            case TvInputInfo.TYPE_COMPONENT: return 2;
            case TvInputInfo.TYPE_COMPOSITE: return 3;
            case TvInputInfo.TYPE_TUNER: return 9;
            default: return 5;
        }
    }

    /** "HDMI1" -> "HDMI 1", "AV1" -> "AV 1"; tuners read "TV". */
    private static String tidy(String s, int type) {
        if (type == TvInputInfo.TYPE_TUNER && (s.toUpperCase().contains("TV") || s.isEmpty())) return "TV";
        return s.replaceAll("^([A-Za-z]+)(\\d+)$", "$1 $2");
    }

    /** Glyph for an input: from its type, or its name when the type is generic (e.g. "USB", "AV"). */
    static String glyph(Input in) {
        if (in.usb) return "usb";
        switch (in.type) {
            case TvInputInfo.TYPE_TUNER: return "antenna";
            case TvInputInfo.TYPE_HDMI: return "hdmi";
            case TvInputInfo.TYPE_COMPOSITE: return "av";
            case TvInputInfo.TYPE_COMPONENT: return "component";
            case TvInputInfo.TYPE_SVIDEO: return "svideo";
            case TvInputInfo.TYPE_SCART: return "scart";
            case TvInputInfo.TYPE_VGA:
            case TvInputInfo.TYPE_DVI: return "vga";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "displayport";
            default: break;
        }
        String l = in.label.toUpperCase(java.util.Locale.ROOT);
        if (l.contains("USB") || l.contains("MEDIA")) return "usb";
        if (l.contains("HDMI")) return "hdmi";
        if (l.startsWith("AV") || l.contains("COMPOSITE")) return "av";
        if (l.contains("YPBPR") || l.contains("COMPONENT")) return "component";
        if (l.contains("VGA") || l.contains("PC")) return "vga";
        if (l.contains("SCART")) return "scart";
        if (l.contains("TV") || l.contains("DTV") || l.contains("ATV")) return "antenna";
        return "input";
    }

    /** Switches the TV to the input. Returns false when no app can show it. */
    static boolean open(Context c, Input in) {
        if (in.usb) {
            for (String pkg : USB_APPS) {
                Intent l = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (l == null) l = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (l != null) {
                    c.startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                }
            }
            return false;
        }
        Uri uri = in.passthrough ? TvContract.buildChannelUriForPassthroughInput(in.id) : TvContract.buildChannelsUriForInput(in.id);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, in.passthrough ? TvContract.Channels.CONTENT_ITEM_TYPE : TvContract.Channels.CONTENT_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }
}
