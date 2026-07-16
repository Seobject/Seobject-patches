package app.seobject.patches.music.settings

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val PLAYER_SCREEN_KEY = "morphe_settings_music_screen_3_player"
private const val LIBRARY_SCREEN_KEY = "morphe_music_pin_playlists_library"
private const val LIBRARY_SCREEN_TITLE =
    "morphe_music_pin_playlists_library_title"

internal val pinPlaylistSettingsResourcePatch = resourcePatch(
    description = "Adds Pin Playlists options to Morphe Player > Library settings."
) {
    finalize {
        val stringsPath = "res/values/strings.xml"
        val stringsFile = get(stringsPath)

        if (stringsFile.exists()) {
            document(stringsPath).use { stringsDocument ->
                val resources = stringsDocument.documentElement
                val stringNodes = stringsDocument.getElementsByTagName("string")
                val libraryTitleExists = (0 until stringNodes.length)
                    .map { stringNodes.item(it) as Element }
                    .any {
                        it.getAttribute("name") == LIBRARY_SCREEN_TITLE
                    }

                if (!libraryTitleExists) {
                    val element = stringsDocument.createElement("string")
                    element.setAttribute("name", LIBRARY_SCREEN_TITLE)
                    element.textContent = "Library"
                    resources.appendChild(element)
                }
            }
        }

        listOf(
            "res/xml/morphe_prefs.xml",
            "res/xml/morphe_prefs_icons.xml",
            "res/xml/morphe_prefs_icons_bold.xml",
        ).forEach { preferencePath ->
            val preferenceFile = get(preferencePath)
            if (!preferenceFile.exists()) return@forEach

            document(preferencePath).use { preferenceDocument ->
                fun Element.androidAttribute(name: String): String =
                    getAttribute("android:$name").ifEmpty {
                        getAttributeNS(ANDROID_NS, name)
                    }

                fun findPreference(key: String): Element? {
                    val nodes = preferenceDocument.getElementsByTagName("*")
                    return (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .firstOrNull {
                            it.androidAttribute("key") == key
                        }
                }

                val playerScreens =
                    preferenceDocument.getElementsByTagName("PreferenceScreen")
                val playerScreen = (0 until playerScreens.length)
                    .mapNotNull { playerScreens.item(it) as? Element }
                    .firstOrNull {
                        val key = it.androidAttribute("key")
                        key == PLAYER_SCREEN_KEY ||
                            key.startsWith("${PLAYER_SCREEN_KEY}_sort_by_")
                    }
                    ?: error(
                        "Could not find Morphe Player preference screen in " +
                            preferencePath
                    )

                val libraryScreen =
                    findPreference(LIBRARY_SCREEN_KEY)
                        ?: preferenceDocument
                            .createElement("PreferenceScreen")
                            .apply {
                                setAttributeNS(
                                    ANDROID_NS,
                                    "android:key",
                                    LIBRARY_SCREEN_KEY
                                )
                                setAttributeNS(
                                    ANDROID_NS,
                                    "android:title",
                                    "@string/$LIBRARY_SCREEN_TITLE"
                                )
                                playerScreen.appendChild(this)
                            }

                fun getOrCreateSwitch(
                    key: String,
                    title: String,
                    summary: String,
                ): Element {
                    val preference = findPreference(key)
                        ?: preferenceDocument.createElement("SwitchPreference")

                    preference.setAttributeNS(
                        ANDROID_NS,
                        "android:key",
                        key
                    )
                    preference.setAttributeNS(
                        ANDROID_NS,
                        "android:title",
                        "@string/$title"
                    )
                    preference.setAttributeNS(
                        ANDROID_NS,
                        "android:summary",
                        "@string/$summary"
                    )
                    preference.setAttributeNS(
                        ANDROID_NS,
                        "android:defaultValue",
                        "true"
                    )

                    return preference
                }

                val enabledSwitch = getOrCreateSwitch(
                    "morphe_music_replace_pin_to_speed_dial",
                    "morphe_music_replace_pin_to_speed_dial_title",
                    "morphe_music_replace_pin_to_speed_dial_summary"
                )
                val separateMenuSwitch = getOrCreateSwitch(
                    "morphe_music_pin_playlist_separate_menu_item",
                    "morphe_music_pin_playlist_separate_menu_item_title",
                    "morphe_music_pin_playlist_separate_menu_item_summary"
                )

                // This screen has no sorting suffix, so XML order is preserved.
                // Keep the master feature switch above the optional menu-row switch.
                libraryScreen.appendChild(enabledSwitch)
                libraryScreen.appendChild(separateMenuSwitch)
            }
        }
    }
}
