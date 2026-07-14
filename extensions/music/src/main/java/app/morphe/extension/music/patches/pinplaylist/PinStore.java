package app.morphe.extension.music.patches.pinplaylist;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local storage for pinned playlist IDs. Pure SharedPreferences, no
 * dependency on any app-internal (obfuscated) classes, so this file should
 * compile and run unchanged across app versions.
 */
public final class PinStore {

    private static final String TAG = "PinMod";
    private static final String PREFS_NAME = "pin_mod_prefs";
    private static final String KEY_PINNED = "pinned_playlist_ids";
    private static final String KEY_PIN_ORDER = "pinned_playlist_order";
    private static final String KEY_FEATURE_STATE_INITIALIZED =
            "pin_feature_state_initialized";
    private static final String KEY_FEATURE_ENABLED_LAST_PROCESS =
            "pin_feature_enabled_last_process";
    private static final String KEY_SIGNATURE_PREFIX =
            "playlist_signature:";

    private static final Pattern PLAYLIST_ID_PATTERN =
            Pattern.compile(
                    "(?:OLAK5uy_[A-Za-z0-9_-]{8,}|"
                            + "PL[A-Za-z0-9_-]{30,}|"
                            + "LRSR[A-Za-z0-9_-]{8,})"
            );

    private static volatile Boolean cachedHasAnyPins;

    private PinStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isSupportedPlaylistId(String playlistId) {
        return playlistId != null
                && ("LM".equals(playlistId)
                || PLAYLIST_ID_PATTERN.matcher(playlistId).matches());
    }

    /**
     * Synchronizes persisted pin data with the restart-required feature state.
     *
     * Returning to enabled after the feature was actually run disabled starts
     * a new pin session: old pinned IDs and chronology are removed. The first
     * run after installing this version only initializes the marker and keeps
     * the user's current pins.
     *
     * @return true when a disabled -> enabled transition cleared old pins.
     */
    public static synchronized boolean synchronizeFeatureState(
            Context context,
            boolean enabled
    ) {
        if (context == null) return false;

        SharedPreferences preferences = prefs(context);

        if (!preferences.getBoolean(
                KEY_FEATURE_STATE_INITIALIZED,
                false
        )) {
            preferences.edit()
                    .putBoolean(
                            KEY_FEATURE_STATE_INITIALIZED,
                            true
                    )
                    .putBoolean(
                            KEY_FEATURE_ENABLED_LAST_PROCESS,
                            enabled
                    )
                    .apply();
            return false;
        }

        boolean previouslyEnabled =
                preferences.getBoolean(
                        KEY_FEATURE_ENABLED_LAST_PROCESS,
                        enabled
                );

        boolean clearPins =
                enabled && !previouslyEnabled;

        SharedPreferences.Editor editor =
                preferences.edit()
                        .putBoolean(
                                KEY_FEATURE_ENABLED_LAST_PROCESS,
                                enabled
                        );

        if (clearPins) {
            editor.remove(KEY_PINNED)
                    .remove(KEY_PIN_ORDER);
            cachedHasAnyPins = false;
        }

        editor.apply();

        Log.d(TAG, "synchronizeFeatureState"
                + " previousEnabled=" + previouslyEnabled
                + " enabled=" + enabled
                + " clearedPins=" + clearPins);

        return clearPins;
    }

    public static boolean hasAnyPins(Context context) {
        Boolean cached = cachedHasAnyPins;
        if (cached != null) return cached;

        return !getPinnedIds(context).isEmpty();
    }

