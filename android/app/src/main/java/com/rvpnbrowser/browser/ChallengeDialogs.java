package com.rvpnbrowser.browser;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Builds the dialogs that answer the interactive challenges of the core
 * (docs/PROTOCOL.md section 2.6), mirroring the desktop extension:
 *
 * <ul>
 *   <li>{@code text} / {@code smsCode} / {@code totp} / {@code radiusCode}:
 *       text field, plus the captcha image when the core sent one,</li>
 *   <li>{@code secondaryAuth}: text field with a "skip" checkbox when the core
 *       allows skipping,</li>
 *   <li>{@code clickCode}: the captcha image with tap-to-select, coordinates are
 *       mapped back to the original image pixels,</li>
 *   <li>{@code externalLogin}: open the login URL in a tab and paste the
 *       callback URL back.</li>
 * </ul>
 *
 * <p>Open dialogs are tracked per activity so a challenge that was answered or
 * timed out elsewhere can be dismissed, and so nothing leaks a window when the
 * activity goes away.</p>
 */
public final class ChallengeDialogs {

    /** Host that can open a URL (used by externalLogin). */
    public interface Host {
        void openUrlInBrowser(String url);
    }

    private static final Map<Activity, Map<String, AlertDialog>> OPEN =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChallengeDialogs() {
    }

    /** Shows the dialog matching the challenge type. */
    public static void show(final Activity activity, final RvpnChallenge challenge,
                            final RvpnClient client, final Host host) {
        if (activity == null || challenge == null || activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }
        switch (challenge.type) {
            case RvpnChallenge.TYPE_CLICK_CODE:
                showClickCode(activity, challenge, client);
                break;
            case RvpnChallenge.TYPE_EXTERNAL_LOGIN:
                showExternalLogin(activity, challenge, client, host);
                break;
            default:
                showCode(activity, challenge, client);
                break;
        }
    }

    /** Dismisses the dialog of one challenge (answered or timed out). */
    public static void dismiss(Activity activity, String challengeId) {
        Map<String, AlertDialog> dialogs = OPEN.get(activity);
        if (dialogs == null || challengeId == null) {
            return;
        }
        AlertDialog dialog;
        synchronized (OPEN) {
            dialog = dialogs.remove(challengeId);
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /** Dismisses every dialog of this activity (call it from onStop/onDestroy). */
    public static void dismissAll(Activity activity) {
        Map<String, AlertDialog> dialogs;
        List<AlertDialog> copy;
        synchronized (OPEN) {
            dialogs = OPEN.remove(activity);
            copy = dialogs == null ? new ArrayList<>() : new ArrayList<>(dialogs.values());
        }
        for (AlertDialog dialog : copy) {
            if (dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                    // The window may already be gone.
                }
            }
        }
    }

    private static void track(Activity activity, String challengeId, AlertDialog dialog) {
        synchronized (OPEN) {
            Map<String, AlertDialog> dialogs = OPEN.get(activity);
            if (dialogs == null) {
                dialogs = new HashMap<>();
                OPEN.put(activity, dialogs);
            }
            dialogs.put(challengeId, dialog);
        }
        dialog.setOnDismissListener(d -> {
            synchronized (OPEN) {
                Map<String, AlertDialog> current = OPEN.get(activity);
                if (current != null) {
                    current.remove(challengeId);
                }
            }
        });
    }

    // ------------------------------------------------------------- text based

    private static void showCode(final Activity activity, final RvpnChallenge challenge,
                                 final RvpnClient client) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_challenge_code, null);
        TextView message = view.findViewById(R.id.challenge_message);
        ImageView image = view.findViewById(R.id.challenge_image);
        EditText input = view.findViewById(R.id.challenge_input);
        CheckBox skip = view.findViewById(R.id.challenge_skip);

