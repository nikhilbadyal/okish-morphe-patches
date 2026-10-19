package app.swiftbackup.patches.blacklist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.swiftbackup.patches.shared.Constants.COMPATIBILITY_SWIFTBACKUP
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Expands Swift Backup's hardcoded 100-app Blacklist limit to 1,000 apps.
 *
 * In the original app, a 100-item ceiling was enforced across 6 separate locations:
 * 1. Storage sanitizer (`es0.a`): truncated any loaded/saved list to 100 with `Iterable.take(100)`.
 * 2. Context-menu action (`pj.invokeSuspend`): rejected adding an app if `size >= 100`.
 * 3. FAB action (`nf.onClick`): blocked opening the multi-picker if `size >= 100` and showed `" (100/100)"`.
 * 4. Multi-choice listener (`sr0.onClick`): checked `selected >= 100`, disabled the OK button if `> 100`,
 *    and formatted the dialog title counter via `Const.p(..., 100, true)`.
 * 5. Dialog initialization (`ox.invoke`): formatted the initial dialog title via `Const.p(..., 100, true)`.
 * 6. Action Bar subtitle (`hj.invoke`): formatted the activity subtitle via `Const.p(..., 100, false)`.
 *
 * Technical Rationale:
 * - In Dalvik bytecode, integer constants in the range -32768..32767 are encoded via `const/16 vAA, #+BBBB`.
 *   Since 1,000 (0x03E8) is a 16-bit signed integer just like 100 (0x0064), the instruction format is identical
 *   (Format 21s, 2 16-bit code units).
 * - Replacing `const/16` in-place preserves method bytecode length, branch offsets, and register layouts,
 *   avoiding any dex verification errors or stack alignment problems.
 * - The destination register for each modified instruction is dynamically read via [OneRegisterInstruction.registerA],
 *   ensuring resilience against compiler register renumbering between R8 compilation runs.
 */
@Suppress("unused")
val swiftBackupBlacklistLimitPatch = bytecodePatch(
    name = "Swift Backup Blacklist Limit",
    description = "Expands the maximum number of blacklisted apps from 100 to 1,000.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SWIFTBACKUP)

    execute {
        // Target limit to expand to. 1,000 apps provides plenty of headroom while remaining well within
        // Dalvik's 16-bit integer immediate range.
        val targetLimit = 1000

        /**
         * Replaces a matched `const/16 <reg>, 100` instruction with `const/16 <reg>, targetLimit`.
         * Resolves the target register dynamically to ensure compatibility regardless of R8 register naming.
         */
        fun patchLimitConstant(fingerprint: Fingerprint, matchIndex: Int, locationTag: String) {
            val matches = fingerprint.instructionMatches
            if (matches.size <= matchIndex) {
                throw PatchException(
                    "Blacklist limit patch ($locationTag): expected match at index $matchIndex, " +
                        "found only ${matches.size} matches."
                )
            }
            val match = matches[matchIndex]
            val register = match.getInstruction<OneRegisterInstruction>().registerA
            fingerprint.method.replaceInstruction(match.index, "const/16 v$register, $targetLimit")
        }

        // 1. Storage sanitizer & deserialization gate (es0.a)
        // Replaces the `const/16 v1, 0x64` passed into Kotlin's `Iterable.take(n)`.
        patchLimitConstant(BlacklistDataSanitizeFingerprint, 0, "storage-sanitizer")
        println("SwiftBackup: patched BlacklistData sanitizer (Iterable.take limit -> $targetLimit)")

        // 2. Context-menu / quick-action "Add to blacklist" check (pj.invokeSuspend)
        // Replaces the `const/16 v7, 0x64` used in `if-lt size, 100`.
        patchLimitConstant(BlacklistMenuAddCheckFingerprint, 0, "context-menu-gate")
        println("SwiftBackup: patched context menu add check (size limit -> $targetLimit)")

        // 3. Floating Action Button (+) click check & toast string (nf.onClick)
        // - Match 0: const/16 p1, 0x64 (FAB gate check)
        // - Match 2: const-string p1, " (100/100)" (counter display suffix in limit toast)
        patchLimitConstant(BlacklistFabAddCheckFingerprint, 0, "fab-click-gate")
        val fabMatches = BlacklistFabAddCheckFingerprint.instructionMatches
        if (fabMatches.size <= 2) {
            // Fail fast: if the suffix string match is missing, throw rather than leaving the UI displaying "(100/100)"
            throw PatchException(
                "Blacklist limit patch (fab-counter-suffix): expected at least 3 matches, found only ${fabMatches.size}"
            )
        }
        val strMatch = fabMatches[2]
        val strReg = strMatch.getInstruction<OneRegisterInstruction>().registerA
        BlacklistFabAddCheckFingerprint.method.replaceInstruction(
            strMatch.index,
            "const-string v$strReg, \" ($targetLimit/$targetLimit)\""
        )
        println("SwiftBackup: patched FAB add check and limit toast suffix -> ($targetLimit/$targetLimit)")

        // 4. Multi-choice dialog click listener (sr0.onClick)
        // Match 0: const/16 p3, 0x64 (shared register controlling toast trigger, OK button enabled, and title format)
        patchLimitConstant(BlacklistMultiSelectDialogFingerprint, 0, "multiselect-click-listener")
        println("SwiftBackup: patched multi-choice click listener (limit & OK button gate -> $targetLimit)")

        // 5. Multi-choice dialog initial title formatter (ox.invoke)
        // Match 1: const/16 v6, 0x64 (max argument to Const.p)
        patchLimitConstant(BlacklistDialogInitFingerprint, 1, "dialog-init-title")
        println("SwiftBackup: patched multi-choice dialog initial title (max limit -> $targetLimit)")

        // 6. Action Bar subtitle updater (hj.invoke)
        // Match 1: const/16 v3, 0x64 (max argument to Const.p)
        patchLimitConstant(BlacklistSubtitleFingerprint, 1, "action-bar-subtitle")
        println("SwiftBackup: patched Action Bar subtitle formatter (max limit -> $targetLimit)")
    }
}
