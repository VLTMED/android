package me.champdev.weblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONObject;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Plays the stream a site started in a real ExoPlayer (hardware decoder, no web page painted over it). Remote: OK pause, left/right seek, up controls, down settings. */
public class PlayerActivity extends Activity {
    static final String TAG = "player";
    // Where each stream was left, so Back to the page and "Play natively" again picks up there (until the app is closed)
    static final Map<String, Long> resume = new HashMap<>();
    // A server/quality switch happens on the page and starts a new stream: keep the position across it
    static long carryMs, carryAt;
    static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    ExoPlayer player;
    SurfaceView surface;
    SubtitleTextView subtitle;
    TextView title, time, timeTotal, notice, speedBoostLabel;
    float speedBeforeHold = 1f;
    boolean speedBoostActive;
    ProgressBar spinner;
    ImageView playPause;
    SeekBar seek;
    View controls;
    String base;
    ProxySelector previousProxy;
    boolean dragging;
    long pending = -1; // where the last seek key press is heading; the real seek happens once the key rests
    float videoRatio;
    long hideAt;
    LinearLayout chipRow;
    final List<LinearLayout> chips = new ArrayList<>();
    LinearLayout chipServers, chipQuality, chipAudio, chipSubs, chipSpeed, chipZoom;
    String chipLayoutKey = "";
    LinearLayout topBar, bottomBar;      // padded for notches; the centre buttons stay centred
    View rew, fwd;                       // the -10 / +10 buttons beside play
    TextView epLine;                     // "Season 1 · Episode 3" under the title
    LinearLayout gesture;                // brightness / volume swipe indicator
    ImageView gestureIcon;
    ProgressBar gestureBar;
    TextView gestureText;
    int zoom = 0;   // Fit at the start of every playback (the choice is not remembered)
    static final String[] ZOOMS = {"Fit", "Fill", "Stretch", "16:9", "4:3"};
    MediaItem.Builder mediaItem;   // kept so an OpenSubtitles pick can be added to the playing stream
    List<MediaItem.SubtitleConfiguration> subList;

