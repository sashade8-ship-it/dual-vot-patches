/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.addon

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.hooks.YouTubeApplicationInitFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch

private const val EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/addon/AddOnApi;"

private const val EXTENSION_ADD_ON_MANAGER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/addon/AddOnManager;"

/**
 * Button slots an add-on can claim at runtime with
 * `AddOnApi.createLegacyButton()`. The slots are hidden until an add-on uses them.
 */
private const val LEGACY_BUTTON_SLOTS_RESOURCE_DIRECTORY = "addonbuttons"

private val addOnSupportResourcePatch = resourcePatch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        addLegacyBottomControl(LEGACY_BUTTON_SLOTS_RESOURCE_DIRECTORY)
    }
}

/**
 * Adds the hooks an add-on patch bundle attaches to.
 *
 * Add-on bundles are loaded in their own class loader and cannot reference any patch of this
 * bundle. The contract between the two is the patched app itself:
 */
@Suppress("unused")
val addOnSupportPatch = bytecodePatch(
    description = "Adds the hooks that third party add-on patch bundles attach to."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addOnSupportResourcePatch,
        sharedExtensionPatch,
        settingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        videoInformationPatch,
        videoIdPatch,
        // Not used by the hooks below, but add-ons commonly observe
        // the player type and the video state.
        playerTypeHookPatch
    )

    execute {
        // Load the add-ons as soon as the app context is available.
        // The shared extension patch hooks the context in its finalize block,
        // which inserts the context hook before this call.
        YouTubeApplicationInitFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_ADD_ON_MANAGER_CLASS_DESCRIPTOR->initialize()V"
        )

        addPlayerBottomButton(EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR)
        initializeLegacyBottomControl(EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR)

        onCreateHook(EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR, "newVideoStarted")
        videoTimeHook(EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR, "videoTimeChanged")
        hookVideoId("$EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR->videoIdChanged(Ljava/lang/String;)V")
    }
}
