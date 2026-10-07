package app.morphe.patches.youtube.layout.hide.members

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE

private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/MembersVideoFilter;"

@Suppress("unused")
val hideMembersVideosPatch = bytecodePatch(
    name = "Hide members videos",
    description = "Hides members-only and members-first videos from home feed, subscriptions, search, and channel feeds."
) {
    dependsOn(
        sharedExtensionPatch,
        lithoFilterPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_hide_members_videos", summary = true),
        )

        addLithoFilter(EXTENSION_FILTER)

        // Ensure MembersVideoFilter initializes on MainActivity onCreate
        try {
            val mainActivity = mutableClassDefBy(YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE)
            val onCreateMethod = mainActivity.methods.firstOrNull { it.name == "onCreate" }
            onCreateMethod?.addInstruction(
                0,
                "invoke-static {}, $EXTENSION_FILTER->initialize()V"
            )
        } catch (_: Exception) {
        }
    }
}
