package me.champdev.weblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Start screen: Continue watching, then FMHY's streaming sites by category, then the user's own sites. */
public class Home extends Activity {
    static final int BG = 0xFF120E1A, CARD = 0xFF1E1828, ACCENT = 0xFFFF3B5C, TEXT = 0xFFF2EDF7, MUTED = 0xFF9A90A8;
    static final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    ScrollView page;
    LinearLayout col;
    static final int GREEN = 0xFF6EE7A0, AMBER = 0xFFFFC857;
    int side, slack, cardW = 220, cardH = 92, gapDp = 14;

    /** TV value on a TV, tighter value on a phone (dp / sp). */
    int u(float tv, float phone) { return dp(App.tv ? tv : phone); }
    float sp(float tv, float phone) { return App.tv ? tv : phone; }
    TextView warpChip, adChip;
    View firstCard;
    final Runnable warpChanged = this::updateChips;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    int dp(float v) {
        return dp(this, v);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView sv = page = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // One margin on every side; rows get a little slack past it so the grown focus ring never clips
        side = u(40, 12);
        slack = u(10, 6);
        cardW = App.tv ? 220 : 156;
        cardH = App.tv ? 92 : 62;
        gapDp = App.tv ? 14 : 8;
        col.setPadding(side, App.tv ? side : dp(6), side, App.tv ? side : dp(12));
        col.setClipToPadding(false);
        sv.addView(col);
        if (tabs()) {
            // Tabs sit on top on a TV (D-pad up from the first row) and at the bottom on a phone (thumb reach)
            if (state == null) tab = 0;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(BG);
            tabBar = tabBar();
            if (App.tv) root.addView(tabBar);
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            if (!App.tv) root.addView(tabBar);
            setContentView(root);
        } else setContentView(sv);
        Warp.listeners.add(warpChanged);

        // Site lists and IPTV channels refresh whenever the app starts and they are older than 12 hours
        if (Sites.stale() || Iptv.stale()) Warp.whenSettled(() -> App.io.execute(() -> {
            boolean sites = Sites.stale() && Sites.refresh();
            boolean iptv = Iptv.stale() && Iptv.refresh();
            if (sites || iptv) runOnUiThread(this::build);
        }));
        // Test the channels that were not checked lately (a few at a time, in the background)
        Warp.whenSettled(() -> App.io.execute(Iptv::sweep));
        // A newer release on GitHub shows as a line on Home
        if (Update.due()) Warp.whenSettled(() -> App.io.execute(() -> { if (Update.check()) runOnUiThread(this::build); }));
    }

    @Override protected void onResume() {
        super.onResume();
        build();
    }

    @Override public boolean onKeyUp(int k, android.view.KeyEvent e) {
        View f = getCurrentFocus();
        if (k != android.view.KeyEvent.KEYCODE_MENU || f == null) return super.onKeyUp(k, e);
        f.performLongClick();
        return true;
    }

    @Override protected void onDestroy() {
        Warp.listeners.remove(warpChanged);
        super.onDestroy();
    }

    /** Draws the selected tab. Subclasses (search, category, title) replace this whole method. */
    void build() {
        col.removeAllViews();
        firstCard = null;
        built++;
        cardW = App.tv ? 220 : 156;   // a site grid on another tab resizes the shared card width
        styleTabs();
        switch (tab) {
            case 0: buildHome(); break;
            case 1: catalogTab("movie", Cats.MOVIES, ""); break;
            case 2: catalogTab("series", Cats.TV, ""); break;
            case 3: sportsTab(); break;
            case 4: catalogTab("series", Cats.ANIME, "Animation"); break;
            case 5: iptvTab(); break;
            default: settingsTab();
        }
        View first = firstCard;
        if (App.tv && first != null && !skipFocus) col.postDelayed(first::requestFocus, 60);
    }

    void buildHome() {
        col.addView(header());
        if (!Update.available().isEmpty()) col.addView(updateLine(Update.available()));

        List<History.Entry> history = History.list();
        if (!history.isEmpty()) {
            col.addView(heading("Continue watching"));
            LinearLayout row = row();
            for (History.Entry e : history) row.addView(historyCard(e));
        }

        List<Catalog.Title> mine = Catalog.mine();
        if (!mine.isEmpty()) {
            col.addView(heading("My list"));
            LinearLayout row = row();
            for (Catalog.Title t : mine) row.addView(posterCard(t, posterW()));
        }
        titleRow("Popular movies", "movie", 1);
        titleRow("Popular series", "series", 2);

        List<Sites.Site> favs = Sites.favorites();
        LinearLayout favHead = new LinearLayout(this);
        favHead.setGravity(Gravity.BOTTOM);
        TextView favTitle = heading("★  Favorites");
        favHead.addView(favTitle, new LinearLayout.LayoutParams(0, -2, 1));
        if (!favs.isEmpty()) favHead.addView(iconButton(R.drawable.ic_delete, "Clear all favorites", v -> confirmClearFavorites(favs.size())));
        col.addView(favHead);
        LinearLayout favRow = row();
        for (Sites.Site s : favs) favRow.addView(siteCard(s, false));
        favRow.addView(addCard());

        List<Sites.Site> more = Sites.load().get(Cats.MORE);
        if (more != null) {
            col.addView(heading(Cats.MORE));
            siteRow(Cats.MORE, more);
        }

        Map<String, List<Sites.Site>> langs = Sites.languages();
        if (!langs.isEmpty()) {
            col.addView(heading("Other languages"));
            LinearLayout row = row();
            for (Map.Entry<String, List<Sites.Site>> l : langs.entrySet()) row.addView(languageCard(l.getKey(), l.getValue().size()));
        }
    }

    /** One row of site cards. Picks come first; a long category shows its first batch and an "All" card for the rest. */
    void siteRow(String category, List<Sites.Site> sites) {
        LinearLayout row = row();
        boolean more = sites.size() > Sites.ROW_MAX + 2;
        for (Sites.Site s : more ? sites.subList(0, Sites.ROW_MAX) : sites) row.addView(siteCard(s, true));
        if (more) row.addView(allCard(category, sites.size()));
    }

    void confirmClearFavorites(int n) {
        confirm("Clear all favorites?", "Removes " + n + (n == 1 ? " site" : " sites") + " from Favorites, including sites you added yourself. FMHY's own lists stay.",
            "Clear all", () -> { Sites.clearFavorites(); build(); toast("Favorites cleared"); });
    }

    // ---- voice input: the device's own speech recognizer (Google TV voice on a TV, the Google app on a phone) ----

    static final int REQ_VOICE = 77;
    java.util.function.Consumer<String> voiceDone;

