package me.champdev.weblock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;

/** Text view for subtitles, with a separate crisp outline pass and drop shadow. */
final class SubtitleTextView extends LocalizedTextView {
    private int outlineColor = android.graphics.Color.BLACK;
    private float outlineWidthPx;
    private int shadowColor = android.graphics.Color.BLACK;
    private float shadowRadiusPx;
    private float shadowDxPx;
    private float shadowDyPx;

    SubtitleTextView(Context context) { super(context); }
    SubtitleTextView(Context context, AttributeSet attrs) { super(context, attrs); }

    void setOutline(float widthPx, int color) {
        outlineWidthPx = Math.max(0f, widthPx);
        outlineColor = color;
        invalidate();
    }

    void setSubtitleShadow(float radiusPx, float dxPx, float dyPx, int color) {
        shadowRadiusPx = Math.max(0f, radiusPx);
        shadowDxPx = dxPx;
        shadowDyPx = dyPx;
        shadowColor = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        TextPaint paint = getPaint();
        Paint.Style oldStyle = paint.getStyle();
        Paint.Join oldJoin = paint.getStrokeJoin();
        float oldWidth = paint.getStrokeWidth();
        float oldMiter = paint.getStrokeMiter();
        int textColor = getCurrentTextColor();

        if (outlineWidthPx > 0f) {
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(outlineWidthPx);
            paint.setStrokeMiter(4f);
            paint.setColor(outlineColor);
            super.onDraw(canvas);
        }

        paint.setStyle(oldStyle);
        paint.setStrokeJoin(oldJoin);
        paint.setStrokeWidth(oldWidth);
        paint.setStrokeMiter(oldMiter);
        paint.setColor(textColor);
        if (shadowRadiusPx > 0f || shadowDxPx != 0f || shadowDyPx != 0f) {
            paint.setShadowLayer(shadowRadiusPx, shadowDxPx, shadowDyPx, shadowColor);
        } else {
            paint.clearShadowLayer();
        }
        super.onDraw(canvas);
    }
}
