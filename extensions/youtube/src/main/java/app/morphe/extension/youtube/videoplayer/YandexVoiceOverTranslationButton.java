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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private static WeakReference<YandexCountdownButton> overlayButtonRef;

    public static void initializeButton(View controlsView) {
        try {
            if (!Settings.DUAL_VOT_YANDEX_ENABLED.get()) return;
            VoiceOverTranslationCoordinator.addOnStateChangeCallback(STATE_REFRESH_CALLBACK);
            YandexCountdownButton button = PlayerOverlayButton.addButton(
                    controlsView,
                    new YandexCountdownButton(controlsView.getContext()),
                    "dualvot_yt_yandex_vot",
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
                button.captureIcon();
                button.post(STATE_REFRESH_CALLBACK);
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
            WeakReference<YandexCountdownButton> ref = overlayButtonRef;
            YandexCountdownButton overlay = ref != null ? ref.get() : null;
            if (overlay != null) {
                overlay.removeCallbacks(PROGRESS_TICK);

                boolean waiting = YandexVoiceOverTranslationPatch.translationStarting;
                boolean error = YandexVoiceOverTranslationPatch.isTranslationErrorVisible();
                int seconds = YandexVoiceOverTranslationPatch.getWaitingTimeSeconds();
                float progress = YandexVoiceOverTranslationPatch.getWaitingProgressFraction();
                String timerPosition = Settings.DUAL_VOT_YANDEX_TIMER_POSITION.get();
                boolean showTimer = waiting && !"hidden".equals(timerPosition);

                overlay.updateCountdown(
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

    private static final class YandexCountdownButton extends ImageView {
        @Nullable
        private Drawable icon;
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

        YandexCountdownButton(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

            textBackgroundPaint.setColor(0xB3000000);
            textBackgroundPaint.setStyle(Paint.Style.FILL);
        }

        void captureIcon() {
            Drawable image = getDrawable();
            if (image == null) return;

            Drawable.ConstantState iconState = image.getConstantState();
            icon = iconState != null
                    ? iconState.newDrawable(getResources()).mutate()
                    : image.mutate();
            icon.setCallback(this);
            // The icon is drawn manually so it can move upward and shrink only
            // while the "below" timer layout is active.
            setImageDrawable(null);
        }

        void updateCountdown(
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
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Drawable currentIcon = icon;
            if (currentIcon == null) {
                super.onDraw(canvas);
                return;
            }

            updateGeometry();
            float size = Math.min(getWidth(), getHeight());
            float centerX = getWidth() / 2.0f;
            float centerY = getHeight() / 2.0f;

            if (!waiting && !error) {
                drawIcon(canvas, centerX, centerY, size);
                return;
            }

            // Replacing the icon while the timer is inside avoids drawing digits
            // across the translate glyph. The below layout keeps the entire
            // icon + halo + timer composition inside the standard button height.
            if (!(waiting && showTimer && !timerBelow)) {
                drawIcon(
                        canvas,
                        ringBounds.centerX(),
                        ringBounds.centerY(),
                        ringBounds.width() * 0.56f
                );
            }
            if ((showRing && waiting) || error) {
                drawRing(canvas);
            }
            if (showTimer && waiting) {
                drawTimer(canvas);
            }
        }

        private void updateGeometry() {
            float size = Math.min(getWidth(), getHeight());
            float centerX = getWidth() / 2.0f;
            float centerY = getHeight() / 2.0f;
            float outerDiameter;

            if (waiting && showTimer && timerBelow) {
                // Reserve the lower part of the unchanged player-button view for
                // the label and reduce only the visual halo/icon composition.
                outerDiameter = size * 0.68f;
                centerY -= size * 0.14f;
            } else {
                // A small edge margin keeps thick configurable strokes from
                // clipping while still surrounding the visible player button.
                outerDiameter = Math.min(size - 2.0f * density, size * 0.92f);
            }

            float centerLineDiameter = Math.max(1.0f, outerDiameter - ringThicknessPx);
            setCenteredSquare(ringBounds, centerX, centerY, centerLineDiameter);
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

        private void drawIcon(Canvas canvas, float centerX, float centerY, float maxSide) {
            Drawable currentIcon = icon;
            if (currentIcon == null) return;

            int intrinsicWidth = currentIcon.getIntrinsicWidth();
            int intrinsicHeight = currentIcon.getIntrinsicHeight();
            float naturalSide = Math.min(
                    intrinsicWidth > 0 ? intrinsicWidth : 24.0f * density,
                    intrinsicHeight > 0 ? intrinsicHeight : 24.0f * density
            );
            float side = Math.max(1.0f, Math.min(naturalSide, maxSide));
            float half = side / 2.0f;
            iconBounds.set(
                    Math.round(centerX - half),
                    Math.round(centerY - half),
                    Math.round(centerX + half),
                    Math.round(centerY + half)
            );
            if (iconBounds.width() <= 0 || iconBounds.height() <= 0) return;

            currentIcon.setBounds(iconBounds);
            currentIcon.setAlpha(iconAlpha);
            currentIcon.draw(canvas);
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
            float size = Math.min(getWidth(), getHeight());
            float textSize = size * (timerBelow ? 0.135f : 0.19f);
            textPaint.setTextSize(Math.max((timerBelow ? 5.0f : 7.0f) * density, textSize));

            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float centerX = getWidth() / 2.0f;
            float centerY = timerBelow
                    ? getHeight() / 2.0f + size * 0.35f
                    : getHeight() / 2.0f;
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2.0f;
            float textWidth = textPaint.measureText(text);
            float horizontalPadding = (timerBelow ? 1.25f : 2.25f) * density;
            float verticalPadding = (timerBelow ? 0.2f : 0.75f) * density;

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
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            Drawable currentIcon = icon;
            if (currentIcon != null && currentIcon.isStateful()) {
                currentIcon.setState(getDrawableState());
            }
        }
    }
}
