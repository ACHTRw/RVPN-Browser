package com.rvpnbrowser.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shows a click captcha and records the taps.
 *
 * <p>The points are stored in <b>original image pixels</b>, exactly as
 * docs/PROTOCOL.md section 2.6 requires: the view scales the bitmap to fit, and
 * every tap is mapped back through the same transform. The markers are drawn on
 * top of the scaled image so the user sees what was selected.</p>
 */
public class ClickCodeView extends View {

    /** Called whenever the selection changes. */
    public interface Listener {
        void onPointsChanged(int count);
    }

    private static final float MAX_TOUCH_SLOP_DP = 12f;

    private final List<Point> points = new ArrayList<>();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap bitmap;
    private Listener listener;
    private float downX;
    private float downY;
    private final float touchSlop;

    public ClickCodeView(Context context) {
        this(context, null);
    }

    public ClickCodeView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ClickCodeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = MAX_TOUCH_SLOP_DP * getResources().getDisplayMetrics().density;
        markerPaint.setColor(ContextCompat.getColor(context, R.color.insecure));
        markerPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(ContextCompat.getColor(context, R.color.text_on_brand));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        numberPaint.setColor(ContextCompat.getColor(context, R.color.text_on_brand));
        numberPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setFakeBoldText(true);
        numberPaint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 11f,
                getResources().getDisplayMetrics()));
        setFocusable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setImage(Bitmap image) {
        this.bitmap = image;
        points.clear();
        requestLayout();
        invalidate();
        notifyListener();
    }

    public boolean hasImage() {
        return bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    public int imageWidth() {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    public int imageHeight() {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    /** A copy of the selected points, in original image pixels. */
    public List<Point> points() {
        synchronized (points) {
            return Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    public void undo() {
        synchronized (points) {
            if (!points.isEmpty()) {
                points.remove(points.size() - 1);
            }
        }
        invalidate();
        notifyListener();
    }

    public void clearPoints() {
        synchronized (points) {
            points.clear();
        }
        invalidate();
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onPointsChanged(points().size());
        }
    }

    // ------------------------------------------------------------- measuring

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.challenge_image_max_height);
        int height;
        if (hasImage()) {
            height = Math.round(available * (float) imageHeight() / (float) imageWidth());
            if (height > maxHeight) {
                height = maxHeight;
            }
        } else {
            height = Math.round(120f * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(available, Math.max(1, height));
    }

    // -------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasImage()) {
            return;
        }
        float scale = scale();
        float drawWidth = imageWidth() * scale;
        float drawHeight = imageHeight() * scale;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f;
        RectF destination = new RectF(left, top, left + drawWidth, top + drawHeight);
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint);

        float radius = 11f * getResources().getDisplayMetrics().density;
        synchronized (points) {
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                float cx = left + p.x * scale;
                float cy = top + p.y * scale;
                canvas.drawCircle(cx, cy, radius, markerPaint);
                canvas.drawCircle(cx, cy, radius, ringPaint);
                canvas.drawText(String.valueOf(i + 1), cx,
                        cy + numberPaint.getTextSize() / 3f, numberPaint);
            }
        }
    }

    private float scale() {
        if (!hasImage()) {
            return 1f;
        }
        return Math.min(getWidth() / (float) imageWidth(), getHeight() / (float) imageHeight());
    }

    // --------------------------------------------------------------- touches

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!hasImage()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    // A drag (scrolling the dialog), not a tap.
                    return true;
                }
                addPoint(event.getX(), event.getY());
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void addPoint(float viewX, float viewY) {
        float scale = scale();
        if (scale <= 0f) {
            return;
        }
        float drawWidth = imageWidth() * scale;
        float drawHeight = imageHeight() * scale;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f;
        if (viewX < left || viewX > left + drawWidth || viewY < top || viewY > top + drawHeight) {
            return;
        }
        int originalX = Math.round((viewX - left) / scale);
        int originalY = Math.round((viewY - top) / scale);
        originalX = Math.max(0, Math.min(imageWidth() - 1, originalX));
        originalY = Math.max(0, Math.min(imageHeight() - 1, originalY));
        synchronized (points) {
            points.add(new Point(originalX, originalY));
        }
        invalidate();
        notifyListener();
    }
}
