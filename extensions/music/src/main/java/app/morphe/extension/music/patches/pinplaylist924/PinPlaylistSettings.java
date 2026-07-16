package app.morphe.extension.music.patches.pinplaylist924;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone settings storage and UI materialization for Pin playlists.
 *
 * Values are stored in Android's default SharedPreferences, which is the same
 * store used by platform SwitchPreference. No Morphe Setting classes are used.
 */
@SuppressWarnings("deprecation")
public final class PinPlaylistSettings {
    public static final String KEY_ENABLED =
            "morphe_music_replace_pin_to_speed_dial";
    public static final String KEY_SEPARATE_MENU_ITEM =
            "morphe_music_pin_playlist_separate_menu_item";

    private static final String PLAYER_SCREEN_KEY =
            "morphe_settings_music_screen_3_player";
    private static final String PLAYER_SCREEN_SORTED_KEY =
            PLAYER_SCREEN_KEY + "_sort_by_title";
    private static final String LIBRARY_SCREEN_KEY =
            "morphe_music_pin_playlists_library";

    private static final String PLAYER_TITLE = "Player";
    private static final String LIBRARY_TITLE = "Library";
    private static final String ENABLED_TITLE = "Enable Pin playlists";
    private static final String ENABLED_SUMMARY =
            "Enable persistent Library playlist pinning and visible pin indicators";
    private static final String SEPARATE_MENU_TITLE =
            "Add Pin to Library button";
    private static final String SEPARATE_MENU_SUMMARY =
            "Show a separate Library pin action in playlist menus";

    private static final Object LOCK = new Object();

    private static volatile SharedPreferences preferences;
    private static volatile boolean enabled = true;
    private static volatile boolean separateMenuItemEnabled = true;

    private static final SharedPreferences.OnSharedPreferenceChangeListener
            PREFERENCE_LISTENER = (sharedPreferences, key) -> {
        if (KEY_ENABLED.equals(key)) {
            enabled = sharedPreferences.getBoolean(KEY_ENABLED, true);
        } else if (KEY_SEPARATE_MENU_ITEM.equals(key)) {
            separateMenuItemEnabled = sharedPreferences.getBoolean(
                    KEY_SEPARATE_MENU_ITEM,
                    true
            );
        }
    };

    public static boolean isEnabled(Context context) {
        initialize(context);
        return enabled;
    }

    public static boolean isSeparateMenuItemEnabled(Context context) {
        initialize(context);
        return separateMenuItemEnabled;
    }

    /**
     * Adds Morphe > Player > Library when a Morphe preference fragment is
     * available. Existing screens and switches are reused and deduplicated.
     */
    public static void installPreferencePath(Object fragmentObject) {
        if (!(fragmentObject instanceof PreferenceFragment)) return;

        PreferenceFragment fragment = (PreferenceFragment) fragmentObject;
        Context context = fragment.getActivity();
        PreferenceScreen root = fragment.getPreferenceScreen();

        if (context == null || root == null) return;

        initialize(context);

        PreferenceScreen player = ensurePlayerScreen(fragment, root, context);
        PreferenceScreen library = ensureLibraryScreen(fragment, player, context);

        SwitchPreference enabledPreference = ensureSwitch(
                root,
                library,
                context,
                KEY_ENABLED,
                ENABLED_TITLE,
                ENABLED_SUMMARY
        );
        SwitchPreference separateMenuPreference = ensureSwitch(
                root,
                library,
                context,
                KEY_SEPARATE_MENU_ITEM,
                SEPARATE_MENU_TITLE,
                SEPARATE_MENU_SUMMARY
        );

        separateMenuPreference.setDependency(KEY_ENABLED);

        // Library intentionally has no sorting suffix. Re-add both switches in
        // the required order after all existing duplicates have been removed.
        library.removePreference(enabledPreference);
        library.removePreference(separateMenuPreference);
        library.addPreference(enabledPreference);
        library.addPreference(separateMenuPreference);
    }

