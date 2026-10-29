package com.petrobot.hume;

import android.content.Context;
import android.content.SharedPreferences;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * News for the top shelf from Indian newsrooms' RSS feeds, which carry a large image per story
 * (free, no key): India, Local (the city from the internet address or the city chosen for
 * weather) and World stories mixed together. Each story becomes a carousel item \u2014 headline, summary, image,
 * section, source and age (no button: stories are read on the shelf).
 */
final class News {
    static final String K_SOURCES = "news_sources";
    static final String INDIA = "india", LOCAL = "local", WORLD = "world";
    static final String[] SECTIONS = {INDIA, LOCAL, WORLD};

    /** A newsroom and its feed for each section ({city} is the local city's slug; null = none). */
    static final class Source {
        final String id, name, india, world, local;

        Source(String id, String name, String india, String world, String local) {
            this.id = id;
            this.name = name;
            this.india = india;
            this.world = world;
            this.local = local;
        }
    }

    static final List<Source> SOURCES = new ArrayList<>();

    /** ANSA (free full text, no images in its feeds): used by the News app only, not the carousel. */
    static final Source ANSA = new Source("ansa", "ANSA",
            "https://www.ansa.it/sito/ansait_rss.xml",
            "https://www.ansa.it/sito/notizie/mondo/mondo_rss.xml",
            "https://www.ansa.it/{ansa}/notizie/{ansa}_rss.xml");
    static final String[][] ANSA_REGIONS = {{"torino", "piemonte"}, {"milano", "lombardia"}, {"roma", "lazio"},
            {"napoli", "campania"}, {"bologna", "emiliaromagna"}, {"firenze", "toscana"}, {"genova", "liguria"},
            {"bari", "puglia"}, {"palermo", "sicilia"}};

    /** Times of India city feed ids (each checked against the feed's own title). */
    static final java.util.Map<String, String> TOI_CITIES = new java.util.HashMap<>();

    /** Repubblica has a city edition at {city}.repubblica.it for these cities. */
    static final String[] REPUBBLICA_CITIES = {"torino", "milano", "roma", "napoli", "bologna", "firenze", "genova",
            "bari", "palermo"};

    static {
        String[][] ids = {{"delhi", "-2128839596"}, {"mumbai", "-2128838597"}, {"bengaluru", "-2128833038"},
                {"chennai", "2950623"}, {"kolkata", "-2128830821"}, {"hyderabad", "-2128816011"}, {"pune", "-2128821991"},
                {"ahmedabad", "-2128821153"}, {"lucknow", "-2128819658"}, {"chandigarh", "-2128816762"},
                {"jaipur", "3012544"}, {"patna", "-2128817995"}, {"noida", "8021716"}, {"gurugram", "6547154"},
                {"nagpur", "442002"}, {"thiruvananthapuram", "878156304"}};
        for (String[] e : ids) TOI_CITIES.put(e[0], e[1]);
        SOURCES.add(new Source("repubblica", "la Repubblica",
                "https://www.repubblica.it/rss/cronaca/rss2.0.xml",
                "https://www.repubblica.it/rss/esteri/rss2.0.xml",
                "https://{rep}.repubblica.it/rss/rss2.0.xml"));
        SOURCES.add(new Source("toi", "Times of India",
                "https://timesofindia.indiatimes.com/rssfeeds/-2128936835.cms",
                "https://timesofindia.indiatimes.com/rssfeeds/296589292.cms",
                "https://timesofindia.indiatimes.com/rssfeeds/{toi}.cms"));
        SOURCES.add(new Source("ht", "Hindustan Times",
                "https://www.hindustantimes.com/feeds/rss/india-news/rssfeed.xml",
                "https://www.hindustantimes.com/feeds/rss/world-news/rssfeed.xml",
                "https://www.hindustantimes.com/feeds/rss/cities/{ht}-news/rssfeed.xml"));
        SOURCES.add(new Source("ie", "The Indian Express",
                "https://indianexpress.com/section/india/feed/",
                "https://indianexpress.com/section/world/feed/",
                "https://indianexpress.com/section/cities/{ie}/feed/"));
        SOURCES.add(new Source("indiatoday", "India Today",
                "https://www.indiatoday.in/rss/1206578",
                "https://www.indiatoday.in/rss/1206577", null));
        SOURCES.add(new Source("news18", "News18",
                "https://www.news18.com/commonfeeds/v1/eng/rss/india.xml",
                "https://www.news18.com/commonfeeds/v1/eng/rss/world.xml", null));
        SOURCES.add(new Source("ndtv", "NDTV",
                "https://feeds.feedburner.com/ndtvnews-india-news",
                "https://feeds.feedburner.com/ndtvnews-world-news", null));
        SOURCES.add(new Source("hindu", "The Hindu",
                "https://www.thehindu.com/news/national/feeder/default.rss",
                "https://www.thehindu.com/news/international/feeder/default.rss", null));
    }

