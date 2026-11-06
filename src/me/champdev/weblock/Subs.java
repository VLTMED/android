package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenSubtitles through Stremio's public addon (no key, no login): subtitle files for an IMDb title or episode. */
final class Subs {
    static final String BASE = "https://opensubtitles-v3.strem.io/subtitles/";

    static final class Sub {
        String url, lang, release;
    }

    /** How far (dp) subtitles sit above the default spot; negative = lower. Kept across videos. */
    static int offset() {
        return App.prefs.getInt("subOffset", 0);
    }

    static String offsetLabel() {
        int o = offset();
        return o == 0 ? "Default" : (o > 0 ? "Up " : "Down ") + Math.abs(o);
    }

    /** Subtitle text size in percent of the default (22 sp). */
    static int size() {
        return App.prefs.getInt("subSize", 100);
    }

    static String sizeLabel() {
        return size() == 100 ? "Default" : size() + "%";
    }

    static final int COLOR_TEXT = 0, COLOR_OUTLINE = 1, COLOR_SHADOW = 2, COLOR_BACKGROUND = 3;

    static int color(int role) {
        switch (role) {
            case COLOR_OUTLINE: return App.prefs.getInt("subOutlineColor", android.graphics.Color.BLACK);
            case COLOR_SHADOW: return App.prefs.getInt("subShadowColor", android.graphics.Color.BLACK);
            case COLOR_BACKGROUND: return App.prefs.getInt("subBackgroundColor", 0x88000000);
            default: return App.prefs.getInt("subTextColor", android.graphics.Color.WHITE);
        }
    }

    static void setColor(int role, int value) {
        String key = role == COLOR_OUTLINE ? "subOutlineColor" : role == COLOR_SHADOW ? "subShadowColor" : role == COLOR_BACKGROUND ? "subBackgroundColor" : "subTextColor";
        App.prefs.edit().putInt(key, value).apply();
    }

    static int outlineWidth() { return App.prefs.getInt("subOutlineWidth", 0); }
    static void setOutlineWidth(int value) { App.prefs.edit().putInt("subOutlineWidth", Math.max(0, Math.min(6, value))).apply(); }
    static int shadowRadius() { return App.prefs.getInt("subShadowRadius", 3); }
    static void setShadowRadius(int value) { App.prefs.edit().putInt("subShadowRadius", Math.max(0, Math.min(8, value))).apply(); }
    static int shadowOffset() { return App.prefs.getInt("subShadowOffset", 1); }
    static void setShadowOffset(int value) { App.prefs.edit().putInt("subShadowOffset", Math.max(-6, Math.min(6, value))).apply(); }
    static int backgroundOpacity() { return android.graphics.Color.alpha(color(COLOR_BACKGROUND)); }
    static int alignment() { return Math.max(0, Math.min(2, App.prefs.getInt("subtitleAlignment", 1))); }
    static void setAlignment(int value) { App.prefs.edit().putInt("subtitleAlignment", Math.max(0, Math.min(2, value))).apply(); }
    static int alignmentGravity() { return alignment() == 0 ? android.view.Gravity.START : alignment() == 2 ? android.view.Gravity.END : android.view.Gravity.CENTER_HORIZONTAL; }

