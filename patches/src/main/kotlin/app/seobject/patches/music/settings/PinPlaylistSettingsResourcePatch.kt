package app.seobject.patches.music.settings

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
internal val pinPlaylistSettingsResourcePatch = resourcePatch(
    description = "Adds Pin Playlists options to the existing Morphe settings screen."
) {
    finalize {
        val stringsPath = "res/values/strings.xml"
        val stringsFile = get(stringsPath)

        if (stringsFile.exists()) {
            document(stringsPath).use { stringsDocument ->
                val resources = stringsDocument.documentElement

                fun addString(name: String, value: String) {
                    val existing = stringsDocument
                        .getElementsByTagName("string")
                        .let { nodes ->
                            (0 until nodes.length)
                                .map { nodes.item(it) as Element }
                                .any { it.getAttribute("name") == name }
                        }

                    if (existing) return

                    val element = stringsDocument.createElement("string")
                    element.setAttribute("name", name)
                    element.textContent = value
                    resources.appendChild(element)
                }

                addString(
                    "seobject_pin_playlists_settings_title",
                    "Pin playlists"
                )
                addString(
                    "seobject_pin_playlists_enabled_title",
                    "Enable Pin playlists"
                )
                addString(
                    "seobject_pin_playlists_enabled_summary",
                    "Pin playlists to the top of the Library"
                )
                addString(
                    "seobject_pin_playlists_separate_title",
                    "Add Pin to Library button"
                )
                addString(
                    "seobject_pin_playlists_separate_summary",
                    "Show a separate Library pin action in playlist menus"
                )
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
                val settingsScreen = preferenceDocument.documentElement

                val existingKeys = settingsScreen.childNodes.let { nodes ->
                    (0 until nodes.length)
                        .mapNotNull { nodes.item(it) as? Element }
                        .map {
                            it.getAttribute("android:key")
                                .ifEmpty {
                                    it.getAttributeNS(ANDROID_NS, "key")
                                }
                        }
                        .toSet()
                }

                fun addSwitch(
                    key: String,
                    title: String,
                    summary: String,
                ) {
                    if (key in existingKeys) return

                    val preference =
                        preferenceDocument.createElement("SwitchPreference")
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
                    settingsScreen.insertBefore(
                        preference,
                        settingsScreen.firstChild
                    )
                }

                addSwitch(
                    "morphe_music_pin_playlist_separate_menu_item",
                    "seobject_pin_playlists_separate_title",
                    "seobject_pin_playlists_separate_summary"
                )
                addSwitch(
                    "morphe_music_replace_pin_to_speed_dial",
                    "seobject_pin_playlists_enabled_title",
                    "seobject_pin_playlists_enabled_summary"
                )
            }
        }
    }
}
