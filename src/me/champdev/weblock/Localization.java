package me.champdev.weblock;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads owner-reviewed translations by locale and preserves dynamic UI placeholders. */
final class Localization {
    private static final Object LOCK = new Object();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");
    private static final Map<String, TranslationTable> TABLES = new HashMap<>();

    private Localization() { }

    static void initialize(Context context) {
        tableFor(context.getApplicationContext(), language(context));
    }

    static CharSequence translate(Context context, CharSequence value) {
        if (value == null) return null;
        TranslationTable table = tableFor(context.getApplicationContext(), language(context));
        String source = value.toString();
        String translated = table.exact.get(source);
        if (translated != null) return translated;
        for (Template template : table.templates) {
            translated = template.apply(source);
            if (translated != null) return translated;
        }
        return value;
    }

    private static String language(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        Locale locale = configuration.getLocales().size() > 0
            ? configuration.getLocales().get(0) : Locale.getDefault();
        String language = locale.getLanguage();
        return language == null || language.isEmpty() ? Locale.getDefault().getLanguage() : language.toLowerCase(Locale.ROOT);
    }

    private static TranslationTable tableFor(Context context, String language) {
        synchronized (LOCK) {
            TranslationTable cached = TABLES.get(language);
            if (cached != null) return cached;

            Map<String, String> exact = new HashMap<>();
            List<Template> templates = new ArrayList<>();
            Resources resources = context.getResources();
            int resource = resources.getIdentifier("ui_strings_" + language, "raw", context.getPackageName());
            if (resource != 0) {
                try (InputStream input = resources.openRawResource(resource);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = input.read(buffer)) >= 0; ) output.write(buffer, 0, n);
                    JSONObject root = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                    JSONArray rows = root.getJSONArray("strings");
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        String english = row.getString("english");
                        String translation = row.optString("translation", "");
                        if (translation.isEmpty()) continue;
                        if (PLACEHOLDER.matcher(english).find()) templates.add(new Template(english, translation));
                        else exact.put(english, translation);
                    }
                } catch (Exception ignored) {
                    exact.clear();
                    templates.clear();
                }
            }
            TranslationTable table = new TranslationTable(exact, templates);
            TABLES.put(language, table);
            return table;
        }
    }

    private static final class TranslationTable {
        final Map<String, String> exact;
        final List<Template> templates;

        TranslationTable(Map<String, String> exact, List<Template> templates) {
            this.exact = Collections.unmodifiableMap(exact);
            this.templates = Collections.unmodifiableList(templates);
        }
    }

    private static final class Template {
        final Pattern pattern;
        final String[] names;
        final String translation;

        Template(String english, String translation) {
            Matcher matcher = PLACEHOLDER.matcher(english);
            List<String> foundNames = new ArrayList<>();
            StringBuilder regex = new StringBuilder("^");
            int previous = 0;
            while (matcher.find()) {
                regex.append(Pattern.quote(english.substring(previous, matcher.start())));
                regex.append("([\\s\\S]*?)");
                foundNames.add(matcher.group(1));
                previous = matcher.end();
            }
            regex.append(Pattern.quote(english.substring(previous))).append("$");
            pattern = Pattern.compile(regex.toString());
            names = foundNames.toArray(new String[0]);
            this.translation = translation;
        }

        String apply(String value) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) return null;
            String result = translation;
            for (int i = 0; i < names.length; i++) {
                String actual = matcher.group(i + 1);
                result = result.replace("{" + names[i] + "}", actual == null ? "" : actual);
            }
            return result;
        }
    }
}
