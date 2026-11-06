package me.champdev.weblock;

import android.content.Context;
import android.graphics.Typeface;

/** Thmanyah Sans faces used by the app UI and subtitle font shelf. */
final class Fonts {
    static final String[] FACE_NAMES = {"Black", "Bold", "Light", "Medium", "Regular"};
    private static final int[] FACE_RESOURCES = {
        R.font.thmanyah_sans_black,
        R.font.thmanyah_sans_bold,
        R.font.thmanyah_sans_light,
        R.font.thmanyah_sans_medium,
        R.font.thmanyah_sans_regular
    };
    private static final int DEFAULT_SUBTITLE_FACE = 3;
    private static final String SUBTITLE_FACE_KEY = "subtitleFont";
    private static final Typeface[] CACHE = new Typeface[FACE_RESOURCES.length];

    private Fonts() { }

    static int subtitleIndex() {
        int index = App.prefs.getInt(SUBTITLE_FACE_KEY, DEFAULT_SUBTITLE_FACE);
        return index >= 0 && index < FACE_RESOURCES.length ? index : DEFAULT_SUBTITLE_FACE;
    }

    static void selectSubtitle(int index) {
        if (index >= 0 && index < FACE_RESOURCES.length) {
            App.prefs.edit().putInt(SUBTITLE_FACE_KEY, index).apply();
        }
    }

    static Typeface subtitle(Context context) {
        return face(context, subtitleIndex());
    }

    static Typeface ui(Context context) {
        return face(context, DEFAULT_SUBTITLE_FACE);
    }

    static Typeface face(Context context, int index) {
        int safeIndex = index >= 0 && index < FACE_RESOURCES.length ? index : DEFAULT_SUBTITLE_FACE;
        synchronized (CACHE) {
            if (CACHE[safeIndex] == null) CACHE[safeIndex] = context.getResources().getFont(FACE_RESOURCES[safeIndex]);
            return CACHE[safeIndex];
        }
    }
}