        final String base = describe(activity, challenge);
        message.setText(base);
        if (challenge.type.equals(RvpnChallenge.TYPE_SMS_CODE)) {
            input.setHint(R.string.placeholder_sms_code);
        }
        if (challenge.hasImage()) {
            Bitmap bitmap = Ui.decodeDataUrl(challenge.imageDataUrl);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            } else {
                message.setText(base + "\n" + activity.getString(R.string.challenge_image_failed));
            }
        }
        if (RvpnChallenge.TYPE_SECONDARY_AUTH.equals(challenge.type)
                && challenge.canSkipSecondaryAuth) {
            skip.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.challenge_heading) + " · "
                        + activity.getString(challenge.typeLabelRes()))
                .setView(view)
                .setPositiveButton(R.string.challenge_submit, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
        startCountdown(activity, challenge, message, base, dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (skip.getVisibility() == View.VISIBLE && skip.isChecked()) {
                submit(activity, client, answerWithSkip(challenge));
                dialog.dismiss();
                return;
            }
            String code = input.getText().toString().trim();
            if (code.isEmpty()) {
                Ui.toast(activity, R.string.challenge_empty_input);
                return;
            }
            submit(activity, client, answerWithCode(challenge, code));
            dialog.dismiss();
        });
        track(activity, challenge.id, dialog);
    }

    // ------------------------------------------------------------ click codes

    private static void showClickCode(final Activity activity, final RvpnChallenge challenge,
                                      final RvpnClient client) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_click_code, null);
        TextView message = view.findViewById(R.id.challenge_message);
        final ClickCodeView clickView = view.findViewById(R.id.click_view);
        final TextView pointsText = view.findViewById(R.id.click_points);
        Button undo = view.findViewById(R.id.btn_undo);
        Button clear = view.findViewById(R.id.btn_clear);

        final String base = describe(activity, challenge);
        message.setText(base);
        Bitmap bitmap = Ui.decodeDataUrl(challenge.imageDataUrl);
        if (bitmap == null) {
            message.setText(base + "\n" + activity.getString(R.string.challenge_image_failed));
        } else {
            clickView.setImage(bitmap);
        }
        pointsText.setText(activity.getString(R.string.challenge_points, 0));
        clickView.setListener(count ->
                pointsText.setText(activity.getString(R.string.challenge_points, count)));
        undo.setOnClickListener(v -> clickView.undo());
        clear.setOnClickListener(v -> clickView.clearPoints());

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.challenge_heading) + " · "
                        + activity.getString(challenge.typeLabelRes()))
                .setView(view)
                .setPositiveButton(R.string.challenge_submit, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
        startCountdown(activity, challenge, message, base, dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Point> points = clickView.points();
            if (points.isEmpty()) {
                Ui.toast(activity, R.string.challenge_points_required);
                return;
            }
            JSONObject params = new JSONObject();
            try {
                JSONArray array = new JSONArray();
                for (Point p : points) {
                    JSONObject item = new JSONObject();
                    item.put("x", p.x);
                    item.put("y", p.y);
                    array.put(item);
                }
                params.put("challengeId", challenge.id);
                params.put("points", array);
                params.put("width", clickView.imageWidth());
                params.put("height", clickView.imageHeight());
            } catch (Exception e) {
                Ui.toast(activity, activity.getString(R.string.challenge_answer_failed,
                        String.valueOf(e.getMessage())));
                return;
            }
            submit(activity, client, params);
            dialog.dismiss();
        });
        track(activity, challenge.id, dialog);
    }

    // --------------------------------------------------------- external login

    private static void showExternalLogin(final Activity activity, final RvpnChallenge challenge,
                                          final RvpnClient client, final Host host) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_external_login, null);
        TextView message = view.findViewById(R.id.challenge_message);
        Button open = view.findViewById(R.id.btn_open_login);
        final EditText input = view.findViewById(R.id.callback_input);

        message.setText(describe(activity, challenge));
        open.setOnClickListener(v -> {
            if (challenge.loginUrl.isEmpty()) {
                Ui.toast(activity, R.string.challenge_no_login_url);
                return;
            }
            if (host != null) {
                host.openUrlInBrowser(challenge.loginUrl);
            }
        });
        // Convenience: the callback URL is usually copied right before coming back.
        String clipboard = Ui.readClipboard(activity);
        if (clipboard.startsWith("http://") || clipboard.startsWith("https://")) {
            input.setText(clipboard);
            input.setSelection(clipboard.length());
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.challenge_heading) + " · "
                        + activity.getString(challenge.typeLabelRes()))
                .setView(view)
                .setPositiveButton(R.string.challenge_submit, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = input.getText().toString().trim();
            if (code.isEmpty()) {
                Ui.toast(activity, R.string.challenge_empty_input);
                return;
            }
            submit(activity, client, answerWithCode(challenge, code));
            dialog.dismiss();
        });
        track(activity, challenge.id, dialog);
    }

    // ---------------------------------------------------------------- helpers

    private static String describe(Activity activity, RvpnChallenge challenge) {
        StringBuilder sb = new StringBuilder();
        if (!challenge.message.isEmpty()) {
            sb.append(challenge.message);
        } else {
            sb.append(activity.getString(challenge.typeLabelRes()));
        }
        return sb.toString();
    }

    /** Keeps "剩余时间 m:ss" up to date and marks an expired challenge. */
    private static void startCountdown(Activity activity, RvpnChallenge challenge,
                                       TextView target, String base, AlertDialog dialog) {
        if (challenge.deadline <= 0) {
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            @Override
            public void run() {
                long left = challenge.remainingMs();
                String suffix = left <= 0
                        ? activity.getString(R.string.challenge_expired)
                        : activity.getString(R.string.challenge_deadline_in, Ui.formatDuration(left));
                target.setText(base + "\n" + suffix);
                // The loop stops by itself as soon as the dialog is gone; the
                // dismiss listener belongs to track().
                if (left > 0 && dialog.isShowing()) {
                    handler.postDelayed(this, 1000L);
                }
            }
        };
        handler.post(tick[0]);
    }

    private static JSONObject answerWithCode(RvpnChallenge challenge, String code) {
        JSONObject params = new JSONObject();
        try {
            params.put("challengeId", challenge.id);
            params.put("code", code);
        } catch (Exception ignored) {
            // put(String) cannot fail.
        }
        return params;
    }

    private static JSONObject answerWithSkip(RvpnChallenge challenge) {
        JSONObject params = new JSONObject();
        try {
            params.put("challengeId", challenge.id);
            params.put("skipSecondaryAuth", true);
        } catch (Exception ignored) {
            // put(String) cannot fail.
        }
        return params;
    }

    private static void submit(final Activity activity, RvpnClient client, JSONObject params) {
        if (client == null) {
            return;
        }
        client.call("answer", params, RvpnClient.DEFAULT_TIMEOUT_MS, (data, error) -> {
            if (error != null) {
                Ui.toast(activity, activity.getString(R.string.challenge_answer_failed,
                        error.getMessage()));
            } else {
                Ui.toast(activity, R.string.challenge_answer_sent);
            }
        });
    }
}
