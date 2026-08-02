package com.example.multiusershare;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

/**
 * Offline address marker. It intentionally keeps the control page dependency-free; the
 * address is also printed below so it remains usable with browsers that do not scan it.
 */
final class QrCodeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String value = "";
    private Bitmap bitmap;
    private int bitmapSize;
    private String bitmapValue = "";

    QrCodeView(Context context) { super(context); setMinimumHeight(220); }
    void setValue(String value) {
        String next = value == null ? "" : value;
        if (next.equals(this.value)) return;
        this.value = next;
        bitmap = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;
        paint.setColor(Color.WHITE);
        canvas.drawRect(left, top, left + size, top + size, paint);
        try {
            ensureBitmap(size);
            canvas.drawBitmap(bitmap, left, top, paint);
        } catch (Exception ignored) {
            paint.setColor(Color.BLACK);
            paint.setTextSize(14);
            canvas.drawText("二维码生成失败", left + 18, top + size / 2f, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.LTGRAY);
        canvas.drawRoundRect(new RectF(left, top, left + size, top + size), 6, 6, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void ensureBitmap(int size) throws Exception {
        if (bitmap != null && bitmapSize == size && bitmapValue.equals(value)) return;
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        bitmapSize = size;
        bitmapValue = value;
    }

}
