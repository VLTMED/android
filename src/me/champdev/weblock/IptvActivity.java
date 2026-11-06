package me.champdev.weblock;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One IPTV group (a category, a country, favorites, adult, or everything) as a filterable grid of square tiles. OK plays, hold OK or Menu stars a channel. */
public class IptvActivity extends Home {
    final List<Iptv.Channel> all = new ArrayList<>(), shown = new ArrayList<>();
    String type, key;
    GridView list;
    int cell;   // tile edge in px
    String query = "";
    TextView count;
    BaseAdapter adapter;

    @Override boolean tabs() { return false; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);   // Home draws its own page first; replaced below
        type = getIntent().getStringExtra("type");
        key = getIntent().getStringExtra("key");
        all.addAll(Iptv.list(type, key));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(side, App.tv ? dp(28) : dp(10), side, 0);
        TextView title = text(getIntent().getStringExtra("title"), sp(26, 22), TEXT, true);
        root.addView(title);
        count = text("", sp(14, 12), MUTED, false);
        count.setPadding(0, dp(2), 0, dp(8));
        root.addView(count);

        EditText filter = new LocalizedEditText(this);
        filter.setHint(Localization.translate(this, "Filter by name"));
        filter.setHintTextColor(MUTED);
        filter.setTextColor(TEXT);
        filter.setTextSize(sp(16, 15));
        filter.setSingleLine();
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.setBackground(rounded(CARD, dp(12)));
        filter.setPadding(dp(14), dp(10), dp(14), dp(10));
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }

            @Override public void afterTextChanged(Editable s) { }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(filter, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView mic = iconButton(R.drawable.ic_mic, "Voice search", v -> listen(filter::setText));
        ((LinearLayout.LayoutParams) mic.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
        mic.setColorFilter(TEXT);
        bar.addView(mic);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        // Square tiles, 6 across on a TV and 3 on a phone
        int cols = App.tv ? 6 : 3, gap = dp(gapDp);
        cell = (getResources().getDisplayMetrics().widthPixels - 2 * side - gap * (cols - 1)) / cols - 1;
        list = new GridView(this);
        list.setNumColumns(cols);
        list.setHorizontalSpacing(gap);
        list.setVerticalSpacing(gap);
        list.setClipToPadding(false);
        list.setPadding(0, dp(10), 0, dp(20));
        GradientDrawable ring = rounded(0x22FF3B5C, dp(14));
        ring.setStroke(dp(3), ACCENT);
        list.setSelector(ring);
        list.setDrawSelectorOnTop(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }

            @Override public Object getItem(int i) { return shown.get(i); }

            @Override public long getItemId(int i) { return i; }

            @Override public View getView(int i, View v, ViewGroup parent) { View t = channelTile(shown.get(i), v, cell); t.setLayoutParams(new AbsListView.LayoutParams(cell, cell)); return t; }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((a, v, i, id) -> Iptv.play(this, shown.get(i)));
        list.setOnItemLongClickListener((a, v, i, id) -> { star(shown.get(i)); return true; });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        filter("");
        if (getIntent().getBooleanExtra("voice", false)) {
            list.requestFocus();
            list.post(() -> listen(filter::setText));
        } else if (getIntent().getBooleanExtra("typing", false)) {
            filter.requestFocus();
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        } else list.requestFocus();
    }

    @Override void build() {
        if (adapter == null) return;
        all.clear();
        all.addAll(Iptv.list(type, key));   // a star or a channel that just failed may have changed it
        filter(query);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        int i = list == null ? -1 : list.getSelectedItemPosition();
        if (k == KeyEvent.KEYCODE_MENU && list.hasFocus() && i >= 0 && i < shown.size()) { star(shown.get(i)); return true; }
        return super.onKeyUp(k, e);
    }

    void filter(String q) {
        query = q;
        String f = q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Iptv.Channel c : all) if (f.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(f) || c.source.toLowerCase(Locale.ROOT).contains(f) || c.group.toLowerCase(Locale.ROOT).contains(f)) shown.add(c);
        adapter.notifyDataSetChanged();
        count.setText(shown.size() + (shown.size() == 1 ? " channel" : " channels") + (App.tv ? "  ·  OK plays, hold OK to star" : "  ·  tap plays, long-press stars"));
    }

    void star(Iptv.Channel c) {
        Iptv.toggleFavorite(c);
        toast(Iptv.isFavorite(c) ? "Starred " + c.name : "Unstarred " + c.name);
        if (type.equals("fav")) { all.remove(c); filter(""); } else adapter.notifyDataSetChanged();
    }
}