    private static void initialize(Context context) {
        if (preferences != null || context == null) return;

        Context appContext = context.getApplicationContext();
        Context safeContext = appContext != null ? appContext : context;

        synchronized (LOCK) {
            if (preferences != null) return;

            SharedPreferences loaded =
                    PreferenceManager.getDefaultSharedPreferences(safeContext);
            enabled = loaded.getBoolean(KEY_ENABLED, true);
            separateMenuItemEnabled = loaded.getBoolean(
                    KEY_SEPARATE_MENU_ITEM,
                    true
            );
            loaded.registerOnSharedPreferenceChangeListener(
                    PREFERENCE_LISTENER
            );
            preferences = loaded;
        }
    }

    private static PreferenceScreen ensurePlayerScreen(
            PreferenceFragment fragment,
            PreferenceScreen root,
            Context context
    ) {
        List<PreferenceLocation> matches = new ArrayList<>();
        collectPlayerScreens(root, matches);

        PreferenceScreen player = chooseLargestScreen(matches);
        if (player == null) {
            player = fragment.getPreferenceManager()
                    .createPreferenceScreen(context);
            player.setKey(PLAYER_SCREEN_SORTED_KEY);
            root.addPreference(player);
        } else {
            moveToParent(matches, player, root);
            mergeDuplicateScreens(matches, player);
        }

        if (player.getKey() == null
                || !player.getKey().startsWith(PLAYER_SCREEN_KEY)) {
            player.setKey(PLAYER_SCREEN_SORTED_KEY);
        }
        player.setTitle(PLAYER_TITLE);
        return player;
    }

    private static PreferenceScreen ensureLibraryScreen(
            PreferenceFragment fragment,
            PreferenceScreen player,
            Context context
    ) {
        List<PreferenceLocation> matches = new ArrayList<>();
        collectExactScreens(player, LIBRARY_SCREEN_KEY, matches);

        PreferenceScreen library = chooseLargestScreen(matches);
        if (library == null) {
            library = fragment.getPreferenceManager()
                    .createPreferenceScreen(context);
            library.setKey(LIBRARY_SCREEN_KEY);
            player.addPreference(library);
        } else {
            moveToParent(matches, library, player);
            mergeDuplicateScreens(matches, library);
        }

        library.setKey(LIBRARY_SCREEN_KEY);
        library.setTitle(LIBRARY_TITLE);
        return library;
    }

    private static SwitchPreference ensureSwitch(
            PreferenceScreen root,
            PreferenceScreen library,
            Context context,
            String key,
            String title,
            String summary
    ) {
        List<PreferenceLocation> matches = new ArrayList<>();
        collectExactPreferences(root, key, matches);

        SwitchPreference result = null;
        PreferenceLocation resultLocation = null;

        for (PreferenceLocation location : matches) {
            if (location.preference instanceof SwitchPreference) {
                result = (SwitchPreference) location.preference;
                resultLocation = location;
                break;
            }
        }

        boolean attachedToLibrary =
                resultLocation != null && resultLocation.parent == library;

        if (result == null) {
            result = new SwitchPreference(context);
            result.setKey(key);
        } else if (!attachedToLibrary) {
            resultLocation.parent.removePreference(result);
            library.addPreference(result);
            attachedToLibrary = true;
        }

        for (PreferenceLocation location : matches) {
            if (location.preference != result) {
                location.parent.removePreference(location.preference);
            }
        }

        result.setKey(key);
        result.setTitle(title);
        result.setSummary(summary);
        result.setPersistent(true);
        result.setDefaultValue(Boolean.TRUE);

        if (!attachedToLibrary) {
            library.addPreference(result);
        }

        return result;
    }

