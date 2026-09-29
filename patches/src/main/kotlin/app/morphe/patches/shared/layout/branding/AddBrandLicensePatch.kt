package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.util.inputStreamFromBundledResource
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies the license and branding notice files to the target apk.
 */
internal val addLicensePatch = rawResourcePatch {
    execute {
        arrayOf(
            "MORPHE_BRANDING.TXT",
            "MORPHE_LICENSE.TXT",
            "MORPHE_LICENSE_NOTICE.TXT"
        ).forEach { sourceFileName ->
            val inputFileStream = inputStreamFromBundledResource(
                "license",
                sourceFileName
            )!!

            val targetFile = get(sourceFileName, false).toPath()

            // If the target file already exists (e.g. added by another patch source or previous step in Morphe Manager),
            // safely overwrite it instead of failing with PatchException.
            if (Files.exists(targetFile)) {
                Files.delete(targetFile)
            }

            Files.copy(inputFileStream, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