    void listen(java.util.function.Consumer<String> then) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a title, site or channel")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        voiceDone = then;
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No voice input on this device");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null || voiceDone == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) voiceDone.accept(said.get(0));
    }

    /** A mic-only chip next to Search. */
    TextView voiceChip(View.OnClickListener click) {
        TextView t = chip(click);
        iconize(t, R.drawable.ic_mic, TEXT);
        t.setCompoundDrawablePadding(0);
        t.setContentDescription(Localization.translate(this, "Voice search"));
        return t;
    }

    void confirm(String title, String message, String yes, Runnable then) {
        new LocalizedDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton(yes, (d, w) -> then.run())
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---- tabs ----

    static final String[] TABS = {"Home", "Movies", "TV", "Sports", "Anime", "IPTV", "Settings"};
    static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_movie, R.drawable.ic_tv, R.drawable.ic_trophy, R.drawable.ic_sparkle, R.drawable.ic_live, R.drawable.ic_settings};
    static int tab;
    static final Map<Integer, String> genres = new HashMap<>();
    static String focusGenre;
    LinearLayout tabBar;
    int built;
    boolean skipFocus;

    /** Search, category and title screens are pages on top of the tabs and have no tab bar. */
    boolean tabs() { return true; }

    LinearLayout tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (App.tv) bar.setPadding(side - slack, dp(14), side - slack, dp(4));
        else bar.setBackgroundColor(CARD);
        for (int i = 0; i < TABS.length; i++) {
            final int n = i;
            TextView t = text(TABS[i], App.tv ? 16 : 11, MUTED, true);
            t.setGravity(Gravity.CENTER);
            if (App.tv) {
                t.setPadding(dp(18), dp(9), dp(18), dp(9));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(slack, 0, dp(8), 0);
                t.setLayoutParams(lp);
                t.setBackground(rounded(CARD, dp(22)));
                focusable(t, dp(22));
            } else {
                t.setPadding(0, dp(8), 0, dp(7));
                t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                t.setBackground(rounded(CARD, dp(12)));
                focusable(t, dp(12));
            }
            t.setOnClickListener(v -> selectTab(n));
            bar.addView(t);
        }
        return bar;
    }

    /** Selected tab = accent colour; the others stay quiet. */
    void styleTabs() {
        if (tabBar == null) return;
        for (int i = 0; i < TABS.length; i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean on = i == tab;
            android.graphics.drawable.Drawable d = getDrawable(TAB_ICONS[i]).mutate();
            int size = dp(App.tv ? 20 : 22);
            d.setBounds(0, 0, size, size);
            int color = App.tv ? (on ? Color.WHITE : TEXT) : (on ? ACCENT : MUTED);
            d.setTint(color);
            t.setTextColor(color);
            if (App.tv) {
                t.setCompoundDrawablesRelative(d, null, null, null);
                t.setCompoundDrawablePadding(dp(8));
                ((GradientDrawable) t.getBackground()).setColor(on ? ACCENT : CARD);
            } else {
                t.setCompoundDrawablesRelative(null, d, null, null);
                t.setCompoundDrawablePadding(dp(3));
            }
        }
    }

    void selectTab(int n) {
        if (n == tab) return;
        tab = n;
        skipFocus = true;
        build();
        skipFocus = false;
        page.scrollTo(0, 0);
    }

    @Override public void onBackPressed() {
        if (tabs() && tab != 0) selectTab(0);
        else super.onBackPressed();
    }

    /** Poster width in dp for a row of posters. */
    int posterW() { return App.tv ? 150 : 108; }

    View note(String s) {
        TextView t = text(s, sp(15, 13), MUTED, false);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        return t;
    }

    View tabHeader(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, sp(26, 20), TEXT, true);
        h.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView search = chip(v -> startActivity(new Intent(this, SearchActivity.class)));
        search.setText("Search");
        iconize(search, R.drawable.ic_search, TEXT);
        h.addView(search);
        h.addView(voiceChip(v -> startActivity(new Intent(this, SearchActivity.class).putExtra("voice", true))));
        return h;
    }

    /** A poster row that draws at once from disk if it can, else fills in when the catalog answers. */
    void titleRow(String head, String type, int moreTab) {
        col.addView(heading(head));
        LinearLayout row = row();
        final int id = built;
        List<Catalog.Title> hit = Catalog.cached(type, "");
        if (hit != null) { fillPosters(row, hit, moreTab); return; }
        row.addView(note("Loading…"));
        Warp.whenSettled(() -> App.io.execute(() -> {
            List<Catalog.Title> list = Catalog.list(type, "");
            runOnUiThread(() -> {
                if (id != built) return;
                row.removeAllViews();
                if (list == null || list.isEmpty()) row.addView(note("Can't reach the catalog. Check WARP."));
                else fillPosters(row, list, moreTab);
            });
        }));
    }

    void fillPosters(LinearLayout row, List<Catalog.Title> list, int moreTab) {
        for (Catalog.Title t : list.subList(0, Math.min(14, list.size()))) row.addView(posterCard(t, posterW()));
        TextView more = text("More  ›", sp(17, 15), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setLayoutParams(cardParams(posterW(), posterW() * 3 / 2));
        more.setBackground(rounded(0xFF2A2236, dp(12)));
        focusable(more, dp(12));
        more.setOnClickListener(v -> selectTab(moreTab));
        row.addView(more);
    }

    /** Movies, TV and Anime: the sites that carry them, then the native catalog (genre chips, posters, load more). */
    void catalogTab(String type, String category, String fixedGenre) {
        col.addView(tabHeader(TABS[tab]));
        List<Sites.Site> sites = Sites.load().get(category);
        if (sites != null) {
            col.addView(heading("Sites"));
            siteRow(category, sites);
        }
        String genre = fixedGenre.isEmpty() ? genres.getOrDefault(tab, "") : fixedGenre;
        col.addView(heading(fixedGenre.isEmpty() ? (genre.isEmpty() ? "Popular" : genre) : "Popular " + fixedGenre.toLowerCase()));
        if (fixedGenre.isEmpty()) col.addView(genreChips());
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);
        loadPage(type, genre, 0, holder, new Grid(holder, App.tv ? 6 : 3), built);
    }

    View genreChips() {
        LinearLayout chips = new LinearLayout(this);
        chips.setClipChildren(false);
        String now = genres.getOrDefault(tab, "");
        String[] all = new String[Catalog.GENRES.length + 1];
        System.arraycopy(Catalog.GENRES, 0, all, 1, Catalog.GENRES.length);
        all[0] = "";
        for (String g : all) {
            TextView c = chip(v -> { genres.put(tab, g); focusGenre = g; build(); });
            c.setText(g.isEmpty() ? "All" : g);
            c.setTextColor(g.equals(now) ? Color.WHITE : TEXT);
            c.setBackground(rounded(g.equals(now) ? ACCENT : CARD, dp(22)));
            focusable(c, dp(22));
            if (App.tv && g.equals(focusGenre)) { focusGenre = null; c.post(c::requestFocus); }
            chips.addView(c);
        }
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        sc.setLayoutParams(lp);
        sc.setPadding(slack, 0, slack, dp(6));
        sc.addView(chips);
        return sc;
    }

    /** Adds one page of posters (50 titles) to the grid, then a "Load more" button. `id` drops answers for a screen that was rebuilt. */
    void loadPage(String type, String genre, int skip, LinearLayout holder, Grid grid, int id) {
        String extra = (genre.isEmpty() ? "" : "genre=" + genre) + (genre.isEmpty() || skip == 0 ? "" : "&") + (skip == 0 ? "" : "skip=" + skip);
        List<Catalog.Title> hit = Catalog.cached(type, extra);
        if (hit != null) { showPage(type, genre, skip, holder, grid, id, hit); return; }
        View loading = note("Loading…");
        holder.addView(loading);
        Warp.whenSettled(() -> App.io.execute(() -> {
            List<Catalog.Title> list = Catalog.list(type, extra);
            runOnUiThread(() -> {
                if (id != built) return;
                holder.removeView(loading);
                if (list == null || list.isEmpty()) {
                    TextView retry = text(list == null ? "Can't reach the catalog. Tap to retry" : "Nothing here", sp(15, 13), TEXT, true);
                    retry.setPadding(dp(14), dp(12), dp(14), dp(12));
                    retry.setBackground(rounded(CARD, dp(14)));
                    focusable(retry, dp(14));
                    retry.setOnClickListener(v -> { holder.removeView(retry); loadPage(type, genre, skip, holder, grid, id); });
                    holder.addView(retry);
                } else showPage(type, genre, skip, holder, grid, id, list);
            });
        }));
    }

    void showPage(String type, String genre, int skip, LinearLayout holder, Grid grid, int id, List<Catalog.Title> list) {
        for (Catalog.Title t : list) grid.add(posterCard(t, grid.cell));
        if (list.size() < 20) return;
        TextView more = text("Load more", sp(16, 14), TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(14), 0, dp(14));
        more.setBackground(rounded(0xFF2A2236, dp(14)));
        focusable(more, dp(14));
        more.setOnClickListener(v -> { holder.removeView(more); loadPage(type, genre, skip + list.size(), holder, grid, id); });
        holder.addView(more);
    }

    void sportsTab() {
        col.addView(tabHeader("Sports"));
        for (String cat : new String[]{Cats.SPORTS, Cats.LIVE}) {
            List<Sites.Site> sites = Sites.load().get(cat);
            if (sites == null) continue;
            col.addView(heading(cat.equals(Cats.LIVE) ? "Live TV  ·  " + sites.size() : "Sports sites  ·  " + sites.size()));
            Grid grid = new Grid(col);
            for (Sites.Site s : sites) grid.add(s);
        }
    }

    void iptvTab() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(text("IPTV", sp(26, 20), TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView search = chip(v -> openIptv("all", "", "Search channels", true));
        search.setText("Search");
        iconize(search, R.drawable.ic_search, TEXT);
        h.addView(search);
        h.addView(voiceChip(v -> openIptv("all", "", "Search channels", false, true)));
        TextView add = chip(v -> addPlaylistDialog());
        add.setText("+  Playlist");
        h.addView(add);
        col.addView(h);

        List<Iptv.Channel> all = Iptv.all();
        if (all.isEmpty()) {
            col.addView(note(Iptv.loading ? "Loading channels…" : "No channels yet. Check WARP, then Settings, Refresh IPTV channels."));
            if (!Iptv.loading) Warp.whenSettled(() -> App.io.execute(() -> {
                boolean ok = Iptv.refresh();
                runOnUiThread(() -> { if (ok && tab == 5) build(); });
            }));
            return;
        }
        String status = Iptv.statusLine();
        if (!status.isEmpty()) col.addView(note(status));
        else if (App.tv && !Iptv.history().isEmpty()) col.addView(note("Hold OK on a channel to remove it or add it to favorites"));
        int cols = App.tv ? 6 : 3;
        // What you added yourself comes first: a row per playlist under the name you gave it, single stream links together
        List<String> keys = new ArrayList<>(), titles = new ArrayList<>();
        for (String url : Iptv.mine()) { keys.add(url); titles.add(Iptv.playlistName(url)); }
        keys.add(Iptv.STREAMS);
        titles.add(Iptv.STREAMS);
        for (int k = 0; k < keys.size(); k++) {
            String key = keys.get(k), title = titles.get(k);
            List<Iptv.Channel> own = Iptv.list("mine", key);
            if (!key.equals(Iptv.STREAMS)) own.removeIf(c -> c.group.equals(Iptv.STREAMS));
            if (own.isEmpty()) continue;
            col.addView(heading(title + "  \u00B7  " + own.size()));
            LinearLayout mr = row();
            for (Iptv.Channel c : own.subList(0, Math.min(14, own.size()))) mr.addView(channelCard(c, cols, "mine"));
            if (own.size() > 14) {
                TextView more = text("All  " + own.size() + "  \u203A", sp(17, 15), TEXT, true);
                more.setGravity(Gravity.CENTER);
                more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
                more.setBackground(rounded(0xFF2A2236, dp(14)));
                focusable(more, dp(14));
                more.setOnClickListener(v -> openIptv("mine", key, title, false));
                mr.addView(more);
            }
        }
        // Recently watched channels and stars, as rows like Continue watching on Home (movies stay on Home, channels stay here)
        List<Iptv.Channel> recent = Iptv.history();
        if (!recent.isEmpty()) {
            col.addView(heading("Continue watching"));
            LinearLayout rr = row();
            for (Iptv.Channel c : recent) rr.addView(channelCard(c, cols, "history"));
        }
        // Channels that worked: what played fine for you, then what passed the background check
        List<Iptv.Channel> works = Iptv.working();
        if (!works.isEmpty()) {
            col.addView(heading("Working  \u00B7  " + works.size()));
            LinearLayout wr = row();
            int shown = 0;
            for (Iptv.Channel c : works) {
                if (shown == 14) break;
                if (!Iptv.good.containsKey(c.url)) break;   // the row is channels that played; the rest are behind More
                wr.addView(channelCard(c, cols, "working"));
                shown++;
            }
            TextView more = text("All  " + works.size() + "  \u203A", sp(17, 15), TEXT, true);
            more.setGravity(Gravity.CENTER);
            more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
            more.setBackground(rounded(0xFF2A2236, dp(14)));
            focusable(more, dp(14));
            more.setOnClickListener(v -> openIptv("working", "", "Working channels", false));
            wr.addView(more);
        }
        List<Iptv.Channel> stars = Iptv.favorites();
        if (!stars.isEmpty()) {
            col.addView(heading("Favorites"));
            LinearLayout fr = row();
            for (Iptv.Channel c : stars.subList(0, Math.min(14, stars.size()))) fr.addView(channelCard(c, cols, "fav"));
            TextView more = text("More  \u203A", sp(17, 15), TEXT, true);
            more.setGravity(Gravity.CENTER);
            more.setLayoutParams(cardParams(squareDp(cols), squareDp(cols)));
            more.setBackground(rounded(0xFF2A2236, dp(14)));
            focusable(more, dp(14));
            more.setOnClickListener(v -> openIptv("fav", "", "Favorites", false));
            fr.addView(more);
        }
        // Square tiles: 6 across on a TV, 3 on a phone; an emoji icon (flag for countries) over the name and count
        col.addView(heading("Categories"));
        Grid cats = new Grid(col, cols);
        for (Map.Entry<String, List<Iptv.Channel>> e : Iptv.groups("cat").entrySet()) cats.add(iptvTile(cats.cell, Iptv.emoji(e.getKey()), e.getKey(), e.getValue().size(), "cat", e.getKey()));
        col.addView(heading("Countries"));
        Grid countries = new Grid(col, cols);
        for (Map.Entry<String, List<Iptv.Channel>> e : Iptv.groups("country").entrySet()) countries.add(iptvTile(countries.cell, Iptv.flag(e.getKey()), e.getKey(), e.getValue().size(), "country", e.getKey()));
    }

    /** Tile edge in dp for a row of `cols` square tiles. */
    int squareDp(int cols) {
        int gap = dp(gapDp);
        return Math.round((getResources().getDisplayMetrics().widthPixels - 2 * side - gap * (cols - 1)) / (float) cols / getResources().getDisplayMetrics().density) - 1;
    }

    View channelCard(Iptv.Channel c, int cols, String row) {
        int px = dp(squareDp(cols));
        View t = channelTile(c, null, px);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
        lp.setMargins(0, 0, dp(gapDp), 0);
        t.setLayoutParams(lp);
        focusable(t, dp(14));
        t.setOnClickListener(v -> Iptv.play(this, c));
        // Hold OK (or press Menu): take it off this row, or star it
        t.setOnLongClickListener(v -> {
            List<String> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            if (row.equals("history")) { items.add("Remove from Continue watching"); actions.add(() -> Iptv.removeFromHistory(c.url)); }
            if (row.equals("working")) { items.add("Remove from Working"); actions.add(() -> Iptv.forgetWorking(c.url)); }
            items.add(Iptv.isFavorite(c) ? "\u2606  Remove from favorites" : "\u2605  Add to favorites");
            actions.add(() -> Iptv.toggleFavorite(c));
            new LocalizedDialogBuilder(this).setTitle(c.name).setItems(items.toArray(new String[0]), (d, i) -> { actions.get(i).run(); build(); }).show();
            return true;
        });
        return t;
    }

    /** A square channel tile: logo (or its first letter) over the name and group. Reused by the channel grid. */
    View channelTile(Iptv.Channel c, View reuse, int size) {
        FrameLayout t, box;
        LinearLayout info;
        TextView letter, name, sub;
        ImageView logo;
        if (reuse == null) {
            t = new FrameLayout(this);
            t.setBackground(rounded(CARD, dp(14)));
            box = new FrameLayout(this);
            letter = text("", sp(34, 28), 0xCCFFFFFF, true);
            letter.setGravity(Gravity.CENTER);
            box.addView(letter, new FrameLayout.LayoutParams(-1, -1));
            logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(14), dp(12), dp(14), dp(4));
            box.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            t.addView(box, new FrameLayout.LayoutParams(-1, size * 11 / 20, Gravity.TOP));
            info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            info.setPadding(dp(8), 0, dp(8), dp(8));
            name = textRaw("", sp(14, 12), TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            sub = textRaw("", sp(11, 10), MUTED, false);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);
            info.addView(sub);
            t.addView(info, new FrameLayout.LayoutParams(-1, size * 9 / 20, Gravity.BOTTOM));
        } else {
            t = (FrameLayout) reuse;
            box = (FrameLayout) t.getChildAt(0);
            letter = (TextView) box.getChildAt(0);
            logo = (ImageView) box.getChildAt(1);
            info = (LinearLayout) t.getChildAt(1);
            name = (TextView) info.getChildAt(0);
            sub = (TextView) info.getChildAt(1);
        }
        box.setBackground(rounded(colorFor(c.name), dp(14)));
        letter.setText(c.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        letter.setVisibility(View.VISIBLE);
        logo.setImageDrawable(null);
        logo.setTag(null);
        if (c.logo.startsWith("http")) loadImage(logo, c.logo, letter);
        name.setText((Iptv.isFavorite(c) ? "\u2605 " : "") + c.name);
        StringBuilder s = new StringBuilder(TextUtils.join(", ", c.cats));
        if (c.adult && !c.source.isEmpty()) s = new StringBuilder(c.source);   // adult channels: which list they came from
        else if (c.cats[0].equals(Iptv.MINE) && !c.group.isEmpty()) s = new StringBuilder(c.group);   // your own playlists: their own group names
        else if (!c.country.isEmpty()) s.append("  \u00B7  ").append(c.country);
        sub.setText(s);
        return t;
    }

    View iptvTile(int size, String icon, String label, int n, String type, String key) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(size, size));
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        TextView ico = text(icon, sp(40, 30), TEXT, false);
        ico.setGravity(Gravity.CENTER);
        card.addView(ico);
        TextView name = text(label, sp(16, 13), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, 0);
        card.addView(name);
        TextView sub = text(n + (n == 1 ? " channel" : " channels"), sp(12, 11), MUTED, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub);
        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> openIptv(type, key, label, false));
        return card;
    }

    /** Set (or clear) the link to your own extra source list, then fetch it. */
    void extrasDialog() {
        EditText in = WebActivity.field(this, "https://example.com/sources.txt", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(Iptv.extrasUrl());
        new LocalizedDialogBuilder(this).setTitle("Extra source list").setMessage("Link to a text file, one \"label|url\" line per playlist. Empty means none.").setView(WebActivity.box(this, in))
            .setPositiveButton("Save", (d, w) -> {
                App.prefs.edit().putString("extrasUrl", in.getText().toString().trim()).apply();
                toast("Fetching\u2026");
                Warp.whenSettled(() -> App.io.execute(() -> {
                    boolean ok = Iptv.refresh();
                    runOnUiThread(() -> { toast(ok ? "Source list updated" : "Can't reach the list"); build(); });
                }));
            })
            .setNeutralButton("Default", (d, w) -> {
                App.prefs.edit().remove("extrasUrl").apply();
                Warp.whenSettled(() -> App.io.execute(() -> {
                    Iptv.refresh();
                    runOnUiThread(this::build);
                }));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Ask for an M3U link and a name, download it, and give its channels their own row on the IPTV tab. */
    void addPlaylistDialog() {
        EditText in = WebActivity.field(this, "https://example.com/playlist.m3u", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = WebActivity.field(this, "Name, e.g. Sports (shown as its row)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new LocalizedDialogBuilder(this).setTitle("Add a playlist or stream").setMessage("A playlist (M3U) or a single stream link (.m3u8)").setView(WebActivity.box(this, in, name))
            .setPositiveButton("Add", (d, w) -> {
                String url = in.getText().toString().trim(), label = name.getText().toString();
                if (url.isEmpty()) return;
                toast("Downloading playlist\u2026");
                Warp.whenSettled(() -> App.io.execute(() -> {
                    String err = Iptv.addPlaylist(url, label);
                    runOnUiThread(() -> {
                        toast(err == null ? "Added to the IPTV tab" : err);
                        build();
                    });
                    if (err == null) Iptv.sweep();
                }));
            })
            .setNeutralButton("Xtream login", (d, w) -> xtreamDialog())
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** An IPTV provider's Xtream Codes login (server, username, password): its live channels get their own row. */
    void xtreamDialog() {
        EditText name = WebActivity.field(this, "Name, e.g. My provider (shown as its row)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText server = WebActivity.field(this, "Server, e.g. http://example.com:8080", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = WebActivity.field(this, "Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = WebActivity.field(this, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new LocalizedDialogBuilder(this).setTitle("Xtream login").setMessage("The server, username and password from your IPTV provider")
            .setView(WebActivity.box(this, name, server, user, pass))
            .setPositiveButton("Add", (d, w) -> {
                if (server.getText().toString().trim().isEmpty() || user.getText().toString().trim().isEmpty()) return;
                String url = Iptv.xtreamUrl(server.getText().toString(), user.getText().toString(), pass.getText().toString());
                toast("Signing in\u2026");
                Warp.whenSettled(() -> App.io.execute(() -> {
                    String err = Iptv.addPlaylist(url, name.getText().toString());
                    runOnUiThread(() -> {
                        toast(err == null ? "Channels added to the IPTV tab" : err);
                        build();
                    });
                }));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    void openIptv(String type, String key, String title, boolean typing) {
        openIptv(type, key, title, typing, false);
    }

    void openIptv(String type, String key, String title, boolean typing, boolean voice) {
        startActivity(new Intent(this, IptvActivity.class).putExtra("type", type).putExtra("key", key).putExtra("title", title).putExtra("typing", typing).putExtra("voice", voice));
    }

    /** One line: a newer version exists, with Install and Later. */
    View updateLine(String version) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(u(18, 14), u(6, 4), u(8, 6), u(6, 4));
        line.setBackground(rounded(CARD, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        line.setLayoutParams(lp);
        line.addView(text("Update " + version + " is available", sp(15, 13), TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView install = chip(null);
        install.setText("Install");
        install.setBackground(rounded(ACCENT, dp(22)));
        install.setOnClickListener(v -> {
            install.setEnabled(false);
            Update.install(status -> {
                boolean failed = status.startsWith("Update failed");
                install.setText(failed ? "Install" : status);
                install.setEnabled(failed);
                if (failed) toast(status);
            });
        });
        TextView later = chip(v -> { Update.skip(version); build(); });
        later.setText("Later");
        later.setTextColor(MUTED);
        line.addView(install);
        line.addView(later);
        if (firstCard == null) firstCard = install;
        return line;
    }

    void settingsTab() {
        col.addView(tabHeader("Settings"));
        col.addView(heading("Connection"));
        warpValue = new LocalizedTextView(this);
        col.addView(settingRow("WARP", "Cloudflare tunnel, only inside this app. Gets past ISP and DNS blocks.", warpValue, v -> {
            boolean on = Warp.state == Warp.State.ON || Warp.state == Warp.State.CONNECTING;
            App.prefs.edit().putBoolean("warp", !on).apply();
            if (on) Warp.stop(); else Warp.start();
        }));
        adValue = new LocalizedTextView(this);
        col.addView(settingRow("Ad block", "Blocks ad domains, pop-ups and redirects on every site.", adValue, v -> {
            App.prefs.edit().putBoolean("adblock", !AdBlock.enabled()).apply();
            updateChips();
        }));
        if (App.tv) {
            TextView cursor = new LocalizedTextView(this);
            col.addView(settingRow("Cursor on sites", "Arrow keys move a round cursor. Hold OK 5 s on a site to switch.", cursor, v -> {
                App.prefs.edit().putBoolean("pointer", !App.prefs.getBoolean("pointer", true)).apply();
                build();
            }));
            boolean on = App.prefs.getBoolean("pointer", true);
            cursor.setText(on ? "On" : "Off");
            cursor.setTextColor(on ? GREEN : MUTED);
        }

        col.addView(heading("Your data"));
        List<Sites.Site> favs = Sites.favorites();
        col.addView(settingRow("Clear favorites", favs.size() + (favs.size() == 1 ? " site" : " sites"), null, v -> confirmClearFavorites(favs.size())));
        int seen = History.list().size();
        col.addView(settingRow("Clear watch history", seen + (seen == 1 ? " title in Continue watching" : " titles in Continue watching"), null, v ->
            confirm("Clear watch history?", "Empties Continue watching.", "Clear", () -> { History.save(new ArrayList<>()); build(); toast("History cleared"); })));
        int watched = Iptv.history().size();
        col.addView(settingRow("Clear IPTV history", watched + (watched == 1 ? " channel" : " channels") + " in IPTV Continue watching", null, v ->
            confirm("Clear IPTV history?", "Empties Continue watching on the IPTV tab.", "Clear", () -> { App.prefs.edit().remove("iptvhist").apply(); build(); toast("IPTV history cleared"); })));
        int listed = Catalog.mine().size();
        col.addView(settingRow("Clear My list", listed + (listed == 1 ? " title" : " titles"), null, v ->
            confirm("Clear My list?", "Removes all " + listed + " titles you saved.", "Clear", () -> { App.prefs.edit().remove("mylist").apply(); build(); toast("My list cleared"); })));

        col.addView(heading("IPTV"));
        if (Iptv.adultAvailable()) {
            TextView adultValue = new LocalizedTextView(this);
            col.addView(settingRow("Adult channels", "Shows the Mature category on the IPTV tab. Off by default.", adultValue, v -> {
                App.prefs.edit().putBoolean("adult", !Iptv.adultOn()).apply();
                build();
            }));
            adultValue.setText(Iptv.adultOn() ? "On" : "Off");
            adultValue.setTextColor(Iptv.adultOn() ? GREEN : MUTED);
        }
        TextView extrasValue = new LocalizedTextView(this);
        col.addView(settingRow("Extra source list", "More playlists and title links, from a text file you choose (it starts on the project's own list).", extrasValue, v -> extrasDialog()));
        extrasValue.setText(Iptv.extrasUrl().isEmpty() ? "None" : Iptv.extrasUrl().equals(Iptv.DEFAULT_EXTRAS) ? "Default" : "Custom");
        extrasValue.setTextColor(Iptv.extrasUrl().isEmpty() ? MUTED : GREEN);
        col.addView(settingRow("Add a playlist or stream", "Paste a playlist or a single stream link (.m3u8), or sign in with Xtream. Each playlist gets its own row on the IPTV tab.", null, v -> addPlaylistDialog()));
        for (String url : Iptv.mine()) {
            int n = Iptv.parse(Iptv.read(Iptv.mineFile(url)), false).size();
            String name = Iptv.playlistName(url);
            col.addView(settingRow(name, n + (n == 1 ? " channel" : " channels") + ". Press to remove.", null, v ->
                confirm("Remove this playlist?", name, "Remove", () -> { Iptv.removePlaylist(url); build(); toast("Playlist removed"); })));
        }
        TextView deadValue = new LocalizedTextView(this);
        col.addView(settingRow("Hide offline channels", "Channels that failed a check or would not play stay out of the lists.", deadValue, v -> {
            App.prefs.edit().putBoolean("hideDead", !Iptv.hideDead()).apply();
            build();
        }));
        deadValue.setText(Iptv.hideDead() ? "On" : "Off");
        deadValue.setTextColor(Iptv.hideDead() ? GREEN : MUTED);
        col.addView(settingRow("Check channels now", Iptv.sweeping ? "Checking: " + Iptv.sweepDone + " of " + Iptv.sweepTotal : Iptv.deadCount() + " offline right now. Re-tests every channel from scratch.", null, v -> {
            if (Iptv.sweeping) { toast("Already checking"); return; }
            Iptv.health.clear();
            toast("Checking channels in the background\u2026");
            Iptv.sweep();
        }));
        col.addView(settingRow("Refresh IPTV channels", "Downloads the newest playlists from GitHub (also at app start, every 12 hours).", null, v -> {
            toast("Downloading channels…");
            App.io.execute(() -> {
                boolean ok = Iptv.refresh();
                runOnUiThread(() -> toast(ok ? "Channels updated" : "Can't reach GitHub. Check WARP."));
            });
        }));

        col.addView(heading("Player"));
        TextView subValue = new LocalizedTextView(this);
        col.addView(settingRow("Subtitles", "Size and position of subtitles in the player. Timing is on the player's Subtitles button.", subValue, v ->
            Subs.panel(this, false, () -> subValue.setText(Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel()), this::build)));
        subValue.setText(Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel());
        subValue.setTextColor(Subs.size() == 100 && Subs.offset() == 0 ? MUTED : GREEN);
        TextView simklValue = new LocalizedTextView(this);
        col.addView(settingRow("Simkl", "Marks movies and episodes you watch in the player as watched on Simkl, a free tracker. Titles opened from Movies, TV or Anime.", simklValue, v -> {
            if (Simkl.signedIn()) confirm("Disconnect Simkl?", "FMHYPlay stops telling Simkl what you watch. Your Simkl history stays.", "Disconnect", () -> { Simkl.signOut(); build(); });
            else simklDialog();
        }));
        simklValue.setText(Simkl.signedIn() ? "Connected" : "Connect");
        simklValue.setTextColor(Simkl.signedIn() ? GREEN : ACCENT);

        col.addView(heading("Updates"));
        String newer = Update.latest();
        TextView updValue = new LocalizedTextView(this);
        col.addView(settingRow("Check for updates", "FMHYPlay " + Update.installed() + ". Asks GitHub for a newer release and installs it on your say.", updValue, v -> checkUpdate(updValue)));
        updValue.setText(newer.isEmpty() ? "Up to date" : newer + " available");
        updValue.setTextColor(newer.isEmpty() ? MUTED : GREEN);
        TextView autoValue = new LocalizedTextView(this);
        col.addView(settingRow("Check at app start", "Looks for an update once every 12 hours.", autoValue, v -> {
            App.prefs.edit().putBoolean("updateCheck", !Update.auto()).apply();
            build();
        }));
        autoValue.setText(Update.auto() ? "On" : "Off");
        autoValue.setTextColor(Update.auto() ? GREEN : MUTED);

        col.addView(heading("Sites"));
        col.addView(settingRow("Refresh FMHY lists", "Checks FMHY for new and removed sites (also every 12 hours).", null, v -> {
            toast("Checking FMHY…");
            App.io.execute(() -> {
                boolean changed = Sites.refresh();
                runOnUiThread(() -> toast(changed ? "Site list updated" : "Site list is up to date"));
            });
        }));

        // Which build is this? (the TV and phone APKs share a package, so say it on screen)
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView about = text("FMHYPlay " + ver + "  ·  " + (App.tv ? "TV build" : "phone build") + "\nSite lists from FMHY. Titles and posters from Cinemeta.", sp(12, 11), MUTED, false);
        about.setPadding(0, u(28, 14), 0, 0);
        col.addView(about);
        updateChips();
    }

    /** Shows a code to enter at simkl.com/pin (where you can also make the free account) and waits for it. */
    void simklDialog() {
        TextView steps = text("Getting a code\u2026", sp(17, 15), TEXT, false), code = text("", sp(40, 32), TEXT, true);
        code.setLetterSpacing(0.15f);
        code.setPadding(0, dp(14), 0, dp(6));
        AlertDialog d = new LocalizedDialogBuilder(this).setTitle("Connect Simkl").setView(WebActivity.box(this, steps, code)).setNegativeButton("Cancel", null).create();
        boolean[] open = {true};
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
        App.io.execute(() -> {
            Simkl.Code c = Simkl.newCode();
            runOnUiThread(() -> {
                if (!open[0]) return;
                if (c == null) { steps.setText("Can't reach Simkl. Check WARP and try again."); return; }
                steps.setText("1. On your phone or computer, open " + c.page.replaceFirst("^https?://", "") + "\n2. Sign in, or make a free account there\n3. Enter this code:");
                code.setText(c.user);
                waitForSimkl(c, c.interval, d, open, steps);
            });
        });
    }

    void waitForSimkl(Simkl.Code c, int every, AlertDialog d, boolean[] open, TextView steps) {
        App.main.postDelayed(() -> {
            if (!open[0]) return;
            App.io.execute(() -> {
                String r = Simkl.check(c);
                runOnUiThread(() -> {
                    if (!open[0]) return;
                    switch (r) {
                        case "ok": d.dismiss(); toast("Simkl connected"); build(); break;
                        case "expired": steps.setText("The code ran out. Close this and press Simkl again for a new one."); break;
                        case "error": steps.setText("Simkl turned this down. Close this and try again."); break;
                        default: waitForSimkl(c, r.equals("slow") ? every + 5 : every, d, open, steps);
                    }
                });
            });
        }, every * 1000L);
    }

    /** Settings: ask GitHub now, then offer the install. */
    void checkUpdate(TextView value) {
        value.setText("Checking\u2026");
        App.io.execute(() -> {
            boolean ok = Update.check();
            runOnUiThread(() -> {
                String v = Update.latest();
                if (!ok) { value.setText("Can't reach GitHub"); toast("Can't reach GitHub. Check WARP."); return; }
                if (v.isEmpty()) { value.setText("Up to date"); toast("FMHYPlay " + Update.installed() + " is the latest"); return; }
                value.setText(v + " available");
                confirm("Update " + v + " is available", "Download it and install over this version?", "Install", () ->
                    Update.install(value::setText));
            });
        });
    }

    TextView warpValue, adValue;

    /** A full-width settings row: title and a line of help on the left, current state on the right. */
    View settingRow(String title, String help, TextView value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u(18, 14), u(14, 11), u(18, 14), u(14, 11));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, sp(17, 15), TEXT, true));
        TextView h = text(help, sp(13, 12), MUTED, false);
        h.setPadding(0, dp(2), 0, 0);
        left.addView(h);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) {
            value.setTextSize(sp(16, 14));
            value.setTypeface(Fonts.ui(this));
            value.setPadding(dp(12), 0, 0, 0);
            row.addView(value);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setBackground(rounded(CARD, dp(14)));
        focusable(row, dp(14));
        row.setOnClickListener(click);
        if (firstCard == null) firstCard = row;
        return row;
    }

    View posterCard(Catalog.Title t, int w) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(cardParams(w, w * 3 / 2));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text(t.name.substring(0, 1).toUpperCase(), sp(40, 30), 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        card.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (!t.poster.isEmpty()) loadImage(img, t.poster, letter);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xE6000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(9, 7), 0, u(9, 7), u(8, 6));
        TextView name = textRaw(t.name, sp(14, 12), Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        String meta = (t.year.isEmpty() ? "" : t.year) + (t.rating.isEmpty() ? "" : (t.year.isEmpty() ? "" : "  ·  ") + "★ " + t.rating);
        if (!meta.isEmpty()) info.addView(textRaw(meta, sp(12, 10), 0xFFD8D0E2, false));
        card.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        card.setBackground(rounded(colorFor(t.name), dp(12)));
        card.setClipToOutline(true);
        focusable(card, dp(12));
        card.setOnClickListener(v -> startActivity(new Intent(this, TitleActivity.class)
            .putExtra("id", t.id).putExtra("type", t.type).putExtra("name", t.name).putExtra("year", t.year).putExtra("poster", t.poster)));
        card.setOnLongClickListener(v -> {
            toast(Catalog.toggleMine(t) ? "Added to My list" : "Removed from My list");
            if (tab == 0 && tabs()) build();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        top.addView(logo, new LinearLayout.LayoutParams(u(44, 30), u(44, 30)));
        TextView name = text("FMHYPlay", sp(26, 20), TEXT, true);
        // Written like the project banner: FMHY in white, Play in the accent red
        android.text.SpannableString brand = new android.text.SpannableString("FMHYPlay");
        brand.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 4, 8, 0);
        name.setText(brand);
        name.setPadding(dp(4), 0, 0, 0);
        top.addView(name);
        h.addView(top);

        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, u(10, 4), 0, u(4, 0));
        TextView search = chip(v -> startActivity(new Intent(this, SearchActivity.class)));
        search.setText("Search");
        iconize(search, R.drawable.ic_search, TEXT);
        warpChip = chip(v -> {
            boolean on = Warp.state == Warp.State.ON || Warp.state == Warp.State.CONNECTING;
            App.prefs.edit().putBoolean("warp", !on).apply();
            if (on) Warp.stop(); else Warp.start();
        });
        adChip = chip(v -> {
            App.prefs.edit().putBoolean("adblock", !AdBlock.enabled()).apply();
            updateChips();
        });
        chips.addView(search);
        chips.addView(voiceChip(v -> startActivity(new Intent(this, SearchActivity.class).putExtra("voice", true))));
        chips.addView(warpChip);
        chips.addView(adChip);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(-slack, 0, -slack, 0);
        scroller.setLayoutParams(slp);
        scroller.setPadding(slack, 0, slack, 0);
        chips.setClipChildren(false);
        scroller.addView(chips);
        h.addView(scroller);
        if (App.tv) {
            TextView hint = text("Hold OK on a site for favorites and mirrors", 13, MUTED, false);
            hint.setPadding(dp(6), dp(2), 0, 0);
            h.addView(hint);
        }
        updateChips();
        return h;
    }

    /** Refreshes the WARP and ad block state shown on the Home chips and in Settings (whichever is on screen). */
    void updateChips() {
        // Icon and text share one colour: green = working, amber = starting, red = failed, grey = off
        String word;
        int color, icon = R.drawable.ic_shield;
        switch (Warp.state) {
            case ON: word = "on"; color = GREEN; icon = R.drawable.ic_shield_check; break;
            case CONNECTING: word = "connecting…"; color = AMBER; break;
            case FAILED: word = "failed, tap to retry"; color = ACCENT; break;
            default: word = "off"; color = MUTED;
        }
        boolean ads = AdBlock.enabled();
        if (warpChip != null) { warpChip.setText("WARP " + word); iconize(warpChip, icon, color); }
        if (adChip != null) { adChip.setText(ads ? "Ad block on" : "Ad block off"); iconize(adChip, R.drawable.ic_block, ads ? GREEN : MUTED); }
        if (warpValue != null) { warpValue.setText(word.substring(0, 1).toUpperCase() + word.substring(1)); warpValue.setTextColor(color); }
        if (adValue != null) { adValue.setText(ads ? "On" : "Off"); adValue.setTextColor(ads ? GREEN : MUTED); }
    }

    /** Puts a tinted icon in front of a chip's text and colours the text to match. */
    void iconize(TextView t, int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        int size = u(20, 17);
        d.setBounds(0, 0, size, size);
        d.setTint(color);
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(u(10, 7));
        t.setTextColor(color);
    }

    /** A round icon-only button (with a spoken label for screen readers). */
    ImageView iconButton(int res, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setColorFilter(MUTED);
        b.setContentDescription(Localization.translate(this, label));
        int pad = u(10, 8);
        b.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(44, 36), u(44, 36));
        lp.topMargin = u(14, 6);
        b.setLayoutParams(lp);
        focusable(b, dp(22));
        b.setOnClickListener(click);
        return b;
    }

    TextView chip(View.OnClickListener click) {
        TextView t = text("", sp(15, 13), TEXT, true);
        t.setPadding(u(16, 12), u(9, 6), u(16, 12), u(9, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(4), u(10, 6), dp(4));
        t.setLayoutParams(lp);
        focusable(t, dp(22));
        t.setOnClickListener(click);
        return t;
    }

    TextView heading(String s) {
        TextView t = text(s, sp(20, 16), TEXT, true);
        t.setPadding(0, u(22, 10), 0, u(6, 0));
        return t;
    }

    LinearLayout row() {
        return row(col);
    }

    LinearLayout row(LinearLayout parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        hs.setLayoutParams(lp);
        hs.setPadding(slack, u(10, 4), slack, u(12, 4));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row);
        parent.addView(hs);
        return row;
    }

    /** Site cards in whole rows across the screen (4 on a TV, 2 on a phone) inside `into`. */
    class Grid {
        final LinearLayout into;
        final int cols, gap = dp(gapDp), cell;   // cell = card width in dp
        LinearLayout row;
        int inRow;

        /** Site cards: 4 across on a TV, 2 on a phone. */
        Grid(LinearLayout into) {
            this(into, App.tv ? 4 : 2);
            cardW = cell;
        }

        Grid(LinearLayout into, int cols) {
            this.into = into;
            this.cols = cols;
            int width = getResources().getDisplayMetrics().widthPixels - 2 * side;
            cell = (int) Math.floor((width - gap * (cols - 1)) / (float) cols / getResources().getDisplayMetrics().density) - 1;
        }

        /** The next card starts a fresh row (after a heading). */
        void breakRow() {
            row = null;
            inRow = 0;
        }

        void add(Sites.Site s) {
            add(siteCard(s, true));
        }

        void add(View card) {
            if (row == null || inRow == cols) {
                row = new LinearLayout(Home.this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = gap;
                row.setLayoutParams(lp);
                into.addView(row);
                inRow = 0;
            }
            ((LinearLayout.LayoutParams) card.getLayoutParams()).setMargins(0, 0, inRow == cols - 1 ? 0 : gap, 0);
            row.addView(card);
            inRow++;
        }
    }

    View historyCard(History.Entry e) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(cardParams(App.tv ? 260 : 190, App.tv ? 146 : 108));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView letter = text(e.title.substring(0, 1).toUpperCase(), sp(44, 32), 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        card.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (!e.image.isEmpty()) loadImage(img, e.image, letter);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE0000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(12, 9), 0, u(12, 9), u(10, 7));
        TextView title = textRaw(e.title, sp(16, 14), Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        String ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
        info.addView(textRaw((e.episode.isEmpty() ? "" : e.episode + "  ·  ") + e.site + "  ·  " + ago, 12, 0xFFD8D0E2, false));
        card.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        card.setBackground(rounded(colorFor(e.site), dp(14)));
        card.setClipToOutline(true);
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(WebActivity.open(this, e.site, e.siteUrl, e.url)));
        card.setOnLongClickListener(v -> {
            new LocalizedDialogBuilder(this).setTitle(e.title)
                .setItems(new String[]{"Open " + e.site + " home", "Remove from Continue watching"}, (d, i) -> {
                    if (i == 0) startActivity(WebActivity.open(this, e.site, e.siteUrl, e.siteUrl));
                    else { History.remove(e); build(); }
                }).show();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    View siteCard(Sites.Site s, boolean markFavorite) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setPadding(u(16, 10), u(10, 6), u(16, 10), u(10, 6));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // Letter tile until (or unless) the site's icon loads
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(colorFor(s.name), dp(6)));
        TextView letter = text(s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase(), sp(14, 12), 0xCCFFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        tile.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = new ImageView(this);
        String host = String.valueOf(Uri.parse(s.url).getHost());
        tile.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        loadImage(icon, "https://www.google.com/s2/favicons?domain=" + host + "&sz=64", letter);
        top.addView(tile, new LinearLayout.LayoutParams(u(26, 22), u(26, 22)));
        TextView name = textRaw(s.name, sp(17, 15), TEXT, true);
        if (markFavorite && Sites.isFavorite(s.url)) {
            android.text.SpannableString star = new android.text.SpannableString("★  " + s.name);
            star.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 1, 0);
            name.setText(star);
        }
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(u(10, 8), 0, 0, 0);
        top.addView(name);
        card.addView(top);
        TextView tags = textRaw(s.tags.isEmpty() ? host : s.tags, sp(12, 11), MUTED, false);
        tags.setSingleLine();
        tags.setEllipsize(TextUtils.TruncateAt.END);
        tags.setPadding(0, u(6, 2), 0, 0);
        card.addView(tags);

        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> openSite(s));
        // Hold OK (or press Menu) for: favorite, mirrors, remove
        card.setOnLongClickListener(v -> {
            List<String> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            items.add(Sites.isFavorite(s.url) ? "☆  Remove from favorites" : "★  Add to favorites");
            actions.add(() -> { Sites.toggleFavorite(s); build(); });
            for (String m : s.mirrors) {
                items.add("Open mirror: " + Uri.parse(m).getHost());
                actions.add(() -> startActivity(WebActivity.open(this, s.name, m, m)));
            }
            new LocalizedDialogBuilder(this).setTitle(s.name)
                .setItems(items.toArray(new String[0]), (d, i) -> actions.get(i).run()).show();
            return true;
        });
        if (firstCard == null) firstCard = card;
        return card;
    }

    /** What tapping a site card does; the title page overrides it to search for the title there. */
    void openSite(Sites.Site s) {
        startActivity(WebActivity.open(this, s.name, s.url, s.url));
    }

    View languageCard(String language, int count) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setPadding(u(16, 10), u(10, 6), u(16, 10), u(10, 6));
        TextView name = text(language, sp(17, 15), TEXT, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        TextView n = text(count + (count == 1 ? " site" : " sites"), sp(12, 11), MUTED, false);
        n.setPadding(0, u(6, 2), 0, 0);
        card.addView(n);
        card.setBackground(rounded(CARD, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, CategoryActivity.class).putExtra("language", language)));
        return card;
    }

    View allCard(String category, int count) {
        TextView card = text("All " + count + "  ›", sp(17, 15), TEXT, true);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setBackground(rounded(0xFF2A2236, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> startActivity(new Intent(this, CategoryActivity.class).putExtra("category", category)));
        return card;
    }

    View addCard() {
        TextView card = text("+  Add a site", sp(17, 15), TEXT, true);
        card.setGravity(Gravity.CENTER);
        card.setLayoutParams(cardParams(cardW, cardH));
        card.setBackground(rounded(0xFF2A2236, dp(14)));
        focusable(card, dp(14));
        card.setOnClickListener(v -> {
            EditText in = WebActivity.field(this, "https://example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            new LocalizedDialogBuilder(this).setTitle("Add a site").setView(WebActivity.box(this, in))
                .setPositiveButton("Add", (d, w) -> {
                    if (in.getText().toString().trim().isEmpty()) return;
                    Sites.addCustom(in.getText().toString());
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        return card;
    }

    LinearLayout.LayoutParams cardParams(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(0, 0, dp(gapDp), 0);
        return lp;
    }

    // Focus = accent ring inside the card plus a lift; nothing grows, so nothing clips at the margins
    void focusable(View v, int radius) {
        v.setFocusable(true);
        v.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(dp(3), ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), states, rounded(Color.WHITE, radius)));
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().translationZ(has ? dp(8) : 0).setDuration(120).start();
            if (has) view.post(() -> reveal(view));
        });
        if (!(v.getBackground() instanceof GradientDrawable) && !(v instanceof FrameLayout)) v.setBackground(rounded(CARD, radius));
    }

    // Keep the focused card (grown + ring) fully on screen with a margin, and its row heading visible
    void reveal(View v) {
        // A delayed focus callback can outlive its card when the rows are rebuilt
        View up = v;
        while (up != null && up != page) up = up.getParent() instanceof View ? (View) up.getParent() : null;
        if (up == null) return;
        Rect r = new Rect();
        if (v.getParent() != null && v.getParent().getParent() instanceof HorizontalScrollView) {
            HorizontalScrollView hs = (HorizontalScrollView) v.getParent().getParent();
            v.getDrawingRect(r);
            hs.offsetDescendantRectToMyCoords(v, r);
            int x = hs.getScrollX(), w = hs.getWidth();
            if (r.left - x < slack) hs.smoothScrollTo(r.left - slack, 0);
            else if (r.right - x > w - slack) hs.smoothScrollTo(r.right - w + slack, 0);
        }
        v.getDrawingRect(r);
        page.offsetDescendantRectToMyCoords(v, r);
        int y = page.getScrollY(), h = page.getHeight();
        if (r.top - dp(64) < y) page.smoothScrollTo(0, Math.max(0, r.top - dp(64)));
        else if (r.bottom + dp(24) > y + h) page.smoothScrollTo(0, r.bottom + dp(24) - h);
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new LocalizedTextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Fonts.ui(this));
        return t;
    }

    TextView textRaw(String s, float sp, int color, boolean bold) {
        TextView t = new PlainTextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Fonts.ui(this));
        return t;
    }

    static int colorFor(String s) {
        float[] hsv = {Math.abs(s.hashCode()) % 360, 0.45f, 0.35f};
        return Color.HSVToColor(hsv);
    }

    // Small image loader: memory + disk cache, fetched through WARP
    void loadImage(ImageView into, String url, View placeholder) {
        into.setTag(url);
        Bitmap hit = images.get(url);
        if (hit != null) { show(into, hit, placeholder); return; }
        App.io.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            images.put(url, b);
            runOnUiThread(() -> { if (url.equals(into.getTag())) show(into, b, placeholder); });
        });
    }

    void show(ImageView into, Bitmap b, View placeholder) {
        into.setImageBitmap(b);
        if (placeholder != null) placeholder.setVisibility(View.GONE);
    }

    Bitmap fetch(String url) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder();
            for (byte x : sha) name.append(String.format("%02x", x));
            // Kept in app storage (not cache) so Continue watching posters survive cache clears
            File dir = new File(getFilesDir(), "img");
            dir.mkdirs();
            File f = new File(dir, name.toString());
            if (!f.exists()) {
                File tmp = new File(dir, name + ".tmp");
                try (InputStream in = Warp.open(url).getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(f)) return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 480) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    void toast(String s) {
        LocalizedToast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
