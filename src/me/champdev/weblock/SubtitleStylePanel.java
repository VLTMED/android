package me.champdev.weblock;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Grouped subtitle controls inspired by modern media-player style sheets. */
final class SubtitleStylePanel {
    private static final int[] PALETTE = {
        Color.WHITE, 0xFFFFD54F, 0xFF54D6FF, 0xFF61D095,
        0xFFFF6B6B, 0xFFFF75C3, 0xFF8A9CFF, Color.BLACK
    };
    private static final String[] COLOR_NAMES = {"White", "Yellow", "Cyan", "Green", "Red", "Pink", "Blue", "Black"};
    private static final int[] BACKGROUND_ALPHAS = {0, 64, 128, 192, 255};
    private static final String[] ALPHA_LABELS = {"Off", "25%", "50%", "75%", "100%"};

    private SubtitleStylePanel() { }

    static void show(Activity activity, boolean timing, Runnable changed, Runnable closed) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Home.dp(activity, 18);
        root.setPadding(pad, pad, pad, Home.dp(activity, 12));
        root.setBackground(Home.rounded(0xF0181320, Home.dp(activity, 20)));

        TextView title = label(activity, "Subtitles", 21, Home.TEXT);
        title.setPadding(0, 0, 0, Home.dp(activity, 8));
        root.addView(title);

