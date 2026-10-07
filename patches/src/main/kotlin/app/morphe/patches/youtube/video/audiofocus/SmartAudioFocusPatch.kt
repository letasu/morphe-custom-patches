package app.morphe.patches.youtube.video.audiofocus

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/audiofocus/SmartAudioFocusManager;"

@Suppress("unused")
val smartAudioFocusPatch = bytecodePatch(
    name = "Smart audio focus",
    description = "Pauses external music while playing YouTube videos and automatically resumes when leaving the video player."
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference(key = "morphe_smart_audio_focus", summary = true),
        )

        // Route all AudioManager focus requests through SmartAudioFocusManager
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach

            var needsPatch = false
            classDef.methods.forEach { method ->
                if (method.implementation?.instructions?.any {
                        it.opcode == Opcode.INVOKE_VIRTUAL &&
                                (it as? ReferenceInstruction)?.reference?.let { ref ->
                                    val mRef = ref as? MethodReference
                                    mRef?.definingClass == "Landroid/media/AudioManager;" &&
                                            (mRef.name == "requestAudioFocus" || mRef.name == "abandonAudioFocus" || mRef.name == "abandonAudioFocusRequest")
                                } == true
                    } == true) {
                    needsPatch = true
                }
            }

            if (needsPatch) {
                val mutableClass = mutableClassDefBy(classDef.type)
                classDef.methods.forEach { method ->
                    val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                    val targetIndices = instructions.mapIndexedNotNull { index, instruction ->
                        if (instruction.opcode == Opcode.INVOKE_VIRTUAL) {
                            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            if (ref?.definingClass == "Landroid/media/AudioManager;") {
                                when (ref.name) {
                                    "requestAudioFocus" -> {
                                        if (ref.parameterTypes == listOf("Landroid/media/AudioFocusRequest;")) {
                                            index to "requestAudioFocus(Landroid/media/AudioManager;Landroid/media/AudioFocusRequest;)I"
                                        } else if (ref.parameterTypes == listOf("Landroid/media/AudioManager\$OnAudioFocusChangeListener;", "I", "I")) {
                                            index to "requestAudioFocus(Landroid/media/AudioManager;Landroid/media/AudioManager\$OnAudioFocusChangeListener;II)I"
                                        } else null
                                    }
                                    "abandonAudioFocusRequest" -> {
                                        if (ref.parameterTypes == listOf("Landroid/media/AudioFocusRequest;")) {
                                            index to "abandonAudioFocusRequest(Landroid/media/AudioManager;Landroid/media/AudioFocusRequest;)I"
                                        } else null
                                    }
                                    "abandonAudioFocus" -> {
                                        if (ref.parameterTypes == listOf("Landroid/media/AudioManager\$OnAudioFocusChangeListener;")) {
                                            index to "abandonAudioFocus(Landroid/media/AudioManager;Landroid/media/AudioManager\$OnAudioFocusChangeListener;)I"
                                        } else null
                                    }
                                    else -> null
                                }
                            } else null
                        } else null
                    }

                    if (targetIndices.isNotEmpty()) {
                        val mutableMethod = mutableClass.findMutableMethodOf(method)
                        targetIndices.reversed().forEach { (index, methodDescriptor) ->
                            val instruction = instructions[index]
                            val invokeString = if (instruction is RegisterRangeInstruction) {
                                "invoke-static/range {v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1}}"
                            } else {
                                val i = instruction as FiveRegisterInstruction
                                when (i.registerCount) {
                                    1 -> "invoke-static {v${i.registerC}}"
                                    2 -> "invoke-static {v${i.registerC}, v${i.registerD}}"
                                    3 -> "invoke-static {v${i.registerC}, v${i.registerD}, v${i.registerE}}"
                                    4 -> "invoke-static {v${i.registerC}, v${i.registerD}, v${i.registerE}, v${i.registerF}}"
                                    5 -> "invoke-static {v${i.registerC}, v${i.registerD}, v${i.registerE}, v${i.registerF}, v${i.registerG}}"
                                    else -> throw IllegalStateException("Unexpected register count: ${i.registerCount}")
                                }
                            }

                            mutableMethod.replaceInstruction(
                                index,
                                "$invokeString, $EXTENSION_CLASS->$methodDescriptor"
                            )
                        }
                    }
                }
            }
        }

        // Hook MainActivity onCreate to initialize listeners early, and onStop to release audio focus
        try {
            val mainActivity = mutableClassDefBy(YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE)
            val onCreateMethod = mainActivity.methods.firstOrNull { it.name == "onCreate" }
            onCreateMethod?.let { method ->
                val alreadyHooked = method.implementation?.instructions?.any {
                    (it as? ReferenceInstruction)?.reference?.let { ref ->
                        (ref as? MethodReference)?.definingClass == EXTENSION_CLASS && (ref as? MethodReference)?.name == "initialize"
                    } == true
                } ?: false
                if (!alreadyHooked) {
                    method.addInstruction(
                        0,
                        "invoke-static {}, $EXTENSION_CLASS->initialize()V"
                    )
                }
            }

            val onStopMethod = mainActivity.methods.firstOrNull { it.name == "onStop" && it.parameterTypes.isEmpty() }
            onStopMethod?.let { method ->
                val alreadyHooked = method.implementation?.instructions?.any {
                    (it as? ReferenceInstruction)?.reference?.let { ref ->
                        (ref as? MethodReference)?.definingClass == EXTENSION_CLASS && (ref as? MethodReference)?.name == "onActivityStopped"
                    } == true
                } ?: false
                if (!alreadyHooked) {
                    method.addInstruction(
                        0,
                        "invoke-static {}, $EXTENSION_CLASS->onActivityStopped()V"
                    )
                }
            }
        } catch (_: Exception) {
            // MainActivity hooks optional
        }
    }
}
