package me.champdev.weblock;

import android.widget.TextView;

import java.util.List;

/** Every site FMHY lists for one row ("category") or one language, as a grid (picks first). Same cards and menus as Home. */
public class CategoryActivity extends Home {
    @Override boolean tabs() { return false; }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        String language = getIntent().getStringExtra("language"), category = getIntent().getStringExtra("category");
        String name = language != null ? language : category;
        List<Sites.Site> sites = language != null ? Sites.languages().get(language) : Sites.load().get(category);

        String displayName = Localization.translate(this, name).toString();
        TextView title = text(displayName + (sites == null ? "" : "  ·  " + sites.size()), sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        col.addView(title);
        TextView hint = text(App.tv ? "Hold OK on a site for favorites and mirrors" : "Long-press a site for favorites and mirrors", 13, MUTED, false);
        hint.setPadding(0, 0, 0, dp(14));
        col.addView(hint);
        if (sites == null) return;

        Grid grid = new Grid(col);
        String group = null;
        for (Sites.Site s : sites) {
            // Picks first, then one block per FMHY section (P-Stream forks, multi-server sites... each with its own heading)
            String now = s.pick ? "FMHY picks" : s.index ? "More lists of sites" : language != null ? "More sites" : s.group;
            if (!now.equals(group)) {
                group = now;
                col.addView(heading(now));
                grid.breakRow();
            }
            grid.add(s);
        }
        if (App.tv && firstCard != null) col.postDelayed(firstCard::requestFocus, 60);
    }
}