        TextView previewLabel = label(activity, "Preview", 12, Home.MUTED);
        previewLabel.setPadding(Home.dp(activity, 2), 0, 0, Home.dp(activity, 4));
        root.addView(previewLabel);
        SubtitleTextView preview = new SubtitleTextView(activity);
        preview.setGravity(Gravity.CENTER);
        preview.setMaxLines(2);
        preview.setText("Subtitles show here");
        Subs.applyAppearance(preview, activity);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, Home.dp(activity, 60));
        previewLp.bottomMargin = Home.dp(activity, 10);
        root.addView(preview, previewLp);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        List<Runnable> refreshers = new ArrayList<>();
        Runnable after = () -> {
            for (Runnable refresh : refreshers) refresh.run();
            Subs.applyAppearance(preview, activity);
            changed.run();
        };

        LinearLayout typography = section(activity, content, "Typography");
        TextView sizePlus = stepRow(activity, typography, refreshers, "Size", Subs::sizeLabel,
            by -> changeInt("subSize", 100, 10 * by, 50, 250), after);
        stepRow(activity, typography, refreshers, "Position", Subs::offsetLabel,
            by -> changeInt("subOffset", 0, 10 * by, -100, 200), after);
        addAlignmentRow(activity, typography, refreshers, after);
        if (timing) stepRow(activity, typography, refreshers, "Timing", Subs::delayLabel,
            by -> Subs.delayMs = Math.max(-30000, Math.min(30000, Subs.delayMs + 100L * by)), after);
        addFontShelf(activity, typography, refreshers, after);
        addColorRow(activity, typography, refreshers, after, Subs.COLOR_TEXT, "Text color");

        LinearLayout effects = section(activity, content, "Outline & shadow");
        stepRow(activity, effects, refreshers, "Outline size", () -> Subs.outlineWidth() == 0 ? "Off" : Subs.outlineWidth() + " dp",
            by -> Subs.setOutlineWidth(Subs.outlineWidth() + by), after);
        addColorRow(activity, effects, refreshers, after, Subs.COLOR_OUTLINE, "Outline color");
        stepRow(activity, effects, refreshers, "Shadow strength", () -> Subs.shadowRadius() == 0 ? "Off" : Subs.shadowRadius() + " dp",
            by -> Subs.setShadowRadius(Subs.shadowRadius() + by), after);
        stepRow(activity, effects, refreshers, "Shadow offset", () -> Subs.shadowOffset() + " dp",
            by -> Subs.setShadowOffset(Subs.shadowOffset() + by), after);
        addColorRow(activity, effects, refreshers, after, Subs.COLOR_SHADOW, "Shadow color");

        LinearLayout background = section(activity, content, "Background");
        addOpacityRow(activity, background, refreshers, after);
        addColorRow(activity, background, refreshers, after, Subs.COLOR_BACKGROUND, "Background color");

        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, Home.dp(activity, 8), 0, 0);
        footer.addView(actionButton(activity, "Reset", () -> {
            App.prefs.edit().remove("subSize").remove("subOffset").remove("subtitleFont").apply();
            Subs.resetAppearance();
            if (timing) Subs.delayMs = 0;
            after.run();
        }));
        footer.addView(actionButton(activity, "Done", dialog::dismiss));
        root.addView(footer);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            window.setDimAmount(0.18f);
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - Home.dp(activity, 24), Home.dp(activity, 560));
            int height = Math.min(activity.getResources().getDisplayMetrics().heightPixels - Home.dp(activity, 48), Home.dp(activity, 760));
            window.setLayout(width, height);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.y = Home.dp(activity, 14);
            window.setAttributes(attrs);
        }
        dialog.setOnDismissListener(ignored -> closed.run());
        dialog.show();
        if (window != null) {
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - Home.dp(activity, 24), Home.dp(activity, 560));
            int height = Math.min(activity.getResources().getDisplayMetrics().heightPixels - Home.dp(activity, 48), Home.dp(activity, 760));
            window.setLayout(width, height);
        }
        sizePlus.requestFocus();
    }

    private static void changeInt(String key, int defaultValue, int delta, int min, int max) {
        int current = App.prefs.getInt(key, defaultValue);
        App.prefs.edit().putInt(key, Math.max(min, Math.min(max, current + delta))).apply();
    }

    private static LinearLayout section(Activity activity, LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Home.dp(activity, 12);
        card.setPadding(pad, Home.dp(activity, 10), pad, pad);
        card.setBackground(Home.rounded(0x24FFFFFF, Home.dp(activity, 16)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = Home.dp(activity, 8);
        parent.addView(card, cardLp);
        TextView heading = label(activity, title, 14, Home.ACCENT);
        heading.setPadding(0, 0, 0, Home.dp(activity, 6));
        card.addView(heading);
        return card;
    }

    private static TextView stepRow(Activity activity, LinearLayout parent, List<Runnable> refreshers,
                                    String title, Supplier<String> value, IntConsumer step, Runnable after) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Home.dp(activity, 2), 0, Home.dp(activity, 2));
        TextView name = label(activity, title, 14, Home.TEXT);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView now = label(activity, value.get(), 14, Home.TEXT);
        now.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(Home.dp(activity, 74), -2);
        row.addView(iconButton(activity, "−", () -> { step.accept(-1); after.run(); }), new LinearLayout.LayoutParams(Home.dp(activity, 40), Home.dp(activity, 40)));
        row.addView(now, valueLp);
        TextView plus = iconButton(activity, "+", () -> { step.accept(1); after.run(); });
        row.addView(plus, new LinearLayout.LayoutParams(Home.dp(activity, 40), Home.dp(activity, 40)));
        parent.addView(row);
        refreshers.add(() -> now.setText(value.get()));
        return plus;
    }

    private static void addAlignmentRow(Activity activity, LinearLayout parent, List<Runnable> refreshers, Runnable after) {
        TextView heading = label(activity, "Alignment", 13, Home.MUTED);
        heading.setPadding(0, Home.dp(activity, 6), 0, Home.dp(activity, 3));
        parent.addView(heading);
        String[] names = {"Start", "Center", "End"};
        List<TextView> choices = new ArrayList<>();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int value = i;
            TextView choice = actionButton(activity, names[i], () -> { Subs.setAlignment(value); after.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Home.dp(activity, 38), 1);
            lp.setMargins(Home.dp(activity, 2), 0, Home.dp(activity, 2), 0);
            choice.setLayoutParams(lp);
            row.addView(choice);
            choices.add(choice);
        }
        refreshers.add(() -> {
            for (int i = 0; i < choices.size(); i++) updateChoice(choices.get(i), i == Subs.alignment(), activity);
        });
        parent.addView(row);
    }

    private static void addFontShelf(Activity activity, LinearLayout parent, List<Runnable> refreshers, Runnable after) {
        TextView heading = label(activity, "Fonts", 13, Home.MUTED);
        heading.setPadding(0, Home.dp(activity, 6), 0, Home.dp(activity, 3));
        parent.addView(heading);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout shelf = new LinearLayout(activity);
        shelf.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> faces = new ArrayList<>();
        for (int i = 0; i < Fonts.FACE_NAMES.length; i++) {
            final int face = i;
            TextView option = fontButton(activity, face);
            option.setOnClickListener(v -> { Fonts.selectSubtitle(face); after.run(); });
            faces.add(option);
            shelf.addView(option);
        }
        refreshers.add(() -> {
            for (int i = 0; i < faces.size(); i++) updateChoice(faces.get(i), i == Fonts.subtitleIndex(), activity);
        });
        scroll.addView(shelf);
        parent.addView(scroll);
    }

    private static TextView fontButton(Activity activity, int index) {
        TextView option = new LocalizedTextView(activity);
        option.setText(Fonts.FACE_NAMES[index]);
        option.setTypeface(Fonts.face(activity, index));
        option.setTextColor(Home.TEXT);
        option.setTextSize(13);
        option.setGravity(Gravity.CENTER);
        option.setFocusable(true);
        option.setClickable(true);
        option.setBackground(Home.rounded(0x2AFFFFFF, Home.dp(activity, 12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Home.dp(activity, 82), Home.dp(activity, 42));
        lp.setMargins(Home.dp(activity, 3), Home.dp(activity, 3), Home.dp(activity, 3), Home.dp(activity, 3));
        option.setLayoutParams(lp);
        updateChoice(option, index == Fonts.subtitleIndex(), activity);
        return option;
    }

    private static void addColorRow(Activity activity, LinearLayout parent, List<Runnable> refreshers,
                                    Runnable after, int role, String title) {
        TextView heading = label(activity, title, 13, Home.MUTED);
        heading.setPadding(0, Home.dp(activity, 7), 0, Home.dp(activity, 3));
        parent.addView(heading);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> swatches = new ArrayList<>();
        for (int i = 0; i < PALETTE.length; i++) {
            final int index = i;
            TextView swatch = new LocalizedTextView(activity);
            swatch.setContentDescription(COLOR_NAMES[i]);
            swatch.setFocusable(true);
            swatch.setClickable(true);
            swatch.setBackground(swatchBackground(PALETTE[i], false, activity));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Home.dp(activity, 36), Home.dp(activity, 36));
            lp.setMargins(Home.dp(activity, 3), Home.dp(activity, 3), Home.dp(activity, 3), Home.dp(activity, 3));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                int old = Subs.color(role);
                int alpha = role == Subs.COLOR_BACKGROUND ? Color.alpha(old) : 255;
                int c = Color.argb(alpha, Color.red(PALETTE[index]), Color.green(PALETTE[index]), Color.blue(PALETTE[index]));
                Subs.setColor(role, c);
                after.run();
            });
            row.addView(swatch);
            swatches.add(swatch);
        }
        TextView custom = actionButton(activity, "Custom color", () -> openColorPicker(activity, role, title, after));
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(-2, Home.dp(activity, 38));
        customLp.setMargins(Home.dp(activity, 6), Home.dp(activity, 2), Home.dp(activity, 3), Home.dp(activity, 2));
        custom.setLayoutParams(customLp);
        row.addView(custom);
        refreshers.add(() -> {
            int selected = Subs.color(role);
            for (int i = 0; i < swatches.size(); i++) {
                boolean isSelected = sameRgb(selected, PALETTE[i]);
                swatches.get(i).setBackground(swatchBackground(PALETTE[i], isSelected, activity));
            }
        });
        scroll.addView(row);
        parent.addView(scroll);
    }

    private static void addOpacityRow(Activity activity, LinearLayout parent, List<Runnable> refreshers, Runnable after) {
        TextView heading = label(activity, "Background opacity", 13, Home.MUTED);
        heading.setPadding(0, Home.dp(activity, 7), 0, Home.dp(activity, 3));
        parent.addView(heading);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        List<TextView> choices = new ArrayList<>();
        for (int i = 0; i < BACKGROUND_ALPHAS.length; i++) {
            final int index = i;
            TextView choice = actionButton(activity, ALPHA_LABELS[i], () -> { Subs.setBackgroundOpacity(BACKGROUND_ALPHAS[index]); after.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Home.dp(activity, 38), 1);
            lp.setMargins(Home.dp(activity, 2), 0, Home.dp(activity, 2), 0);
            choice.setLayoutParams(lp);
            row.addView(choice);
            choices.add(choice);
        }
        refreshers.add(() -> {
            int selected = nearestOpacity(Subs.backgroundOpacity());
            for (int i = 0; i < choices.size(); i++) updateChoice(choices.get(i), i == selected, activity);
        });
        parent.addView(row);
    }

    private static int nearestOpacity(int alpha) {
        int best = 0;
        for (int i = 1; i < BACKGROUND_ALPHAS.length; i++) if (Math.abs(BACKGROUND_ALPHAS[i] - alpha) < Math.abs(BACKGROUND_ALPHAS[best] - alpha)) best = i;
        return best;
    }

    private static void openColorPicker(Activity activity, int role, String title, Runnable after) {
        int initial = Subs.color(role);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Home.dp(activity, 18);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(Home.rounded(0xF0181320, Home.dp(activity, 18)));
        TextView heading = label(activity, "Custom color", 18, Home.TEXT);
        box.addView(heading);
        TextView swatch = label(activity, String.format(Locale.ROOT, "#%08X", initial), 14, Color.WHITE);
        swatch.setGravity(Gravity.CENTER);
        swatch.setBackground(Home.rounded(initial, Home.dp(activity, 10)));
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(-1, Home.dp(activity, 44));
        swatchLp.topMargin = Home.dp(activity, 8);
        swatchLp.bottomMargin = Home.dp(activity, 8);
        box.addView(swatch, swatchLp);

        final int[] components = {Color.red(initial), Color.green(initial), Color.blue(initial), Color.alpha(initial)};
        final int[] current = {initial};
        String[] labels = {"Red", "Green", "Blue", "Opacity"};
        for (int component = 0; component < components.length; component++) {
            final int index = component;
            LinearLayout line = new LinearLayout(activity);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(activity, labels[component], 13, Home.TEXT);
            line.addView(name, new LinearLayout.LayoutParams(Home.dp(activity, 76), -2));
            SeekBar bar = new SeekBar(activity);
            bar.setMax(255);
            bar.setProgress(components[component]);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
            line.addView(bar, new LinearLayout.LayoutParams(0, -2, 1));
            TextView number = label(activity, String.valueOf(components[component]), 12, Home.MUTED);
            number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            line.addView(number, new LinearLayout.LayoutParams(Home.dp(activity, 32), -2));
            box.addView(line);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    components[index] = value;
                    number.setText(String.valueOf(value));
                    current[0] = Color.argb(components[3], components[0], components[1], components[2]);
                    swatch.setText(String.format(Locale.ROOT, "#%08X", current[0]));
                    swatch.setBackground(Home.rounded(current[0], Home.dp(activity, 10)));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, Home.dp(activity, 10), 0, 0);
        actions.addView(actionButton(activity, "Cancel", dialog::dismiss));
        actions.addView(actionButton(activity, "Apply", () -> {
            Subs.setColor(role, current[0]);
            after.run();
            dialog.dismiss();
        }));
        box.addView(actions);
        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - Home.dp(activity, 36), Home.dp(activity, 440));
            window.setLayout(width, -2);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - Home.dp(activity, 36), Home.dp(activity, 440));
            window.setLayout(width, -2);
        }
    }

    private static TextView iconButton(Activity activity, String text, Runnable action) {
        TextView button = actionButton(activity, text, action);
        button.setTextSize(21);
        return button;
    }

    private static TextView actionButton(Activity activity, String text, Runnable action) {
        TextView button = new LocalizedTextView(activity);
        button.setText(text);
        button.setTextColor(Home.TEXT);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Fonts.ui(activity));
        button.setPadding(Home.dp(activity, 10), 0, Home.dp(activity, 10), 0);
        button.setBackground(choiceBackground(false, activity));
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Home.dp(activity, 42));
        lp.setMargins(Home.dp(activity, 3), 0, Home.dp(activity, 3), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private static TextView label(Context context, String text, int size, int color) {
        TextView result = new LocalizedTextView(context);
        result.setText(text);
        result.setTextColor(color);
        result.setTextSize(size);
        result.setTypeface(Fonts.ui(context));
        return result;
    }

    private static StateListDrawable swatchBackground(int color, boolean selected, Activity activity) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(color);
        normal.setStroke(Home.dp(activity, 1), 0x88FFFFFF);
        GradientDrawable chosen = new GradientDrawable();
        chosen.setShape(GradientDrawable.OVAL);
        chosen.setColor(color);
        chosen.setStroke(Home.dp(activity, 3), Home.ACCENT);
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(color);
        focused.setStroke(Home.dp(activity, 3), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, chosen);
        states.addState(new int[]{}, selected ? chosen : normal);
        return states;
    }

    private static void updateChoice(TextView view, boolean selected, Activity activity) {
        view.setSelected(selected);
        view.setBackground(choiceBackground(selected, activity));
    }

    private static StateListDrawable choiceBackground(boolean selected, Activity activity) {
        GradientDrawable normal = Home.rounded(0x2AFFFFFF, Home.dp(activity, 12));
        GradientDrawable chosen = Home.rounded(0x55FF3B5C, Home.dp(activity, 12));
        chosen.setStroke(Home.dp(activity, 2), Home.ACCENT);
        GradientDrawable focused = Home.rounded(selected ? 0x55FF3B5C : 0x34FFFFFF, Home.dp(activity, 12));
        focused.setStroke(Home.dp(activity, 2), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, chosen);
        states.addState(new int[]{}, selected ? chosen : normal);
        return states;
    }

    private static boolean sameRgb(int a, int b) {
        return Color.red(a) == Color.red(b) && Color.green(a) == Color.green(b) && Color.blue(a) == Color.blue(b);
    }
}