    private static void collectPlayerScreens(
            PreferenceGroup root,
            List<PreferenceLocation> result
    ) {
        for (int index = 0; index < root.getPreferenceCount(); index++) {
            Preference preference = root.getPreference(index);
            String key = preference.getKey();

            if (preference instanceof PreferenceScreen
                    && key != null
                    && (PLAYER_SCREEN_KEY.equals(key)
                    || key.startsWith(PLAYER_SCREEN_KEY + "_sort_by_"))) {
                result.add(new PreferenceLocation(root, preference));
            }
        }
    }

    private static void collectExactScreens(
            PreferenceGroup parent,
            String key,
            List<PreferenceLocation> result
    ) {
        for (int index = 0; index < parent.getPreferenceCount(); index++) {
            Preference preference = parent.getPreference(index);

            if (preference instanceof PreferenceScreen
                    && key.equals(preference.getKey())) {
                result.add(new PreferenceLocation(parent, preference));
            }
        }
    }

    private static void collectExactPreferences(
            PreferenceGroup group,
            String key,
            List<PreferenceLocation> result
    ) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);

            if (key.equals(preference.getKey())) {
                result.add(new PreferenceLocation(group, preference));
            }

            if (preference instanceof PreferenceGroup) {
                collectExactPreferences(
                        (PreferenceGroup) preference,
                        key,
                        result
                );
            }
        }
    }

    private static PreferenceScreen chooseLargestScreen(
            List<PreferenceLocation> locations
    ) {
        PreferenceScreen result = null;

        for (PreferenceLocation location : locations) {
            PreferenceScreen candidate =
                    (PreferenceScreen) location.preference;

            if (result == null
                    || candidate.getPreferenceCount()
                    > result.getPreferenceCount()) {
                result = candidate;
            }
        }

        return result;
    }

    private static void moveToParent(
            List<PreferenceLocation> locations,
            PreferenceScreen screen,
            PreferenceGroup targetParent
    ) {
        for (PreferenceLocation location : locations) {
            if (location.preference == screen) {
                if (location.parent != targetParent) {
                    location.parent.removePreference(screen);
                    targetParent.addPreference(screen);
                }
                return;
            }
        }
    }

    private static void mergeDuplicateScreens(
            List<PreferenceLocation> locations,
            PreferenceScreen target
    ) {
        for (PreferenceLocation location : locations) {
            if (location.preference == target) continue;

            PreferenceScreen duplicate =
                    (PreferenceScreen) location.preference;
            mergePreferenceGroups(duplicate, target);
            location.parent.removePreference(duplicate);
        }
    }

    private static void mergePreferenceGroups(
            PreferenceGroup source,
            PreferenceGroup target
    ) {
        List<Preference> children = new ArrayList<>();
        for (int index = 0; index < source.getPreferenceCount(); index++) {
            children.add(source.getPreference(index));
        }

        for (Preference child : children) {
            source.removePreference(child);
            String key = child.getKey();
            Preference existing = key == null
                    ? null
                    : findExactPreference(target, key);

            if (existing instanceof PreferenceGroup
                    && child instanceof PreferenceGroup) {
                mergePreferenceGroups(
                        (PreferenceGroup) child,
                        (PreferenceGroup) existing
                );
            } else if (existing == null) {
                target.addPreference(child);
            }
        }
    }

    private static Preference findExactPreference(
            PreferenceGroup group,
            String key
    ) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference preference = group.getPreference(index);
            if (key.equals(preference.getKey())) return preference;

            if (preference instanceof PreferenceGroup) {
                Preference nested = findExactPreference(
                        (PreferenceGroup) preference,
                        key
                );
                if (nested != null) return nested;
            }
        }

        return null;
    }

    private static final class PreferenceLocation {
        final PreferenceGroup parent;
        final Preference preference;

        PreferenceLocation(
                PreferenceGroup parent,
                Preference preference
        ) {
            this.parent = parent;
            this.preference = preference;
        }
    }

    private PinPlaylistSettings() {
    }
}