    static void setBackgroundOpacity(int alpha) {
        int c = color(COLOR_BACKGROUND);
        setColor(COLOR_BACKGROUND, android.graphics.Color.argb(Math.max(0, Math.min(255, alpha)), android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)));
    }

    static void applyAppearance(SubtitleTextView view, android.content.Context context) {
        view.setTextSize(22f * size() / 100f);
        view.setTypeface(Fonts.subtitle(context));
        view.setTextColor(color(COLOR_TEXT));
        view.setGravity(android.view.Gravity.CENTER_VERTICAL | alignmentGravity());
        view.setOutline(Home.dp(context, outlineWidth()), color(COLOR_OUTLINE));
        view.setSubtitleShadow(Home.dp(context, shadowRadius()), 0, Home.dp(context, shadowOffset()), color(COLOR_SHADOW));
        view.setBackground(backgroundOpacity() == 0 ? null : Home.rounded(color(COLOR_BACKGROUND), Home.dp(context, 7)));
        view.setPadding(Home.dp(context, 10), Home.dp(context, 4), Home.dp(context, 10), Home.dp(context, 4));
    }

    static void resetAppearance() {
        App.prefs.edit().remove("subTextColor").remove("subOutlineColor").remove("subShadowColor")
            .remove("subBackgroundColor").remove("subOutlineWidth").remove("subShadowRadius").remove("subShadowOffset").remove("subtitleAlignment").apply();
    }

    /** Subtitle timing for the video playing now, in ms (not saved): positive shows them later, negative earlier. Read on the player's thread. */
    static volatile long delayMs;

    static String delayLabel() {
        return delayMs == 0 ? "In sync" : String.format(Locale.ROOT, "%+.1f s", delayMs / 1000f);
    }

    private static void bump(String key, int def, int by, int min, int max) {
        App.prefs.edit().putInt(key, Math.max(min, Math.min(max, App.prefs.getInt(key, def) + by))).apply();
    }

    /** Opens the grouped, live-preview subtitle appearance panel. */
    static void panel(android.app.Activity a, boolean timing, Runnable changed, Runnable closed) {
        SubtitleStylePanel.show(a, timing, changed, closed);
    }

    /** season 0 = a movie. Null when OpenSubtitles can't be reached. Call off the main thread. */
    static List<Sub> find(String imdb, int season, int episode) {
        String path = season > 0 ? "series/" + imdb + ":" + season + ":" + episode : "movie/" + imdb;
        try {
            JSONArray a = new JSONObject(Sites.readAll(Warp.open(BASE + path + ".json").getInputStream())).getJSONArray("subtitles");
            List<Sub> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Sub s = new Sub();
                s.url = o.getString("url");
                s.lang = o.optString("lang", "und");
                // The file name tells releases apart (the movie name is the same on every row)
                s.release = o.optString("subtitleFileName", "").isEmpty() ? o.optString("movieReleaseName", "Subtitle " + (i + 1)) : o.optString("subtitleFileName");
                out.add(s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** IMDb id of the best Cinemeta match for a page's title, "" when nothing matches. */
    static String imdbFor(String title, boolean series) {
        List<Catalog.Title> hit = Catalog.list(series ? "series" : "movie", "search=" + android.net.Uri.encode(title));
        return hit == null || hit.isEmpty() ? "" : hit.get(0).id;
    }

    /** Languages with their files, your own language and English first, then the biggest. Keys are ISO 639-2 codes. */
    static Map<String, List<Sub>> byLanguage(List<Sub> all) {
        Map<String, List<Sub>> m = new LinkedHashMap<>();
        for (Sub s : all) m.computeIfAbsent(s.lang, k -> new ArrayList<>()).add(s);
        List<Map.Entry<String, List<Sub>>> es = new ArrayList<>(m.entrySet());
        String mine = Locale.getDefault().getISO3Language();
        es.sort((a, b) -> rank(b, mine) - rank(a, mine));
        Map<String, List<Sub>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Sub>> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static int rank(Map.Entry<String, List<Sub>> e, String mine) {
        return (e.getKey().equals(mine) ? 2000 : e.getKey().equals("eng") ? 1000 : 0) + e.getValue().size();
    }

    // OpenSubtitles' own codes that are not ISO languages
    static final Map<String, String> ODD = new LinkedHashMap<>();
    static {
        ODD.put("pob", "Portuguese (Brazil)"); ODD.put("zht", "Chinese (Traditional)"); ODD.put("zhc", "Chinese (Cantonese)");
        ODD.put("zhe", "Chinese (bilingual)"); ODD.put("spl", "Spanish (Latin America)"); ODD.put("spn", "Spanish (Spain)");
        ODD.put("ex", "Extremaduran"); ODD.put("ze", "Chinese (bilingual)"); ODD.put("pom", "Portuguese");
    }

    static String language(String iso3) {
        if (ODD.containsKey(iso3)) return ODD.get(iso3);
        String n = new Locale(iso3).getDisplayLanguage(Locale.ENGLISH);
        return n.isEmpty() || n.equals(iso3) ? iso3.toUpperCase(Locale.ROOT) : n;
    }
}