    /** The local city for news: the weather location (from the internet address or chosen). */
    static String city(Context c) {
        return Weather.place(c);
    }

    /** City slugs as each newsroom spells them in its URLs. */
    private static String slug(String city, boolean indianExpress) {
        String s = city.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z ]", "").replace(' ', '-');
        if (s.equals("new-delhi")) s = "delhi";
        if (indianExpress) {
            switch (s) {
                case "bengaluru": return "bangalore";
                case "gurugram": return "gurgaon";
                case "mysuru": return "mysore";
                case "thiruvananthapuram": return "thiruvananthapuram";
                default: return s;
            }
        }
        switch (s) {
            case "bangalore": return "bengaluru";
            case "gurgaon": return "gurugram";
            case "bombay": return "mumbai";
            default: return s;
        }
    }

    /** The Repubblica city edition for a place such as "Torino Centro (TO)", or null. */
    private static String repubblicaCity(String place) {
        String p = java.text.Normalizer.normalize(place.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        for (String c : REPUBBLICA_CITIES) if (p.contains(c)) return c;
        return null;
    }

    private News() {
    }

    static final String K_REFRESH = "news_refresh_min";
    static final int[] REFRESH_CHOICES = {15, 30, 60};

    /** Minutes between news updates (30 by default). */
    static int refreshMinutes(Context c) {
        return Suggestions.prefs(c).getInt(K_REFRESH, 30);
    }

    static Set<String> sources(Context c) {
        Set<String> def = new HashSet<>(java.util.Arrays.asList("repubblica"));
        return new HashSet<>(Suggestions.prefs(c).getStringSet(K_SOURCES, def));
    }

    static void setSources(Context c, Set<String> ids) {
        SharedPreferences p = Suggestions.prefs(c);
        p.edit().putStringSet(K_SOURCES, ids).apply();
    }

    /** Latest stories: India, local and world mixed together, alternating between newsrooms. Blocking. */
    static List<Suggestions.Item> fetch(Context c, int max) {
        return fetch(c, max, sources(c), true, 8);
    }

    /** The News app's stories: la Repubblica and ANSA, with or without a picture, more per feed. Blocking. */
    static List<Suggestions.Item> fetchForApp(Context c, int max) {
        return fetch(c, max, new HashSet<>(java.util.Arrays.asList("repubblica", "ansa")), false, 20);
    }

    private static List<Suggestions.Item> fetch(Context c, int max, Set<String> chosen, boolean needImage, int perFeed) {
        List<List<Suggestions.Item>> lists = new ArrayList<>();
        String city = city(c);
        List<Source> all = new ArrayList<>(SOURCES);
        all.add(ANSA);
        for (String sec : SECTIONS) {
            if (sec.equals(LOCAL) && city.isEmpty()) continue;
            for (Source s : all) {
                if (!chosen.contains(s.id)) continue;
                String url = sec.equals(INDIA) ? s.india : sec.equals(WORLD) ? s.world : s.local;
                if (url == null) continue;
                if (url.contains("{rep}")) {
                    String rc = repubblicaCity(city);
                    if (rc == null) continue;
                    url = url.replace("{rep}", rc);
                }
                if (url.contains("{ansa}")) {
                    String region = null;
                    String low = city.toLowerCase(Locale.ROOT);
                    for (String[] r : ANSA_REGIONS) if (low.contains(r[0])) region = r[1];
                    if (region == null) continue;
                    url = url.replace("{ansa}", region);
                }
                if (url.contains("{toi}")) {
                    String id = TOI_CITIES.get(slug(city, false));
                    if (id == null) continue;
                    url = url.replace("{toi}", id);
                }
                url = url.replace("{ie}", slug(city, true)).replace("{ht}", slug(city, false));
                boolean it = s.id.equals("repubblica") || s.id.equals("ansa");
                String label = sec.equals(LOCAL) ? (it ? repubblicaCity(city) == null ? city : cap(repubblicaCity(city)) : city)
                        : sec.equals(WORLD) ? (it ? "Mondo" : "World") : (it ? "Italia" : "India");
                try {
                    long maxAge = (sec.equals(LOCAL) ? 7 : 2) * 86400000L;  // city news moves slower
                    List<Suggestions.Item> l = read(url, label + "  \u00b7  " + s.name, maxAge, needImage, perFeed);
                    if (!l.isEmpty()) lists.add(l);
                } catch (Exception e) {
                    // skip a feed that can't be reached (or a city without its own feed)
                    android.util.Log.w("TvHome", "news feed " + url + ": " + e);
                }
            }
        }
        List<Suggestions.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            for (List<Suggestions.Item> l : lists) {
                if (round < l.size()) {
                    any = true;
                    Suggestions.Item it = l.get(round);
                    if (out.size() < max && seen.add(it.title.toLowerCase(Locale.ROOT))) out.add(it);
                }
            }
            if (!any) break;
        }
        return out;
    }

    private static List<Suggestions.Item> read(String url, String label, long maxAge, boolean needImage, int perFeed)
            throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) HumeLauncher");
        List<Suggestions.Item> out = new ArrayList<>();
        try (InputStream in = con.getInputStream()) {
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            x.setInput(in, null);
            Suggestions.Item cur = null;
            String tag = null, rawDesc = "";
            int seenItems = 0;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < 30 && seenItems < 80; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = x.getName();
                    if (tag.equals("item")) {
                        seenItems++;
                        cur = new Suggestions.Item();
                        cur.type = "news";
                        cur.title = cur.overview = cur.backdrop = cur.year = "";
                        cur.genres = label;
                        cur.logo = "";
                        rawDesc = "";
                    } else if (cur != null && (tag.equals("media:content") || tag.equals("media:thumbnail") || tag.equals("enclosure"))) {
                        String u = x.getAttributeValue(null, "url");
                        String type = x.getAttributeValue(null, "type");
                        if (u != null && (type == null || type.startsWith("image")) && cur.backdrop.isEmpty()) cur.backdrop = bigger(u);
                    }
                } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                    String t = x.getText();
                    switch (tag) {
                        case "title": cur.title += t; break;
                        case "link": cur.link += t.trim(); break;
                        case "description": cur.overview += t; rawDesc += t; break;
                        case "pubDate": cur.published = parseDate(t.trim()); break;
                        default: break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (x.getName().equals("item") && cur != null) {
                        cur.title = clean(cur.title);
                        cur.overview = clean(cur.overview);
                        if (cur.backdrop.isEmpty()) {  // some feeds (India Today) put the image in the summary
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(rawDesc);
                            if (m.find()) cur.backdrop = bigger(m.group(1).replace("&amp;", "&"));
                        }
                        boolean fresh = cur.published == 0 || System.currentTimeMillis() - cur.published < maxAge;
                        if (!cur.title.isEmpty() && (!needImage || !cur.backdrop.isEmpty()) && fresh) out.add(cur);
                        cur = null;
                    }
                    tag = null;
                }
            }
        } finally {
            con.disconnect();
        }
        out.sort((a, b) -> Long.compare(b.published, a.published));  // newest first
        return out.size() > perFeed ? new ArrayList<>(out.subList(0, perFeed)) : out;
    }

    /** Ask for a larger rendition where the newsroom's image URLs allow it. */
    private static String bigger(String u) {
        java.util.regex.Matcher toi = java.util.regex.Pattern.compile("toiimg\\.com/photo/msid-(\\d+)").matcher(u);
        if (toi.find()) return "https://static.toiimg.com/thumb/msid-" + toi.group(1) + ",width-1280,height-720,resizemode-4/photo.jpg";
        return u.replaceAll("/LANDSCAPE_\\d+/", "/LANDSCAPE_1200/");
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Strip tags and entities; keep one short paragraph. */
    static String clean(String s) {
        String t = s.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
        return t.length() > 260 ? t.substring(0, 257).trim() + "\u2026" : t;
    }

    /** RSS dates: RFC 822 ("Thu, 22 Oct 2026 13:33:54 +0530") or ISO 8601 (Times of India). */
    static long parseDate(String pub) {
        String[] fmts = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"};
        for (String f : fmts) {
            try {
                return new SimpleDateFormat(f, Locale.US).parse(pub).getTime();
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return 0;
    }

    /** "Adesso", "5 min fa", "3 h fa", "Ieri" \u2014 worked out when shown, so it never goes stale. */
    static String age(long published) {
        if (published <= 0) return "";
        long min = (System.currentTimeMillis() - published) / 60000;
        if (min < 1) return "Adesso";
        if (min < 60) return min + " min fa";
        if (min < 24 * 60) return (min / 60) + " h fa";
        return min < 48 * 60 ? "Ieri" : (min / 1440) + " giorni fa";
    }
}