    public static Set<String> getPinnedIds(Context context) {
        SharedPreferences preferences = prefs(context);
        Set<String> storedValues = preferences.getStringSet(
                KEY_PINNED,
                new HashSet<>()
        );

        HashSet<String> validStored = new HashSet<>();
        boolean removedInvalidValue = false;

        if (storedValues != null) {
            for (String value : storedValues) {
                if (isSupportedPlaylistId(value)) {
                    validStored.add(value);
                } else if (value != null && !value.isEmpty()) {
                    removedInvalidValue = true;
                }
            }
        }

        LinkedHashSet<String> ordered =
                new LinkedHashSet<>();
        String serialized =
                preferences.getString(KEY_PIN_ORDER, "");

        if (serialized != null && !serialized.isEmpty()) {
            String[] values = serialized.split("\n", -1);

            for (String value : values) {
                if (isSupportedPlaylistId(value)
                        && validStored.contains(value)) {
                    ordered.add(value);
                } else if (!value.isEmpty()) {
                    removedInvalidValue = true;
                }
            }
        }

        // Migration for pins created before ordered persistence existed.
        for (String value : validStored) {
            ordered.add(value);
        }

        String cleanedOrder =
                serializeOrder(ordered);

        if (removedInvalidValue
                || !cleanedOrder.equals(serialized)
                || !validStored.equals(storedValues)) {
            preferences.edit()
                    .putStringSet(
                            KEY_PINNED,
                            new HashSet<>(ordered)
                    )
                    .putString(
                            KEY_PIN_ORDER,
                            cleanedOrder
                    )
                    .apply();

            if (removedInvalidValue) {
                Log.d(TAG, "Removed unsupported playlist IDs"
                        + " from persisted pin state");
            }
        }

        cachedHasAnyPins = !ordered.isEmpty();
        return ordered;
    }

    public static boolean isPinned(Context context, String playlistId) {
        if (!isSupportedPlaylistId(playlistId)) {
            return false;
        }
        return getPinnedIds(context).contains(playlistId);
    }

    public static void setPinned(Context context, String playlistId, boolean pinned) {
        if (!isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "Ignoring unsupported playlist ID");
            return;
        }

        LinkedHashSet<String> updated =
                new LinkedHashSet<>(getPinnedIds(context));

        if (pinned) {
            // Existing pins keep their position; a newly pinned playlist is
            // appended after all playlists pinned earlier.
            updated.add(playlistId);
        } else {
            updated.remove(playlistId);
        }

        prefs(context).edit()
                .putStringSet(KEY_PINNED, new HashSet<>(updated))
                .putString(KEY_PIN_ORDER, serializeOrder(updated))
                .apply();

        cachedHasAnyPins = !updated.isEmpty();
    }

    public static void setPlaylistSignature(
            Context context,
            String playlistId,
            String signature
    ) {
        if (context == null
                || !isSupportedPlaylistId(playlistId)
                || signature == null
                || signature.isEmpty()) {
            return;
        }

        prefs(context).edit()
                .putString(
                        KEY_SIGNATURE_PREFIX + playlistId,
                        signature
                )
                .apply();
    }

    public static Map<String, String> getPlaylistSignatures(
            Context context
    ) {
        LinkedHashMap<String, String> result =
                new LinkedHashMap<>();

        if (context == null) return result;

        Map<String, ?> all = prefs(context).getAll();

        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key == null
                    || !key.startsWith(KEY_SIGNATURE_PREFIX)
                    || !(value instanceof String)) {
                continue;
            }

            String playlistId =
                    key.substring(KEY_SIGNATURE_PREFIX.length());
            String signature = (String) value;

            if (isSupportedPlaylistId(playlistId)
                    && !signature.isEmpty()) {
                result.put(playlistId, signature);
            }
        }

        return result;
    }

    /** Flips the pinned state for a playlist ID and returns the NEW state. */
    public static boolean togglePinned(
            Context context,
            String playlistId
    ) {
        if (!isSupportedPlaylistId(playlistId)) {
            Log.d(TAG, "togglePinned() ignored unsupported playlist ID");
            return false;
        }

        boolean newState =
                !isPinned(context, playlistId);
        setPinned(
                context,
                playlistId,
                newState
        );
        return newState;
    }

    private static String serializeOrder(Set<String> ordered) {
        StringBuilder builder = new StringBuilder();

        for (String value : ordered) {
            if (value == null || value.isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(value);
        }

        return builder.toString();
    }
}
