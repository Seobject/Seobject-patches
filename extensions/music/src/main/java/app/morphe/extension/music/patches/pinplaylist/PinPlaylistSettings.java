package app.morphe.extension.music.patches.pinplaylist924;

import static java.lang.Boolean.TRUE;

import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Runtime settings owned by the standalone Pin playlists extension.
 */
public final class PinPlaylistSettings {
    public static final BooleanSetting ENABLED =
            new BooleanSetting(
                    "morphe_music_replace_pin_to_speed_dial",
                    TRUE,
                    true
            );

    public static final BooleanSetting SEPARATE_MENU_ITEM =
            new BooleanSetting(
                    "morphe_music_pin_playlist_separate_menu_item",
                    TRUE,
                    true
            );

    private PinPlaylistSettings() {
    }
}
