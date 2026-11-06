package me.champdev.weblock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** TextView used for app-owned UI labels; dynamic content views use PlainTextView instead. */
class LocalizedTextView extends TextView {
    private boolean translateText = true;

    LocalizedTextView(Context context) { super(context); }
    LocalizedTextView(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override public void setText(CharSequence text, BufferType type) {
        super.setText(translateText ? Localization.translate(getContext(), text) : text, type);
    }

    void setTextUnlocalized(CharSequence text) {
        translateText = false;
        try {
            super.setText(text, BufferType.NORMAL);
        } finally {
            translateText = true;
        }
    }

    @Override public void setContentDescription(CharSequence contentDescription) {
        super.setContentDescription(Localization.translate(getContext(), contentDescription));
    }
}

/** Plain content label for server-provided titles, channel names and summaries. */
class PlainTextView extends TextView {
    PlainTextView(Context context) { super(context); }
    PlainTextView(Context context, AttributeSet attrs) { super(context, attrs); }
}

/** Edit fields never transform text entered or supplied by the user. */
class LocalizedEditText extends EditText {
    LocalizedEditText(Context context) { super(context); }
    LocalizedEditText(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override public void setContentDescription(CharSequence contentDescription) {
        super.setContentDescription(Localization.translate(getContext(), contentDescription));
    }
}

class LocalizedButton extends Button {
    LocalizedButton(Context context) { super(context); }
    LocalizedButton(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override public void setText(CharSequence text, BufferType type) {
        super.setText(Localization.translate(getContext(), text), type);
    }

    @Override public void setContentDescription(CharSequence contentDescription) {
        super.setContentDescription(Localization.translate(getContext(), contentDescription));
    }
}

/** Applies the same UI-string table to native alert dialogs and their visible list/button labels. */
class LocalizedDialogBuilder extends AlertDialog.Builder {
    private final Context context;

    LocalizedDialogBuilder(Context context) {
        super(context);
        this.context = context;
    }

    @Override public AlertDialog.Builder setTitle(CharSequence title) {
        return super.setTitle(Localization.translate(context, title));
    }

    @Override public AlertDialog.Builder setMessage(CharSequence message) {
        return super.setMessage(Localization.translate(context, message));
    }

    @Override public AlertDialog.Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
        return super.setPositiveButton(Localization.translate(context, text), listener);
    }

    @Override public AlertDialog.Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
        return super.setNegativeButton(Localization.translate(context, text), listener);
    }

    @Override public AlertDialog.Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
        return super.setNeutralButton(Localization.translate(context, text), listener);
    }

    @Override public AlertDialog.Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        if (items == null) return super.setItems(null, listener);
        CharSequence[] translated = new CharSequence[items.length];
        for (int i = 0; i < items.length; i++) translated[i] = Localization.translate(context, items[i]);
        return super.setItems(translated, listener);
    }
}

final class LocalizedToast {
    private LocalizedToast() { }

    static Toast makeText(Context context, CharSequence text, int duration) {
        return Toast.makeText(context, Localization.translate(context, text), duration);
    }

    static Toast makeText(Context context, int resid, int duration) {
        return Toast.makeText(context, Localization.translate(context, context.getText(resid)), duration);
    }
}
