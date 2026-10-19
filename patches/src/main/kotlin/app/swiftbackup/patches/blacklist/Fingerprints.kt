package app.swiftbackup.patches.blacklist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * Swift Backup hardcodes a limit of 100 blacklisted applications across several
 * independent validation gates and UI widgets. The original reason was Firebase Realtime
 * Database node size constraints and O(N) linear iteration overhead during app list renders.
 * Because patched installations operate with local offline metadata storage, this limit can
 * safely be raised to 1,000 apps without network payload issues.
 */

/**
 * Matches the static sanitizer/truncation function in `es0.smali`.
 *
 * Whenever BlacklistData is loaded from disk or prepared for saving, this method enforces
 * an upper boundary by taking at most 100 items via Kotlin's `Iterable.take(100)` (`el1.p1`).
 * Without patching this gate, any blacklist containing more than 100 apps would be silently
 * truncated back to 100 upon deserialization or save.
 *
 * Filter rationale:
 * - Return type and parameter type are both the un-obfuscated `BlacklistData` model.
 * - Pinned by `literal(100)` (the `const/16 v1, 0x64` fed into `take()`) and the subsequent call
 *   to `BlacklistApp.setInstalled`, guaranteeing a unique match across the APK without hardcoding
 *   the obfuscated utility class name `Les0;`.
 */
object BlacklistDataSanitizeFingerprint : Fingerprint(
    returnType = "Lorg/swiftapps/swiftbackup/blacklist/data/BlacklistData;",
    parameters = listOf("Lorg/swiftapps/swiftbackup/blacklist/data/BlacklistData;"),
    filters = listOf(
        // Match 0: const/16 v1, 0x64 (argument to Iterable.take(100))
        literal(100),
        // Match 1: verify it is the blacklist app inspection loop
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/blacklist/data/BlacklistApp;",
            name = "setInstalled"
        )
    )
)

/**
 * Matches the context-menu / quick-action "Add to blacklist" coroutine in `pj.smali`.
 *
 * When a user taps "Add to blacklist" on a single application outside the BlacklistActivity,
 * this coroutine checks `if (blacklist.size() >= 100)`. If reached, it redirects the user
 * to BlacklistActivity and flashes string resource `0x7f130341` (R.string.maximum_num_blacklist_limit_message:
 * "Maximum number of blacklisted apps reached").
 *
 * Filter rationale:
 * - Method is a Kotlin suspend lambda (`invokeSuspend(Object): Object`).
 * - Pinned by the comparison literal 100 followed by the blacklist limit string resource ID `0x7f130341`.
 *   This is the only coroutine in the entire application referencing this specific error string.
 */
object BlacklistMenuAddCheckFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Match 0: const/16 v7, 0x64 (compared against blacklist.size())
        literal(100),
        // Match 1: const p0, 0x7f130341 (R.string.maximum_num_blacklist_limit_message)
        literal(0x7f130341)
    )
)

/**
 * Matches the Floating Action Button (FAB) click listener in `nf.smali` (`BlacklistActivity`).
 *
 * Tapping the "+" button in BlacklistActivity checks `if (currentCount >= 100)`. If the limit
 * is already reached, it displays a toast combining `0x7f130341` with the suffix string `" (100/100)"`
 * instead of opening the multi-select app picker dialog.
 *
 * Filter rationale:
 * - Implements `android.view.View.OnClickListener.onClick(View): void`.
 * - Pinned by `literal(100)`, `literal(0x7f130341)`, and the explicit string `" (100/100)"`.
 *   Both the constant 100 and the suffix string need to be updated to 1000.
 */
object BlacklistFabAddCheckFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        // Match 0: const/16 p1, 0x64 (compared against list size)
        literal(100),
        // Match 1: const p1, 0x7f130341 (R.string.maximum_num_blacklist_limit_message)
        literal(0x7f130341),
        // Match 2: const-string p1, " (100/100)" (toast counter display)
        string(" (100/100)")
    )
)

/**
 * Matches the multi-choice dialog checkbox listener in `sr0.smali`.
 *
 * Inside the multi-choice app selection dialog:
 * 1. Selecting an item checks `if (selectedCount >= 100)` and shows a toast with `0x7f130341`.
 * 2. It sets the OK dialog button state to `selectedCount <= 100` (`if-le v3, p3`).
 * 3. It updates the dialog title via `Const.p(context, selectedCount, 100, true)`.
 *
 * Filter rationale:
 * - Implements `android.content.DialogInterface.OnMultiChoiceClickListener.onClick(DialogInterface, int, boolean): void`.
 * - Pinned by `literal(100)`, string resource `0x7f130341`, and `Const.p`.
 *   Replacing the single `const/16 <reg>, 100` simultaneously updates the toast threshold, the button disable check,
 *   and the dialog title maximum.
 */
object BlacklistMultiSelectDialogFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/DialogInterface;", "I", "Z"),
    filters = listOf(
        // Match 0: const/16 p3, 0x64 (shared limit register for toast check, button toggle, and title)
        literal(100),
        // Match 1: const p2, 0x7f130341 (error toast message)
        literal(0x7f130341),
        // Match 2: invoke-static Const.p(Context, current, max, isSelection)
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/common/Const;",
            name = "p"
        )
    )
)

/**
 * Matches the multi-choice dialog initialization lambda in `ox.smali`.
 *
 * When the app selection dialog is first constructed in BlacklistActivity, this lambda
 * sets the initial dialog title using `Const.p(context, currentSelectedCount, 100, true)`.
 *
 * Filter rationale:
 * - Method is a synthetic Kotlin lambda (`invoke(Object): Object`).
 * - Pinned by the static field access `BlacklistActivity.P` (synthetic companion flag on BlacklistActivity),
 *   `literal(100)`, and the formatting call to `Const.p`.
 */
object BlacklistDialogInitFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Match 0: sget v2, Lorg/swiftapps/swiftbackup/blacklist/BlacklistActivity;->P:I
        fieldAccess(smali = "Lorg/swiftapps/swiftbackup/blacklist/BlacklistActivity;->P:I"),
        // Match 1: const/16 v6, 0x64 (max limit argument to Const.p)
        literal(100),
        // Match 2: invoke-static Const.p(Context, current, max, isSelection)
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/common/Const;",
            name = "p"
        )
    )
)

/**
 * Matches the Action Bar subtitle updater lambda in `hj.smali` (`BlacklistActivity`).
 *
 * Updates the activity's action bar subtitle to show the current blacklisted count and cap
 * (e.g. "Blacklisted apps (42 / 100)") using `Const.p(context, listSize, 100, false)`.
 *
 * Filter rationale:
 * - Method is a synthetic Kotlin lambda (`invoke(Object): Object`).
 * - Pinned by the view binding getter `BlacklistActivity.N()` (returns ActivityBlacklistBinding),
 *   `literal(100)`, and the formatting call to `Const.p`.
 */
object BlacklistSubtitleFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Match 0: Any virtual method call on BlacklistActivity (retrieves the view binding in this lambda).
        // Omitting the single-letter obfuscated name ("N") prevents breakage across R8 compilation runs.
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/blacklist/BlacklistActivity;"
        ),
        // Match 1: const/16 v3, 0x64 (max limit argument to Const.p)
        literal(100),
        // Match 2: invoke-static Const.p(Context, current, max, isSelection)
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/common/Const;",
            name = "p"
        )
    )
)
