package app.morphe.patches.youtube.misc.playertype

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.getPlayerTypeFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/PlayerTypeHookPatch;"

val playerTypeHookPatch = bytecodePatch(
    description = "Hook to get the current player type and video playback state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val playerTypeMethod = getPlayerTypeFingerprint().method
        val playerTypeAlreadyHooked = playerTypeMethod.implementation?.instructions?.any { inst ->
            inst.opcode == Opcode.INVOKE_STATIC &&
                (inst as? ReferenceInstruction)?.reference?.let { ref ->
                    (ref as? MethodReference)?.let { mRef ->
                        mRef.definingClass == EXTENSION_CLASS && mRef.name == "setPlayerType"
                    }
                } == true
        } == true

        if (!playerTypeAlreadyHooked) {
            playerTypeMethod.addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_CLASS->setPlayerType(Ljava/lang/Enum;)V",
            )
        }


        val controlStateType = ControlsStateToStringFingerprint.originalClassDef.type
        val videoStateType = VideoStateEnumFingerprint.originalClassDef.type

        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(element = controlStateType),
            filters = listOf(
                // Obfuscated parameter field name.
                fieldAccess(
                    definingClass = controlStateType,
                    type = videoStateType
                ),
                resourceLiteral(ResourceType.STRING, "accessibility_play"),
                resourceLiteral(ResourceType.STRING, "accessibility_pause")
            )
        ).let {
            it.method.apply {
                val alreadyHooked = implementation?.instructions?.any { inst ->
                    inst.opcode == Opcode.INVOKE_STATIC &&
                        (inst as? ReferenceInstruction)?.reference?.let { ref ->
                            (ref as? MethodReference)?.let { mRef ->
                                mRef.definingClass == EXTENSION_CLASS && mRef.name == "setVideoState"
                            }
                        } == true
                } == true

                if (!alreadyHooked) {
                    val videoStateFieldName = getInstruction<ReferenceInstruction>(
                        it.instructionMatches.first().index
                    ).reference

                    addInstructions(
                        0,
                        """
                            iget-object v0, p1, $videoStateFieldName  # copy VideoState parameter field
                            invoke-static {v0}, $EXTENSION_CLASS->setVideoState(Ljava/lang/Enum;)V
                        """
                    )
                }
            }
        }
    }
}
