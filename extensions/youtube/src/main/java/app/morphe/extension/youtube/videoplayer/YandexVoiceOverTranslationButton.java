/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s):
 * - Jav1x (https://github.com/Jav1x)
 *
 * Ported to morphe-patches: https://github.com/MorpheApp/morphe-patches
 * Modified by: Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

/* Modified for Dual VoT Patches: separate Yandex player button. */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationCoordinator;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVoiceOverTranslationPatch;
import app.morphe.extension.youtube.patches.voiceovertranslation.yandex.YandexVotBottomSheet;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class YandexVoiceOverTranslationButton {
    private static final long DETERMINATE_FRAME_DELAY_MS = 250;
    private static final long INDETERMINATE_FRAME_DELAY_MS = 50;
    private static final int ERROR_COLOR = 0xFFFF3B30;

    private static final Runnable STATE_REFRESH_CALLBACK =
            YandexVoiceOverTranslationButton::refreshActivatedState;
    private static final Runnable PROGRESS_TICK =
            YandexVoiceOverTranslationButton::refreshActivatedState;

    @Nullable
    private static WeakReference<ImageView> overlayButtonRef;
    @Nullable
    private static WeakReference<CountdownDrawable> countdownDrawableRef;

    public static void initializeButton(View controlsView) {
        try {
            if (!Settings.DUAL_VOT_YANDEX_ENABLED.get()) return;
            VoiceOverTranslationCoordinator.addOnStateChangeCallback(STATE_REFRESH_CALLBACK);
            ImageView button = PlayerOverlayButton.addButton(controlsView, "dualvot_yt_yandex_vot",
                    view -> {
                        VoiceOverTranslationCoordinator.toggleYandex();
                        refreshActivatedState();
                    },
                    view -> {
                        YandexVotBottomSheet.show(view.getContext());
                        return true;
                    });
            overlayButtonRef = button != null ? new WeakReference<>(button) : null;
            if (button != null) {
                CountdownDrawable drawable = new CountdownDrawable(button, button.getDrawable());
                countdownDrawableRef = new WeakReference<>(drawable);
                // Keep the indicator in the same square drawable coordinate space as
                // the icon. A foreground receives the entire, sometimes rectangular,
                // touch target and produces an oversized oval in compact players.
                button.setImageDrawable(drawable);
                button.setImageAlpha(255);
                button.post(STATE_REFRESH_CALLBACK);
            } else {
                countdownDrawableRef = null;
            }
            refreshActivatedState();
        } catch (Exception ex) {
            Logger.printException(() -> "YandexVoiceOverTranslationButton initializeButton failure", ex);
        }
    }

    private static void refreshActivatedState() {
        Utils.verifyOnMainThread();
        try {
            boolean active = VoiceOverTranslationCoordinator.isYandexActive();
            int alpha = active ? 255 : 128;
            WeakReference<ImageView> ref = overlayButtonRef;
            ImageView overlay = ref != null ? ref.get() : null;
            if (overlay != null) {
                overlay.removeCallbacks(PROGRESS_TICK);

                boolean waiting = YandexVoiceOverTranslationPatch.translationStarting;
                boolean error = YandexVoiceOverTranslationPatch.isTranslationErrorVisible();
                int seconds = YandexVoiceOverTranslationPatch.getWaitingTimeSeconds();
                float progress = YandexVoiceOverTranslationPatch.getWaitingProgressFraction();
                String timerPosition = Settings.DUAL_VOT_YANDEX_TIMER_POSITION.get();
                boolean showTimer = waiting && !"hidden".equals(timerPosition);

                WeakReference<CountdownDrawable> drawableRef = countdownDrawableRef;
                CountdownDrawable drawable = drawableRef != null ? drawableRef.get() : null;
                if (drawable != null) {
                    drawable.update(
                            waiting,
                            error,
                            showTimer,
                            "below".equals(timerPosition),
                            Settings.DUAL_VOT_YANDEX_PROGRESS_RING_ENABLED.get(),
                            Settings.DUAL_VOT_YANDEX_PROGRESS_RING_COLOR.get(),
                            Settings.DUAL_VOT_YANDEX_PROGRESS_RING_THICKNESS.get(),
                            seconds,
                            progress,
                            alpha
                    );
                }

                if (waiting || error) {
                    boolean indeterminate = error || seconds <= 0 || progress < 0.0f;
                    overlay.postDelayed(
                            PROGRESS_TICK,
                            indeterminate
                                    ? INDETERMINATE_FRAME_DELAY_MS
                                    : DETERMINATE_FRAME_DELAY_MS
                    );
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "refreshActivatedState failure", ex);
        }
    }

    private static final class CountdownDrawable extends Drawable {
        private final Drawable icon;
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ringBounds = new RectF();
        private final RectF textBackgroundBounds = new RectF();
        private final Rect iconBounds = new Rect();
        private final float density;

        private boolean waiting;
        private boolean error;
        private boolean showTimer;
        private boolean timerBelow;
        private boolean showRing;
        private int ringColor = 0xFFFFC107;
        private float ringThicknessPx;
        private int remainingSeconds = -1;
        private float progress = -1.0f;
        private int iconAlpha = 128;

        CountdownDrawable(ImageView owner, Drawable icon) {
            density = owner.getResources().getDisplayMetrics().density;
            Drawable.ConstantState iconState = icon.getConstantState();
            this.icon = iconState != null
                    ? iconState.newDrawable(owner.getResources()).mutate()
                    : icon.mutate();
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

            textBackgroundPaint.setColor(0xB3000000);
            textBackgroundPaint.setStyle(Paint.Style.FILL);
        }

        void update(
                boolean waiting,
                boolean error,
                boolean showTimer,
                boolean timerBelow,
                boolean showRing,
                String configuredColor,
                int configuredThicknessDp,
                int remainingSeconds,
                float progress,
                int iconAlpha
        ) {
            this.waiting = waiting;
            this.error = error;
            this.showTimer = showTimer;
            this.timerBelow = timerBelow;
            this.showRing = showRing;
            this.ringColor = parseColor(configuredColor);
            this.ringThicknessPx = Math.max(1.0f, configuredThicknessDp * density);
            this.remainingSeconds = remainingSeconds;
            this.progress = progress;
            this.iconAlpha = Math.max(0, Math.min(255, iconAlpha));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            updateGeometry();

            // Replacing the icon while the timer is inside avoids drawing digits
            // across the translate glyph. All other states retain the icon.
            if (!(waiting && showTimer && !timerBelow)) {
                drawIcon(canvas);
            }
            if ((showRing && waiting) || error) {
                drawRing(canvas);
            }
            if (showTimer && waiting) {
                drawTimer(canvas);
            }
        }

        private void updateGeometry() {
            Rect bounds = getBounds();
            float size = Math.min(bounds.width(), bounds.height());
            float centerX = bounds.exactCenterX();
            float centerY = bounds.exactCenterY();
            float strokeInset = ringThicknessPx / 2.0f + 0.75f * density;

            if (waiting && showTimer && timerBelow) {
                float iconAreaSide = Math.max(1.0f, size * 0.68f);
                float compositionTop = centerY - size / 2.0f;
                float iconCenterY = compositionTop + size * 0.36f;
                setCenteredSquare(
                        ringBounds,
                        centerX,
                        iconCenterY,
                        Math.max(1.0f, iconAreaSide - 2.0f * strokeInset)
                );
            } else {
                setCenteredSquare(
                        ringBounds,
                        centerX,
                        centerY,
                        Math.max(1.0f, size - 2.0f * strokeInset)
                );
            }
        }

        private static void setCenteredSquare(
                RectF target,
                float centerX,
                float centerY,
                float side
        ) {
            float half = side / 2.0f;
            target.set(centerX - half, centerY - half, centerX + half, centerY + half);
        }

        private void drawIcon(Canvas canvas) {
            boolean ringVisible = (showRing && waiting) || error;
            if (!ringVisible && !(waiting && showTimer && timerBelow)) {
                iconBounds.set(getBounds());
            } else {
                float inset = ringThicknessPx / 2.0f + 1.25f * density;
                iconBounds.set(
                        Math.round(ringBounds.left + inset),
                        Math.round(ringBounds.top + inset),
                        Math.round(ringBounds.right - inset),
                        Math.round(ringBounds.bottom - inset)
                );
            }
            if (iconBounds.width() <= 0 || iconBounds.height() <= 0) return;

            icon.setBounds(iconBounds);
            icon.setAlpha(iconAlpha);
            icon.draw(canvas);
        }

        private void drawRing(Canvas canvas) {
            ringPaint.setStrokeWidth(ringThicknessPx);

            if (error) {
                float pulse = (float) ((Math.sin(SystemClock.uptimeMillis() / 90.0) + 1.0) / 2.0);
                ringPaint.setColor(withAlpha(ERROR_COLOR, Math.round(120 + pulse * 135)));
                float start = (SystemClock.uptimeMillis() / 3.0f) % 360.0f - 90.0f;
                canvas.drawArc(ringBounds, start, 115.0f, false, ringPaint);
                return;
            }

            ringPaint.setColor(withAlpha(ringColor, 48));
            canvas.drawArc(ringBounds, -90.0f, 360.0f, false, ringPaint);

            ringPaint.setColor(ringColor);
            if (remainingSeconds > 0 && progress >= 0.0f) {
                canvas.drawArc(
                        ringBounds,
                        -90.0f,
                        360.0f * Math.max(0.0f, Math.min(1.0f, progress)),
                        false,
                        ringPaint
                );
            } else {
                float start = (SystemClock.uptimeMillis() / 3.0f) % 360.0f - 90.0f;
                canvas.drawArc(ringBounds, start, 100.0f, false, ringPaint);
            }
        }

        private void drawTimer(Canvas canvas) {
            String text = formatTimerText(remainingSeconds);
            Rect bounds = getBounds();
            float size = Math.min(bounds.width(), bounds.height());
            float textSize = size * (timerBelow ? 0.22f : 0.29f);
            textPaint.setTextSize(Math.max((timerBelow ? 5.5f : 7.0f) * density, textSize));

            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float centerX = bounds.exactCenterX();
            float centerY = timerBelow
                    ? bounds.exactCenterY() + size * 0.35f
                    : bounds.exactCenterY();
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2.0f;
            float textWidth = textPaint.measureText(text);
            float horizontalPadding = (timerBelow ? 1.5f : 2.25f) * density;
            float verticalPadding = (timerBelow ? 0.25f : 0.75f) * density;

            textBackgroundBounds.set(
                    centerX - textWidth / 2.0f - horizontalPadding,
                    baseline + metrics.ascent - verticalPadding,
                    centerX + textWidth / 2.0f + horizontalPadding,
                    baseline + metrics.descent + verticalPadding
            );
            float radius = textBackgroundBounds.height() / 2.0f;
            canvas.drawRoundRect(textBackgroundBounds, radius, radius, textBackgroundPaint);
            canvas.drawText(text, centerX, baseline, textPaint);
        }

        private static String formatTimerText(int seconds) {
            if (seconds <= 0) return "\u2026";
            if (seconds >= 60) {
                int minutes = (seconds + 59) / 60;
                return str("dualvot_yandex_button_time_minutes", minutes);
            }
            return str("dualvot_yandex_button_time_seconds", seconds);
        }

        private static int parseColor(String value) {
            try {
                return Color.parseColor(value);
            } catch (Exception ignored) {
                return 0xFFFFC107;
            }
        }

        private static int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
        }

        @Override
        public void setAlpha(int alpha) {
            ringPaint.setAlpha(alpha);
            textPaint.setAlpha(alpha);
            icon.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            ringPaint.setColorFilter(colorFilter);
            textPaint.setColorFilter(colorFilter);
            icon.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getIntrinsicWidth() {
            return icon.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return icon.getIntrinsicHeight();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
