package com.fader.tv;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Mirrored audio spectrum drawn as thin rounded bars around a centre line.
 * Uses real FFT levels when a {@link Source} provides them; otherwise it
 * falls back to a musical pseudo-beat so the effect still feels alive on TVs
 * where the audio tap is unavailable. Nothing here touches playback.
 */
final class SpectrumView extends View {
    interface Source { boolean read(float[] out); }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bars = 48;
    private float[] levels = new float[bars];
    private float[] peaks = new float[bars];
    private float[] external = new float[bars];
    private LinearGradient gradient;
    private int gradientHeight;
    private boolean playing, running, attached, fadeEdges;
    private long lastFrame = System.nanoTime();
    private double clockMs;
    private int seed = 1;
    private float bass;
    private Source source;

    SpectrumView(Context context) {
        super(context);
        capPaint.setColor(0xff9ed0ff);
    }

    void setBars(int count) {
        if (count < 8 || count == bars) return;
        bars = count;
        levels = new float[bars];
        peaks = new float[bars];
        external = new float[bars];
        gradient = null;
        invalidate();
    }

    /** Each track gets its own pseudo pattern, so the motion changes with the song. */
    void setSeed(int value) { seed = value; }

    void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value;
        if (value) {
            lastFrame = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    void setSource(Source value) { source = value; }

    /** Ambient uses a wide band with the outer bars faded, like the mockup. */
    void setFadeEdges(boolean value) { fadeEdges = value; }

    float bass() { return bass; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrame = System.nanoTime();
        postInvalidateOnAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        running = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        running = visibility == VISIBLE;
        if (running) postInvalidateOnAnimation();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        running = visibility == VISIBLE;
        if (running) postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        gradient = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        long now = System.nanoTime();
        advance(now);

        if (gradient == null || gradientHeight != height) {
            gradientHeight = height;
            gradient = new LinearGradient(0, 0, 0, height,
                new int[]{0xffa7d4ff, 0xff63adff, 0xff2c4764}, null, Shader.TileMode.CLAMP);
        }
        barPaint.setShader(gradient);

        float gap = Math.max(2f, width * 0.0055f);
        float barWidth = Math.max(2f, (width - gap * (bars - 1)) / bars);
        float midY = height / 2f;
        float maxHeight = height - 4f;

        for (int i = 0; i < bars; i++) {
            float alpha = 1f;
            if (fadeEdges) {
                float edge = Math.min(i, bars - 1 - i) / (bars * 0.13f);
                alpha = 0.12f + 0.88f * Math.min(1f, edge);
            }
            int a = (int) (255 * alpha);
            float level = Math.min(1f, Math.max(0f, levels[i]));
            float barHeight = Math.max(2f, level * maxHeight);
            float x = i * (barWidth + gap);
            float top = midY - barHeight / 2f;
            barPaint.setAlpha(a);
            canvas.drawRoundRect(x, top, x + barWidth, top + barHeight, barWidth / 2f, barWidth / 2f, barPaint);

            float peak = Math.min(1f, Math.max(0f, peaks[i]));
            if (peak > 0.01f) {
                float py = midY - peak * maxHeight / 2f;
                capPaint.setAlpha((int) (200 * alpha));
                canvas.drawRoundRect(x, py - 1.4f, x + barWidth, py + 1.4f, 1.4f, 1.4f, capPaint);
            }
        }
        barPaint.setShader(null);

        if (attached && running && (playing || bass > 0.004f)) postInvalidateOnAnimation();
    }

    private void advance(long now) {
        long delta = Math.min(70L, Math.max(0L, (now - lastFrame) / 1_000_000L));
        lastFrame = now;
        if (playing) clockMs += delta;
        float t = (float) (clockMs / 1000.0);
        float bpm = 104f + Math.abs(seed % 27);
        float beatMs = 60000f / bpm;
        float phase = (float) ((clockMs % beatMs) / beatMs);
        int beatIndex = (int) (clockMs / beatMs);
        float kick = (float) Math.exp(-phase * 9f);
        float snare = (beatIndex % 2 == 1) ? (float) Math.exp(-phase * 13f) * 0.6f : 0f;
        float hatPhase = (float) ((clockMs % (beatMs / 2f)) / (beatMs / 2f));
        float hat = (float) Math.exp(-hatPhase * 18f) * 0.3f;

        boolean real = false;
        if (source != null) {
            if (external.length != bars) external = new float[bars];
            real = source.read(external);
        }

        for (int i = 0; i < bars; i++) {
            float target;
            if (!playing) {
                target = 0f;
            } else if (real) {
                target = Math.min(1f, Math.max(0f, external[i]));
            } else {
                float x = i / (float) (bars - 1);
                float bassBand = gauss(x, 0.10f, 0.16f);
                float midBand = gauss(x, 0.46f, 0.26f);
                float highBand = gauss(x, 0.80f, 0.22f);
                target = bassBand * (0.42f + 0.58f * kick)
                    + midBand * (0.20f + 0.18f * (float) Math.sin(t * 2.1f + i * 0.31f) + 0.10f * snare)
                    + highBand * (0.09f + 0.13f * (float) Math.sin(t * 3.7f + i * 0.67f) + 0.15f * hat)
                    + 0.02f * (float) Math.random();
                target = Math.min(1f, Math.max(0f, target));
            }
            float attack = real ? 0.62f : 0.48f;
            float release = real ? 0.20f : 0.12f;
            levels[i] += (target - levels[i]) * (target > levels[i] ? attack : release);
            peaks[i] = Math.max(levels[i], peaks[i] - 0.005f * Math.max(1, delta / 16f));
        }

        float sum = 0f;
        int bassBars = Math.max(3, bars / 6);
        for (int i = 0; i < bassBars; i++) sum += levels[i];
        bass = sum / bassBars;
    }

    private static float gauss(float x, float centre, float spread) {
        float d = (x - centre) / spread;
        return (float) Math.exp(-d * d);
    }
}
