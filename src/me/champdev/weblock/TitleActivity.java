package me.champdev.weblock;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One movie or series: poster, details, episodes by season, and Watch on the site you picked (its search box is filled in for you). */
public class TitleActivity extends Home {
    final Catalog.Title t = new Catalog.Title();
    int season = 1;
    boolean refocus = true;   // TV: land on Watch when the screen first appears, not after every season click

    @Override boolean tabs() { return false; }

    @Override protected void onCreate(Bundle state) {
        t.id = getIntent().getStringExtra("id");
        t.type = getIntent().getStringExtra("type");
        t.name = getIntent().getStringExtra("name");
        t.year = String.valueOf(getIntent().getStringExtra("year"));
        t.poster = String.valueOf(getIntent().getStringExtra("poster"));
        super.onCreate(state);
        Warp.whenSettled(() -> App.io.execute(() -> {
            Catalog.fill(t);
            runOnUiThread(() -> {
                if (t.series() && Catalog.seasons(t) > 0 && season > Catalog.seasons(t)) season = 1;
                refocus = true;
                build();
            });
        }));
    }

    @Override protected void onResume() {
        refocus = true;
        super.onResume();
    }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;

        LinearLayout hero = new LinearLayout(this);
        int w = App.tv ? 190 : 112;
        FrameLayout poster = new FrameLayout(this);
        poster.setBackground(rounded(colorFor(t.name), dp(12)));
        poster.setClipToOutline(true);
        TextView letter = text(t.name.substring(0, 1).toUpperCase(), 40, 0x55FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        poster.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (!t.poster.isEmpty() && !t.poster.equals("null")) loadImage(img, t.poster, letter);
        hero.addView(poster, new LinearLayout.LayoutParams(dp(w), dp(w * 3 / 2)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(u(24, 14), 0, 0, 0);
        TextView name = textRaw(t.name, sp(30, 21), TEXT, true);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);
        List<String> meta = new ArrayList<>();
        if (!t.year.isEmpty() && !t.year.equals("null")) meta.add(t.year);
        if (!t.runtime.isEmpty()) meta.add(t.runtime);
        if (!t.rating.isEmpty()) meta.add("★ " + t.rating);
        TextView line = textRaw(String.join("  ·  ", meta), sp(15, 13), MUTED, false);
        line.setPadding(0, dp(6), 0, 0);
        info.addView(line);
        if (!t.genres.isEmpty()) {
            TextView genres = textRaw(t.genres, sp(14, 12), MUTED, false);
            genres.setPadding(0, dp(3), 0, 0);
            info.addView(genres);
        }
        if (App.tv) {
            TextView overview = textRaw(t.overview, 15, TEXT, false);
            overview.setPadding(0, dp(12), 0, 0);
            overview.setMaxLines(6);
            overview.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(overview);
        }
        hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(hero);

        if (!App.tv && !t.overview.isEmpty()) {
            TextView overview = textRaw(t.overview, 13, TEXT, false);
            overview.setPadding(0, dp(12), 0, 0);
            col.addView(overview);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, u(18, 12), 0, u(4, 2));
        Sites.Site site = chosen();
        TextView watch = chip(v -> watch(null));
        watch.setText(site == null ? "No site available" : "▶  Watch on " + site.name);
        watch.setTextColor(0xFFFFFFFF);
        watch.setBackground(rounded(ACCENT, dp(22)));
        focusable(watch, dp(22));
        TextView list = chip(null);
        boolean mine = Catalog.isMine(t.id);
        list.setText(mine ? "✓  In My list" : "+  My list");
        list.setOnClickListener(v -> { toast(Catalog.toggleMine(t) ? "Added to My list" : "Removed from My list"); build(); });
        buttons.addView(watch);
        buttons.addView(list);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(-slack, 0, -slack, 0);
        scroller.setLayoutParams(slp);
        scroller.setPadding(slack, 0, slack, 0);
        buttons.setClipChildren(false);
        scroller.addView(buttons);
        col.addView(scroller);
        if (firstCard == null) firstCard = watch;

        // The sites that carry this kind of title: tap one and its own search box is filled in with the title
        List<Sites.Site> sites = options();
        if (!sites.isEmpty()) {
            col.addView(heading("Watch on  ·  tap a site to open “" + t.name + "”"));
            Grid grid = new Grid(col);
            for (Sites.Site s : sites) grid.add(s);
        }
        if (t.series() && !t.episodes.isEmpty()) episodes();
        if (App.tv && refocus) col.postDelayed(watch::requestFocus, 60);
        refocus = false;
    }

    void episodes() {
        col.addView(heading("Episodes"));
        LinearLayout seasons = new LinearLayout(this);
        seasons.setClipChildren(false);
        for (int s = 1; s <= Catalog.seasons(t); s++) {
            final int n = s;
            TextView c = chip(v -> { season = n; build(); });
            c.setText("Season " + s);
            c.setTextColor(s == season ? 0xFFFFFFFF : TEXT);
            c.setBackground(rounded(s == season ? ACCENT : CARD, dp(22)));
            focusable(c, dp(22));
            seasons.addView(c);
        }
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(-slack, 0, -slack, 0);
        sc.setLayoutParams(lp);
        sc.setPadding(slack, 0, slack, dp(6));
        sc.addView(seasons);
        col.addView(sc);

        for (Catalog.Episode e : t.episodes) {
            if (e.season != season) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(u(12, 8), u(10, 7), u(14, 10), u(10, 7));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(colorFor(e.title), dp(8)));
            thumb.setClipToOutline(true);
            if (!e.thumb.isEmpty()) loadImage(thumb, e.thumb, null);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(App.tv ? 160 : 100), dp(App.tv ? 90 : 56)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(u(16, 10), 0, 0, 0);
            String episodeTitle = e.title.isEmpty()
                ? e.number + "  " + Localization.translate(this, "Episode " + e.number)
                : e.number + "  " + e.title;
            TextView title = textRaw(episodeTitle, sp(16, 14), TEXT, true);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);
            if (!e.overview.isEmpty()) {
                TextView o = textRaw(e.overview, sp(13, 12), MUTED, false);
                o.setMaxLines(2);
                o.setEllipsize(TextUtils.TruncateAt.END);
                o.setPadding(0, dp(2), 0, 0);
                text.addView(o);
            }
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(8);
            row.setLayoutParams(rp);
            row.setBackground(rounded(CARD, dp(14)));
            focusable(row, dp(14));
            row.setOnClickListener(v -> watch(e));
            col.addView(row);
        }
    }

    /** The site picked last time, else your first favorite, else FMHY's first pick for this kind of title. */
    List<Sites.Site> options() {
        List<Sites.Site> out = new ArrayList<>(Sites.favorites());
        List<Sites.Site> row = Sites.load().get(t.series() ? Cats.TV : Cats.MOVIES);
        if (row != null) for (Sites.Site s : row) if (!Sites.has(out, s.url) && out.size() < 30) out.add(s);
        return out;
    }

    Sites.Site chosen() {
        List<Sites.Site> options = options();
        String url = App.prefs.getString("watchsite", "");
        for (Sites.Site s : options) if (s.url.equals(url)) return s;
        return options.isEmpty() ? null : options.get(0);
    }

    @Override void openSite(Sites.Site s) {
        App.prefs.edit().putString("watchsite", s.url).apply();
        startActivity(withTitle(titleIntent(s)));
    }

    // The title's own page when the site is known (by TMDB id), else the site with a search for its name
    android.content.Intent titleIntent(Sites.Site s) {
        String link = Sites.deeplink(s.url, t);
        android.content.Intent in = WebActivity.open(this, s.name, s.url, link != null ? link : s.url);
        return link != null ? in : in.putExtra("q", t.name);
    }

    void watch(Catalog.Episode e) {
        Sites.Site s = chosen();
        if (s == null) { toast("No site to watch on"); return; }
        if (e != null) toast("Season " + e.season + ", episode " + e.number + ": pick it on the site");
        startActivity(withTitle(titleIntent(s))
            .putExtra("season", e == null ? 0 : e.season).putExtra("episode", e == null ? 0 : e.number));
    }

    // What the native player needs to look up subtitles for this title
    android.content.Intent withTitle(android.content.Intent in) {
        return in.putExtra("imdb", t.id).putExtra("series", t.series());
    }
}