    private float shownFps;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            if (Math.round(measuredFps) != Math.round(shownFps)) { shownFps = measuredFps; refreshChips(); }
            App.main.postDelayed(this, 500);
        }
    };

    static android.content.Intent open(android.content.Context c, String url, boolean dash, Map<String, String> headers, List<String> subs, String title) {
        return new android.content.Intent(c, PlayerActivity.class).putExtra("url", url).putExtra("dash", dash)
            .putExtra("headers", new JSONObject(headers).toString()).putStringArrayListExtra("subs", new ArrayList<>(subs)).putExtra("title", title);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = getIntent().getStringExtra("url");
        if (url == null) { finish(); return; }
        base = Sniffer.path(url);
        Subs.delayMs = 0;   // timing belongs to one video
        buildUi();
        routeThroughWarp();
        startPlayer(url);
    }

    int dp(float v) { return Home.dp(this, v); }

    /** A round, semi-transparent icon button (back, play, +/-10). */
    ImageView circle(int icon, int sizeDp, Runnable click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(Color.WHITE);
        int pad = dp(sizeDp / 4f);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        b.setBackground(bg);
        b.setFocusable(false);
        b.setOnClickListener(v -> click.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    /** -10 / +10: the round arrow with the number inside. */
    View skip(int icon, long ms) {
        FrameLayout f = new FrameLayout(this);
        ImageView b = circle(icon, 64, () -> seekBy(ms));
        f.addView(b, new FrameLayout.LayoutParams(dp(64), dp(64)));
        TextView n = new LocalizedTextView(this);
        n.setText("10");
        n.setTextColor(Color.WHITE);
        n.setTextSize(12);
        n.setTypeface(Fonts.ui(this));
        n.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER);
        lp.topMargin = dp(2);
        f.addView(n, lp);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(64), dp(64));
        ll.setMargins(dp(24), 0, dp(24), 0);
        f.setLayoutParams(ll);
        return f;
    }

    void showCues(CueGroup group) {
        StringBuilder sb = new StringBuilder();
        for (Cue c : group.cues) if (c.text != null) sb.append(sb.length() > 0 ? "\n" : "").append(c.text);
        ((LocalizedTextView) subtitle).setTextUnlocalized(sb);
        subtitle.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Subtitle size and distance from the bottom: 22 sp and 100 dp (clear of the controls), changed by the Subtitle size and position settings. */
    void placeSubtitle() {
        Subs.applyAppearance(subtitle, this);
        FrameLayout.LayoutParams subtitleLp = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
        subtitleLp.gravity = Gravity.BOTTOM | Subs.alignmentGravity();
        subtitleLp.setMargins(dp(20), 0, dp(20), dp(Math.max(0, 100 + Subs.offset())));
        subtitle.requestLayout();
    }

    /** The subtitle panel (size, position, timing), with a sample line on screen when nothing is being said right now. */
    void subtitleStyle() {
        if (subtitle.getVisibility() != View.VISIBLE) { subtitle.setText("Subtitles show here"); subtitle.setVisibility(View.VISIBLE); }
        Subs.panel(this, true, this::placeSubtitle, () -> showCues(player.getCurrentCues()));
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());

        subtitle = new SubtitleTextView(this);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setVisibility(View.GONE);
        root.addView(subtitle, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        placeSubtitle();

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        notice = new LocalizedTextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(18);
        notice.setBackgroundColor(0xAA000000);
        notice.setPadding(dp(14), dp(6), dp(14), dp(6));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        np.topMargin = dp(80);
        root.addView(notice, np);

        // Swipe indicator (brightness on the left half, volume on the right half)
        gesture = new LinearLayout(this);
        gesture.setOrientation(LinearLayout.VERTICAL);
        gesture.setGravity(Gravity.CENTER_HORIZONTAL);
        gesture.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable gbg = new GradientDrawable();
        gbg.setCornerRadius(dp(18));
        gbg.setColor(0xB0000000);
        gesture.setBackground(gbg);
        gestureIcon = new ImageView(this);
        gestureIcon.setColorFilter(Color.WHITE);
        gesture.addView(gestureIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        gestureBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        gestureBar.setMax(100);
        gestureBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(110), dp(6));
        gp.topMargin = dp(10);
        gesture.addView(gestureBar, gp);
        gestureText = new LocalizedTextView(this);
        gestureText.setTextColor(Color.WHITE);
        gestureText.setTextSize(13);
        gestureText.setPadding(0, dp(6), 0, 0);
        gesture.addView(gestureText);
        gesture.setVisibility(View.GONE);
        root.addView(gesture, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        speedBoostLabel = new LocalizedTextView(this);
        speedBoostLabel.setText("2×");
        speedBoostLabel.setTextColor(Color.WHITE);
        speedBoostLabel.setTextSize(24);
        speedBoostLabel.setTypeface(Fonts.ui(this));
        speedBoostLabel.setGravity(Gravity.CENTER);
        speedBoostLabel.setPadding(dp(18), dp(10), dp(18), dp(10));
        speedBoostLabel.setBackground(Home.rounded(0xE6000000, dp(18)));
        speedBoostLabel.setElevation(dp(8));
        speedBoostLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams speedLabelLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        speedLabelLp.topMargin = dp(110);
        root.addView(speedBoostLabel, speedLabelLp);

        // Everything that hides after a few seconds lives in one overlay
        FrameLayout overlay = new FrameLayout(this);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(24);
        top.setPadding(pad, dp(10), pad, dp(26));
        topBar = top;
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        ImageView back = circle(R.drawable.ic_p_back, 48, this::finish);
        top.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        title = new PlainTextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // A page's title carries the site name ("Game of Thrones | Movy"): show just the title
        String shown = getIntent().getStringExtra("title");
        if (getIntent().hasExtra("pageTitle")) {
            String clean = History.cleanTitle(String.valueOf(getIntent().getStringExtra("pageTitle")), String.valueOf(getIntent().getStringExtra("site")));
            if (!clean.isEmpty()) shown = clean;
        }
        title.setText(shown);
        titles.addView(title);
        epLine = new LocalizedTextView(this);
        epLine.setTextColor(0xCCFFFFFF);
        epLine.setTextSize(13);
        epLine.setSingleLine();
        epLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        epLine.setVisibility(View.GONE);
        titles.addView(epLine);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // -10  PLAY  +10
        LinearLayout cluster = new LinearLayout(this);
        cluster.setGravity(Gravity.CENTER);
        rew = skip(R.drawable.ic_p_replay, -10000);
        playPause = circle(R.drawable.ic_p_play, 88, this::toggle);
        playPause.setColorFilter(Color.WHITE);
        fwd = skip(R.drawable.ic_p_forward, 10000);
        cluster.addView(rew);
        cluster.addView(playPause);
        cluster.addView(fwd);
        overlay.addView(cluster, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(pad, dp(30), pad, dp(10));
        bottomBar = bar;
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        seek.setFocusable(false);
        seek.setPadding(dp(14), dp(14), dp(14), dp(14));   // a finger-sized strip to grab
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) pending = p;
            }

            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }

            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; commitSeek(); }
        });
        time = new LocalizedTextView(this);
        time.setTextColor(Color.WHITE);
        time.setTextSize(14);
        time.setPadding(0, 0, dp(10), 0);
        row.addView(time);
        row.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        timeTotal = new LocalizedTextView(this);
        timeTotal.setTextColor(Color.WHITE);
        timeTotal.setTextSize(14);
        timeTotal.setPadding(dp(10), 0, 0, 0);
        row.addView(timeTotal);
        bar.addView(row);
        // All actions sit on the bar itself as round icon buttons, spread evenly (never wider than the screen):
        // Down puts the remote on them, Left/Right moves, OK opens the choice, the label shows at the top
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.VERTICAL);
        chipRow.setGravity(Gravity.CENTER_HORIZONTAL);
        chipRow.setPadding(0, dp(6), 0, 0);
        chipServers = chip(R.drawable.ic_p_servers, () -> pickServer());
        chipQuality = chip(R.drawable.ic_p_quality, this::pickQuality);
        chipAudio = chip(R.drawable.ic_p_audio, () -> pickTrack(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO, "Audio", false));
        chipSubs = chip(R.drawable.ic_p_subs, () -> pickTrack(player.getCurrentTracks(), C.TRACK_TYPE_TEXT, "Subtitles", true));
        chipSpeed = chip(R.drawable.ic_p_speed, this::pickSpeed);
        chipZoom = chip(R.drawable.ic_p_zoom, this::cycleZoom);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        bar.addView(chipRow, new LinearLayout.LayoutParams(screenW - 2 * pad, -2));
        ((LinearLayout.LayoutParams) chipRow.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        overlay.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        controls = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        speedBoostLabel.bringToFront();

        if (android.os.Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l = 0, t = 0, r = 0, bt = 0;
            if (android.os.Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                l = in.getDisplayCutout().getSafeInsetLeft();
                t = in.getDisplayCutout().getSafeInsetTop();
                r = in.getDisplayCutout().getSafeInsetRight();
                bt = in.getDisplayCutout().getSafeInsetBottom();
            }
            int pad2 = dp(24);
            topBar.setPadding(pad2 + l, dp(10) + t, pad2 + r, dp(26));
            bottomBar.setPadding(pad2 + l, dp(30), pad2 + r, dp(10) + bt);
            return in;
        });
        root.setOnClickListener(v -> { if (controls.getVisibility() == View.VISIBLE) hideControls(); else showControls(); });
        root.setOnLongClickListener(v -> beginHoldSpeed());
        root.setOnTouchListener(this::swipe);
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---- swipe: up/down on the left half = brightness, on the right half = volume ----

    float downX, downY, startLevel;
    int swipeKind;   // 0 undecided, 1 brightness, 2 volume, -1 not a vertical swipe

    float brightnessNow() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (android.provider.Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    float volumeNow() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private android.view.GestureDetector taps;

    boolean swipe(View v, MotionEvent ev) {
        if (taps == null) taps = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                float third = v.getWidth() / 3f;
                if (e.getX() < third) seekBy(-10000);
                else if (e.getX() > 2 * third) seekBy(10000);
                else toggle();
                return true;
            }
        });
        taps.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swipeKind = 0;
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (swipeKind < 0) return false;
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                if (swipeKind == 0) {
                    float slop = dp(14);
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) { swipeKind = -1; return false; }
                    if (Math.abs(dy) < slop) return false;
                    if (downX < dp(28) || downX > v.getWidth() - dp(28)) { swipeKind = -1; return false; }
                    swipeKind = downX < v.getWidth() / 2f ? 1 : 2;
                    startLevel = swipeKind == 1 ? brightnessNow() : volumeNow();
                    MotionEvent c = MotionEvent.obtain(ev);   // the view saw a press: end it so it does not count as a tap
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    v.onTouchEvent(c);
                    c.recycle();
                }
                float level = Math.max(swipeKind == 1 ? 0.02f : 0f, Math.min(1f, startLevel - dy / (v.getHeight() * 0.7f)));
                if (swipeKind == 1) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = level;
                    getWindow().setAttributes(lp);
                } else {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
                }
                gestureIcon.setImageResource(swipeKind == 1 ? R.drawable.ic_p_sun : R.drawable.ic_p_volume);
                gestureBar.setProgress(Math.round(level * 100));
                gestureText.setText(Math.round(level * 100) + "%");
                FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) gesture.getLayoutParams();
                gl.gravity = (swipeKind == 1 ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
                gl.setMargins(dp(48), 0, dp(48), 0);
                gesture.setLayoutParams(gl);
                gesture.setVisibility(View.VISIBLE);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = swipeKind > 0;
                swipeKind = 0;
                if (was) App.main.postDelayed(() -> gesture.setVisibility(View.GONE), 600);
                if (speedBoostActive) { endHoldSpeed(); return false; }
                return was;
            }
            default:
                return swipeKind > 0;
        }
    }

    private boolean beginHoldSpeed() {
        if (player == null || !player.isPlaying() || speedBoostActive) return speedBoostActive;
        speedBeforeHold = player.getPlaybackParameters().speed;
        speedBoostActive = true;
        player.setPlaybackSpeed(2f);
        speedBoostLabel.setVisibility(View.VISIBLE);
        refreshChips();
        return true;
    }

    private void endHoldSpeed() {
        if (!speedBoostActive) return;
        speedBoostActive = false;
        if (player != null) {
            player.setPlaybackSpeed(speedBeforeHold);
            refreshChips();
        }
        if (speedBoostLabel != null) speedBoostLabel.setVisibility(View.GONE);
    }

    /** A pill button: its icon and, beside it, its current value ("720p", "1x", "EN", "Fit"). Only the icon when it has no value. */
    LinearLayout chip(int icon, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        ImageView img = new ImageView(this);
        img.setImageResource(icon);
        img.setColorFilter(Color.WHITE);
        t.addView(img, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView val = new LocalizedTextView(this);
        val.setTextColor(Color.WHITE);
        val.setTextSize(14);
        val.setTypeface(Fonts.ui(this));
        val.setSingleLine();
        val.setPadding(dp(6), 0, 0, 0);
        val.setVisibility(View.GONE);
        t.addView(val);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = new GradientDrawable(), off = new GradientDrawable();
        on.setCornerRadius(dp(22));
        off.setCornerRadius(dp(22));
        on.setColor(Home.ACCENT);
        off.setColor(0x44FFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{android.R.attr.state_pressed}, on);
        bg.addState(new int[0], off);
        t.setBackground(bg);
        t.setClickable(true);
        t.setFocusable(false);
        t.setOnClickListener(v -> { showControls(); action.run(); });
        chips.add(t);
        return t;
    }

    /**
     * Show or hide a button, give it its pop-up label, and put its current value beside the icon.
     * changed = the setting is not the default, so it is tinted.
     */
    void show(LinearLayout t, boolean visible, String label, boolean changed, String value) {
        t.setVisibility(visible ? View.VISIBLE : View.GONE);
        t.setContentDescription(label);
        int color = changed ? 0xFFFF6B86 : Color.WHITE;
        ImageView img = (ImageView) t.getChildAt(0);
        TextView val = (TextView) t.getChildAt(1);
        img.setColorFilter(color);
        val.setTextColor(color);
        boolean text = value != null && !value.isEmpty();
        val.setVisibility(text ? View.VISIBLE : View.GONE);
        val.setText(text ? value : "");
        int side = 12;
        t.setPadding(dp(text ? side : side - 2), 0, dp(text ? side + 2 : side - 2), 0);
    }

    static void show(TextView t, boolean visible, String text) {
        t.setVisibility(visible ? View.VISIBLE : View.GONE);
        t.setText(text);
    }

    static String labelOf(androidx.media3.common.Format f, String name, int n) {
        return f.label != null ? f.label : f.language != null && !f.language.equals("und") ? new Locale(f.language).getDisplayLanguage() : name + " " + n;
    }

    /** "1080p" for 1080, "4K" for 2160 and up, "" when unknown. */
    static String heightLabel(int h) {
        return h <= 0 ? "" : h >= 2160 ? "4K" : h + "p";
    }

    /** "1080p60": the height and, when the frame rate is known, the rate rounded (23.976 is 24, 59.94 is 60). */
    static String qualityLabel(int h, float fps) {
        String base = heightLabel(h);
        return base.isEmpty() || fps <= 0 ? base : base + Math.round(fps);
    }

    // The stream's frame rate, read off the frames' own timestamps: the shortest gap between two frames in a row. A slow decoder that
    // drops frames makes the gaps longer, never shorter, so the shortest gap is still the true frame duration.
    volatile float measuredFps;
    private long fpsLastUs = -1, fpsMinGapUs = Long.MAX_VALUE;
    private int fpsFrames;
    static final float[] RATES = {23.976f, 24, 25, 29.97f, 30, 48, 50, 59.94f, 60, 100, 119.88f, 120};
    final androidx.media3.exoplayer.video.VideoFrameMetadataListener fpsMeter = (ptsUs, releaseNs, format, media) -> {
        long gap = ptsUs - fpsLastUs;
        fpsLastUs = ptsUs;
        if (gap > 4000 && gap < 200000) fpsMinGapUs = Math.min(fpsMinGapUs, gap);   // 5 to 250 frames a second; seeks and splices give odd gaps
        if (++fpsFrames < 40 || fpsMinGapUs == Long.MAX_VALUE) return;
        float fps = 1_000_000f / fpsMinGapUs, best = fps;
        for (float r : RATES) if (Math.abs(r - fps) / r < 0.03f) best = r;   // snap to a standard rate
        measuredFps = Math.max(measuredFps, best);   // a decoder that falls behind only ever reads low: keep the highest reading for this quality
        fpsMinGapUs = Long.MAX_VALUE;
        fpsFrames = 0;
    };

    /** A two or three letter code for a track: its language ("EN"), else the start of its label. */
    static String codeOf(androidx.media3.common.Format f) {
        if (f.language != null && !f.language.equals("und")) return f.language.substring(0, Math.min(2, f.language.length())).toUpperCase(Locale.ROOT);
        return f.label == null ? "" : f.label.substring(0, Math.min(3, f.label.length())).toUpperCase(Locale.ROOT);
    }

    // What each button shows right now: the actual quality, audio, subtitles, speed, zoom, server and episode
    void refreshChips() {
        if (player == null) return;
        Tracks t = player.getCurrentTracks();
        WebActivity page = WebActivity.active;
        String cur = "";
        if (page != null) for (String[] v : page.servers.values()) if (!v[1].isEmpty()) cur = v[0];
        show(chipServers, page != null && !page.servers.isEmpty(), "Servers" + (cur.isEmpty() ? "" : " \u00B7 " + cur), false,
            cur.length() > 6 ? cur.substring(0, 6) : cur);
        int video = 0, audio = 0, text = 0;
        String audioNow = "", textNow = "Off", audioCode = "", textCode = "";
        for (Tracks.Group g : t.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.getType() == C.TRACK_TYPE_VIDEO) video++;
                else if (g.getType() == C.TRACK_TYPE_AUDIO) { audio++; if (g.isTrackSelected(i)) { audioNow = labelOf(g.getTrackFormat(i), "Audio", audio); audioCode = codeOf(g.getTrackFormat(i)); } }
                else if (g.getType() == C.TRACK_TYPE_TEXT) { text++; if (g.isTrackSelected(i)) { textNow = labelOf(g.getTrackFormat(i), "Subtitles", text); textCode = codeOf(g.getTrackFormat(i)); } }
            }
        }
        boolean manual = false;
        for (TrackSelectionOverride o : player.getTrackSelectionParameters().overrides.values()) if (o.getType() == C.TRACK_TYPE_VIDEO) manual = true;
        androidx.media3.common.Format vf = player.getVideoFormat();
        float rate = vf != null && vf.frameRate > 0 ? vf.frameRate : measuredFps;
        String h = vf == null ? "" : qualityLabel(vf.height, rate);
        String pq = "";
        if (page != null) for (String[] v : page.qualities.values()) if (!v[1].isEmpty()) pq = v[0];
        if (video <= 1 && page != null && !page.qualities.isEmpty()) {
            // The page's own quality list: its label ("1080p Full HD") shortened to the number
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\b(\\d{3,4}p|4k|8k)\\b").matcher(pq);
            String shown = m.find() ? m.group(1).toLowerCase(Locale.ROOT).replace("k", "K") : h;
            show(chipQuality, true, "Quality \u00B7 " + (pq.isEmpty() ? (h.isEmpty() ? "Auto" : h) : pq), !pq.isEmpty(), shown);
        } else show(chipQuality, video > 1, "Quality \u00B7 " + (manual ? h : "Auto" + (h.isEmpty() ? "" : " " + h)), manual, h.isEmpty() ? "Auto" : h);
        show(chipAudio, audio > 1, "Audio \u00B7 " + audioNow, false, audioCode);
        boolean subsOn = !textNow.equals("Off");
        show(chipSubs, text > 0 || canSearchSubs(), "Subtitles \u00B7 " + textNow, subsOn, subsOn ? (textCode.isEmpty() ? "CC" : textCode) : "Off");
        float sp = player.getPlaybackParameters().speed;
        show(chipSpeed, true, "Speed \u00B7 " + speedLabel(sp), sp != 1f, speedLabel(sp));
        show(chipZoom, true, "Zoom \u00B7 " + ZOOMS[zoom], zoom != 0, ZOOMS[zoom]);
        fitChips();
    }

    // Pills in as many centred rows as it takes to fit the width (one row on a TV, two on a phone), never overflowing.
    // Rebuilt only when what is shown changes, so the remote keeps its place.
    void fitChips() {
        StringBuilder key = new StringBuilder();
        for (LinearLayout c : chips) key.append(c.getVisibility() == View.VISIBLE ? ((TextView) c.getChildAt(1)).getText() + "|" : "-|");
        if (key.toString().equals(chipLayoutKey)) return;
        chipLayoutKey = key.toString();
        View had = getCurrentFocus();
        chipRow.removeAllViews();
        for (LinearLayout c : chips) if (c.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) c.getParent()).removeView(c);
        int max = ((LinearLayout.LayoutParams) chipRow.getLayoutParams()).width, gap = dp(8), used = 0;
        LinearLayout row = null;
        for (LinearLayout c : chips) {
            if (c.getVisibility() != View.VISIBLE) continue;
            c.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = c.getMeasuredWidth();
            if (row == null || used + w + gap > max) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
                if (chipRow.getChildCount() > 0) rp.topMargin = dp(6);
                chipRow.addView(row, rp);
                used = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.setMargins(gap / 2, 0, gap / 2, 0);
            row.addView(c, lp);
            used += w + gap;
        }
        if (had != null && chips.contains(had)) had.requestFocus();
    }

    // Put the remote on the action row (on) or give it back to play/seek (off)
    void rowFocus(boolean on) {
        LinearLayout first = null;
        for (LinearLayout c : chips) {
            c.setFocusableInTouchMode(on);   // the emulator/phone may be in touch mode; focus must still work
            c.setFocusable(on);
            if (on && first == null && c.getVisibility() == View.VISIBLE) first = c;
        }
        if (on) { showControls(); if (first != null) first.requestFocus(); }
    }

    boolean inRow() {
        View f = getCurrentFocus();
        return controls.getVisibility() == View.VISIBLE && f != null && chips.contains(f);
    }

    // The stream's own requests go through WARP like the page's did (and straight out for LAN or a site on the bypass list)
    void routeThroughWarp() {
        previousProxy = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return Collections.singletonList(Warp.proxyFor(uri.getHost()));
            }

            @Override public void connectFailed(URI uri, SocketAddress a, IOException e) { }
        });
    }

    void startPlayer(String url) {
        Map<String, String> headers = new HashMap<>();
        String ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
        try {
            JSONObject h = new JSONObject(getIntent().getStringExtra("headers"));
            for (java.util.Iterator<String> it = h.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.equalsIgnoreCase("User-Agent")) ua = h.getString(k); else headers.put(k, h.getString(k));
            }
        } catch (Exception ignored) { }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(ua).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(30000).setDefaultRequestProperties(headers);
        // The WebView's cookie for each request's own host, never one site's session sent to another
        DataSource.Factory data = new ResolvingDataSource.Factory(http, spec -> {
            String cookie = CookieManager.getInstance().getCookie(spec.uri.toString());
            return cookie == null || cookie.isEmpty() ? spec : spec.withAdditionalHeaders(Collections.singletonMap("Cookie", cookie));
        });

        // IPTV passes its own media type ("" = let ExoPlayer read it off the URL); pages only ever hand over HLS or DASH
        String mime = getIntent().hasExtra("mime") ? getIntent().getStringExtra("mime") : getIntent().getBooleanExtra("dash", false) ? MimeTypes.APPLICATION_MPD : MimeTypes.APPLICATION_M3U8;
        MediaItem.Builder item = mediaItem = new MediaItem.Builder().setUri(url);
        if (!mime.isEmpty()) item.setMimeType(mime);
        List<MediaItem.SubtitleConfiguration> subs = subList = new ArrayList<>();
        List<String> found = getIntent().getStringArrayListExtra("subs");
        for (int i = 0; found != null && i < found.size(); i++) {
            String s = found.get(i);
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(s))
                .setMimeType(Sniffer.path(s).endsWith(".srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT)
                .setLabel("Subtitles " + (i + 1) + " (" + fileName(s) + ")").build());
        }
        item.setSubtitleConfigurations(subs);

        // The subtitle renderer sees the clock moved by the Timing setting: an earlier clock shows subtitles later, a later one earlier
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override protected void buildTextRenderers(android.content.Context c, androidx.media3.exoplayer.text.TextOutput out, android.os.Looper l, int mode,
                                                        ArrayList<androidx.media3.exoplayer.Renderer> list) {
                int from = list.size();
                super.buildTextRenderers(c, out, l, mode, list);
                for (int i = from; i < list.size(); i++) list.set(i, new androidx.media3.exoplayer.ForwardingRenderer(list.get(i)) {
                    @Override public void render(long pos, long elapsed) throws androidx.media3.exoplayer.ExoPlaybackException { super.render(pos - Subs.delayMs * 1000, elapsed); }

                    @Override public void resetPosition(long pos, boolean joining) throws androidx.media3.exoplayer.ExoPlaybackException { super.resetPosition(pos - Subs.delayMs * 1000, joining); }
                });
            }
        };
        player = new ExoPlayer.Builder(this, renderers.setEnableDecoderFallback(true).setMediaCodecSelector(PlayerActivity::decoders))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).setHandleAudioBecomingNoisy(true).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        player.setVideoFrameMetadataListener(fpsMeter);
        // Side-loaded subtitles stay off until picked on the Subtitles button
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY && getIntent().hasExtra("mime")) Iptv.played(getIntent().getStringExtra("url"));
                if (s == Player.STATE_READY && !scrobbled && player.getPlayWhenReady()) { scrobbled = true; scrobble("start"); }
                if (s == Player.STATE_ENDED) { resume.remove(base); scrobble("stop"); finish(); }
                update();
            }

            // Play and pause by the user (not buffering) go to Simkl; leaving the player sends the stop
            @Override public void onPlayWhenReadyChanged(boolean play, int reason) {
                if (scrobbled && !isFinishing()) scrobble(play ? "start" : "pause");
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                update();
                if (playing) hideAt = System.currentTimeMillis() + 3500; else showControls();
            }

            @Override public void onVideoSizeChanged(VideoSize v) {
                measuredFps = 0;
                fpsMinGapUs = Long.MAX_VALUE;
                fpsFrames = 0;
                if (v.width > 0 && v.height > 0) videoRatio = v.width * v.pixelWidthHeightRatio / v.height;
                fit();
                refreshChips();
            }

            @Override public void onTracksChanged(Tracks t) { refreshChips(); }

            @Override public void onPlaybackParametersChanged(androidx.media3.common.PlaybackParameters p) { refreshChips(); }

            @Override public void onCues(CueGroup group) { showCues(group); }

            @Override public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "playback error", e);
                if (getIntent().hasExtra("mime")) { Iptv.mark(getIntent().getStringExtra("url"), false); Iptv.saveHealth(); }
                LocalizedToast.makeText(PlayerActivity.this, getIntent().hasExtra("mime") ? "Channel is offline (" + e.getErrorCodeName() + ")"
                    : "Native player could not play this (" + e.getErrorCodeName() + "), back to the page", Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        Long at = resume.get(base);
        if (carryMs > 0 && SystemClock.elapsedRealtime() - carryAt < 45000) at = carryMs;
        carryMs = 0;
        if (at != null && at > 10000) player.setMediaItem(item.build(), at); else player.setMediaItem(item.build());
        player.prepare();
        player.play();
        episodeLine();
        showControls();
        App.main.post(tick);
    }

    /**
     * The emulator's software H.264 decoder (c2.goldfish) does not report the new picture size when the quality changes mid-stream, so
     * the picture comes out zoomed. There the decoder is restarted on a size change instead; real devices keep seamless switching.
     */
    static java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> decoders(String mime, boolean secure, boolean tunneling)
        throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
        java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> in = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
        java.util.List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> out = new ArrayList<>();
        for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : in) {
            out.add(i.name.startsWith("c2.goldfish") && i.adaptive
                ? androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(i.name, i.mimeType, i.codecMimeType, i.capabilities, i.hardwareAccelerated, i.softwareOnly, i.vendor, true, i.secure)
                : i);
        }
        return out;
    }

    // The surface is sized to the picture: Fit letterboxes (nothing stretched), Fill crops to cover the screen, Stretch fills it,
    // 16:9 and 4:3 force that shape
    void fit() {
        View root = (View) surface.getParent();
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0 || (videoRatio <= 0 && zoom != 2)) return;
        float r = zoom == 3 ? 16f / 9 : zoom == 4 ? 4f / 3 : videoRatio;
        int vw = w, vh = h;
        if (zoom == 1) {
            vh = Math.round(w / r);
            if (vh < h) { vh = h; vw = Math.round(h * r); }
        } else if (zoom != 2) {
            vh = Math.round(w / r);
            if (vh > h) { vh = h; vw = Math.round(h * r); }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width == vw && lp.height == vh) return;
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    void cycleZoom() {
        zoom = (zoom + 1) % ZOOMS.length;
        fit();
        say("Zoom: " + ZOOMS[zoom]);
        refreshChips();
    }

    static String fileName(String url) {
        String p = Sniffer.path(url);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    void toggle() {
        if (player.isPlaying()) player.pause(); else player.play();
        showControls();
    }

    // Presses only move a target; ExoPlayer seeks once, 400 ms after the last one (a seek per press re-buffers each time)
    void seekBy(long ms) {
        long dur = player.getDuration();
        long to = (pending >= 0 ? pending : player.getCurrentPosition()) + ms;
        pending = Math.max(0, dur == C.TIME_UNSET ? to : Math.min(to, dur - 1000));
        App.main.removeCallbacks(commit);
        App.main.postDelayed(commit, 400);
        say(clock(pending));
        showControls();
    }

    final Runnable commit = this::commitSeek;

    void commitSeek() {
        App.main.removeCallbacks(commit);
        if (pending >= 0 && player != null) player.seekTo(pending);
        pending = -1;
        update();
    }

    void say(String s) {
        notice.setText(s);
        notice.setVisibility(View.VISIBLE);
        App.main.removeCallbacks(hideNotice);
        App.main.postDelayed(hideNotice, 1200);
    }

    final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    void showControls() {
        controls.setVisibility(View.VISIBLE);
        hideAt = System.currentTimeMillis() + (inRow() ? 8000 : 3500);
        refreshChips();
        update();
    }

    void hideControls() {
        rowFocus(false);
        controls.setVisibility(View.GONE);
    }

    void update() {
        if (player == null) return;
        boolean playing = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
        playPause.setImageResource(playing ? R.drawable.ic_p_pause : R.drawable.ic_p_play);
        long dur = player.getDuration(), pos = pending >= 0 ? pending : player.getCurrentPosition();
        boolean live = dur == C.TIME_UNSET || player.isCurrentMediaItemLive();
        seek.setVisibility(live ? View.INVISIBLE : View.VISIBLE);
        rew.setVisibility(live ? View.GONE : View.VISIBLE);
        fwd.setVisibility(live ? View.GONE : View.VISIBLE);
        if (!live && !dragging) {
            seek.setMax((int) dur);
            seek.setProgress((int) pos);
            seek.setSecondaryProgress((int) player.getBufferedPosition());
        }
        time.setText(live ? "LIVE" : clock(pos));
        timeTotal.setText(live ? "" : clock(dur));
        if (playing && controls.getVisibility() == View.VISIBLE && !dragging && hasWindowFocus() && System.currentTimeMillis() > hideAt) hideControls();
    }

    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (player == null) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode(), r = e.getRepeatCount();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (inRow()) {
            // On the action row the normal focus keys work: Left/Right move, OK presses (it clicks on key up, so pass both)
            if (down) showControls();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) return super.dispatchKeyEvent(e);
            // Two rows on a phone: Up/Down move between them, Up from the top row hands the remote back to play/seek
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) {
                    View n = getCurrentFocus().focusSearch(k == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                    if (n != null && chips.contains(n)) n.requestFocus();
                    else if (k == KeyEvent.KEYCODE_DPAD_UP) rowFocus(false);
                }
                return true;
            }
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE:
                if (r == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: player.play(); showControls(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                // Holding the key speeds up: 10 s steps, then 30 s, then 60 s
                if (r == 0 || r % 3 == 0) {
                    boolean back = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND;
                    seekBy((back ? -1 : 1) * (r < 12 ? 10000L : r < 40 ? 30000L : 60000L));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU:
                if (r == 0) rowFocus(true);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    static String speedLabel(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    // The page's own server list, read from the page when the stream started
    void pickServer() {
        WebActivity page = WebActivity.active;
        pickFromPage(page == null ? null : page.servers, "Servers");
    }

    // Quality: the stream's own choices if it has several, else the page's quality list (a page that serves one playlist per quality)
    void pickQuality() {
        WebActivity page = WebActivity.active;
        if (page != null && !page.qualities.isEmpty() && countVideo(player.getCurrentTracks()) <= 1) pickFromPage(page.qualities, "Quality");
        else pickVideo(player.getCurrentTracks());
    }

    void pickFromPage(Map<String, String[]> list, String title) {
        WebActivity page = WebActivity.active;
        if (page == null || list == null || list.isEmpty()) { say("No " + title.toLowerCase() + " list found on this page"); return; }
        List<String> keys = new ArrayList<>(list.keySet()), labels = new ArrayList<>();
        for (String k : keys) labels.add(list.get(k)[0] + (list.get(k)[1].isEmpty() ? "" : "  \u2713"));
        new LocalizedDialogBuilder(this).setTitle(title).setItems(labels.toArray(new String[0]), (d, i) -> {
            carryMs = player.getCurrentPosition();
            carryAt = SystemClock.elapsedRealtime();
            page.switchServer(keys.get(i));
            finish();
        }).show();
    }

    static int countVideo(Tracks t) {
        int n = 0;
        for (Tracks.Group g : t.getGroups()) if (g.getType() == C.TRACK_TYPE_VIDEO) for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) n++;
        return n;
    }

    void pickSpeed() {
        String[] labels = new String[SPEEDS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = speedLabel(SPEEDS[i]);
        new LocalizedDialogBuilder(this).setTitle("Speed").setItems(labels, (d, i) -> player.setPlaybackSpeed(SPEEDS[i])).show();
    }

    // Quality: the best track of each height, plus Auto
    void pickVideo(Tracks tracks) {
        // key = height * 1000 + frame rate, so 1080p30 and 1080p60 are two entries; the best bitrate of each is kept
        TreeMap<Integer, TrackSelectionOverride> byKey = new TreeMap<>(Collections.reverseOrder());
        TreeMap<Integer, Integer> bitrate = new TreeMap<>();
        TreeMap<Integer, String> names = new TreeMap<>();
        androidx.media3.common.Format playing = player.getVideoFormat();   // an adaptive stream marks every quality as selected, so the playing one is found by its size and bitrate
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int t = 0; t < g.length; t++) {
                androidx.media3.common.Format f = g.getTrackFormat(t);
                int h = f.height, br = f.bitrate, key = h * 1000 + (f.frameRate > 0 ? Math.round(f.frameRate) : 0);
                if (h <= 0 || !g.isTrackSupported(t) || (bitrate.containsKey(key) && bitrate.get(key) >= br)) continue;
                bitrate.put(key, br);
                byKey.put(key, new TrackSelectionOverride(g.getMediaTrackGroup(), t));
                // The declared rate, else (only for the quality playing right now) the measured one; never a guess for the others
                names.put(key, qualityLabel(h, f.frameRate > 0 ? f.frameRate : playing != null && f.height == playing.height && f.bitrate == playing.bitrate ? measuredFps : 0));
            }
        }
        List<String> labels = new ArrayList<>();
        List<TrackSelectionOverride> picks = new ArrayList<>();
        labels.add("Auto");
        picks.add(null);
        for (Map.Entry<Integer, TrackSelectionOverride> en : byKey.entrySet()) {
            labels.add(names.get(en.getKey()));
            picks.add(en.getValue());
        }
        new LocalizedDialogBuilder(this).setTitle("Quality").setItems(labels.toArray(new String[0]), (d, i) -> {
            androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            if (picks.get(i) != null) b.addOverride(picks.get(i));
            player.setTrackSelectionParameters(b.build());
            say(labels.get(i));
        }).show();
    }

    void pickTrack(Tracks tracks, int type, String name, boolean canOff) {
        List<String> labels = new ArrayList<>();
        List<TrackSelectionOverride> picks = new ArrayList<>();
        if (canOff) { labels.add("Off"); picks.add(null); }
        int n = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != type) continue;
            TrackGroup tg = g.getMediaTrackGroup();
            for (int t = 0; t < g.length; t++) {
                if (!g.isTrackSupported(t)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(t);
                String label = f.label != null ? f.label : f.language != null ? new Locale(f.language).getDisplayLanguage() : name + " " + (++n);
                labels.add(label + (g.isTrackSelected(t) ? "  ✓" : ""));
                picks.add(new TrackSelectionOverride(tg, t));
            }
        }
        boolean search = type == C.TRACK_TYPE_TEXT && canSearchSubs();
        if (!search && (labels.isEmpty() || (canOff && labels.size() == 1))) { say("No " + name.toLowerCase() + " in this video"); return; }
        int searchAt = search ? labels.size() : -1;
        if (search) labels.add("\uD83D\uDD0D  Search OpenSubtitles\u2026");
        int positionAt = type == C.TRACK_TYPE_TEXT ? labels.size() : -1;
        if (positionAt >= 0) labels.add("Aa  Size, position, timing  (" + Subs.sizeLabel() + " \u00B7 " + Subs.offsetLabel() + " \u00B7 " + Subs.delayLabel() + ")");
        new LocalizedDialogBuilder(this).setTitle(name).setItems(labels.toArray(new String[0]), (d, i) -> {
            if (i == searchAt) { searchSubs(); return; }
            if (i == positionAt) { subtitleStyle(); return; }
            androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if (picks.get(i) == null) b.setTrackTypeDisabled(type, true); else b.setTrackTypeDisabled(type, false).addOverride(picks.get(i));
            player.setTrackSelectionParameters(b.build());
        }).show();
    }

    // ---- seasons and episodes ----

    /** {season, episode} of what is playing: what the page's URL or title say, else what the catalog screen passed; null for a movie. */
    int[] pageEpisode() {
        Intent in = getIntent();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("S(\\d+) E(\\d+)|E(\\d+)")
            .matcher(History.episode(String.valueOf(in.getStringExtra("pageUrl")), String.valueOf(in.getStringExtra("pageTitle"))));
        if (m.find()) return new int[]{m.group(1) != null ? Integer.parseInt(m.group(1)) : 1, Integer.parseInt(m.group(1) != null ? m.group(2) : m.group(3))};
        int s = in.getIntExtra("season", 0), e = in.getIntExtra("episode", 0);
        return s > 0 || e > 0 ? new int[]{s, e} : null;
    }

    // "Season 1 · Episode 3" under the title
    void episodeLine() {
        int[] pe = pageEpisode();
        if (pe == null) { epLine.setVisibility(View.GONE); return; }
        String label = (pe[0] > 0 ? "Season " + pe[0] + "  \u00B7  " : "") + "Episode " + pe[1];
        epLine.setText(label);
        epLine.setVisibility(View.VISIBLE);
    }

    // ---- OpenSubtitles ----

    /** Only for a page's movie or episode, never a live channel. */
    boolean canSearchSubs() {
        return getIntent().hasExtra("pageTitle") && player != null && !player.isCurrentMediaItemLive();
    }

    void searchSubs() {
        Intent in = getIntent();
        String imdb = in.getStringExtra("imdb"), title = History.cleanTitle(String.valueOf(in.getStringExtra("pageTitle")), String.valueOf(in.getStringExtra("site")));
        // The page's own URL/title say which episode is playing (the site may have moved on from the one picked in the catalog)
        int[] pe = pageEpisode();
        int season = pe == null ? 0 : pe[0], episode = pe == null ? 0 : pe[1];
        final int s = season, e = episode;
        final boolean needsEpisode = in.getBooleanExtra("series", false) && s == 0;
        say("Searching OpenSubtitles\u2026");
        App.io.execute(() -> {
            String id = needsEpisode ? "" : imdb == null || imdb.isEmpty() ? Subs.imdbFor(title, s > 0) : imdb;
            List<Subs.Sub> found = id.isEmpty() ? null : Subs.find(id, s, e);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (found == null) say(needsEpisode ? "Open the episode on the site first" : "Can't find this title on OpenSubtitles");
                else if (found.isEmpty()) say("No subtitles for this title");
                else pickSubLanguage(Subs.byLanguage(found));
            });
        });
    }

    void pickSubLanguage(Map<String, List<Subs.Sub>> langs) {
        List<String> codes = new ArrayList<>(langs.keySet()), labels = new ArrayList<>();
        for (String c : codes) labels.add(Subs.language(c) + "  \u00B7  " + langs.get(c).size());
        new LocalizedDialogBuilder(this).setTitle("Subtitle language").setItems(labels.toArray(new String[0]), (d, i) -> {
            List<Subs.Sub> files = langs.get(codes.get(i)).subList(0, Math.min(40, langs.get(codes.get(i)).size()));
            List<String> names = new ArrayList<>();
            for (Subs.Sub f : files) names.add(f.release);
            new LocalizedDialogBuilder(this).setTitle(Subs.language(codes.get(i)) + ": pick a release that matches your video")
                .setItems(names.toArray(new String[0]), (d2, j) -> addSubtitle(files.get(j))).show();
        }).show();
    }

    // Side-load the file and restart the stream at the same spot with it switched on
    void addSubtitle(Subs.Sub sub) {
        subList.removeIf(c -> "opensubtitles".equals(c.id));
        subList.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url)).setId("opensubtitles").setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(sub.lang).setLabel(Subs.language(sub.lang) + " (OpenSubtitles)").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
        mediaItem.setSubtitleConfigurations(subList);
        long pos = player.getCurrentPosition();
        boolean playing = player.getPlayWhenReady();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.setMediaItem(mediaItem.build(), pos);
        player.prepare();
        player.setPlayWhenReady(playing);
        say("Subtitles: " + Subs.language(sub.lang));
    }

    @Override protected void onStop() {
        super.onStop();
        endHoldSpeed();
        if (player == null) return;
        player.pause();
        long dur = player.getDuration(), pos = player.getCurrentPosition();
        if (dur != C.TIME_UNSET && !player.isCurrentMediaItemLive() && pos < dur - 30000) resume.put(base, pos); else resume.remove(base);
    }

    // ---- Simkl ----

    boolean scrobbled, stopped;   // a start went out for this video; the stop went out

    /** Tells Simkl where the video is, for a movie or an episode from the catalog (live streams and unknown episodes are left out). */
    void scrobble(String action) {
        if (player == null || player.isCurrentMediaItemLive() || stopped || !scrobbled) return;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) return;
        boolean series = getIntent().getBooleanExtra("series", false);
        int[] episode = series ? pageEpisode() : null;
        if (series && episode == null) return;
        if (action.equals("stop")) stopped = true;
        Simkl.scrobble(action, getIntent().getStringExtra("imdb"), episode,
            player.getPlaybackState() == Player.STATE_ENDED ? 100 : player.getCurrentPosition() * 100.0 / dur);
    }

    @Override protected void onDestroy() {
        scrobble("stop");
        if (getIntent().hasExtra("mime")) Iptv.saveHealth();
        App.main.removeCallbacks(tick);
        App.main.removeCallbacks(hideNotice);
        if (player != null) { player.release(); player = null; }
        if (previousProxy != null) ProxySelector.setDefault(previousProxy);
        super.onDestroy();
    }
}
