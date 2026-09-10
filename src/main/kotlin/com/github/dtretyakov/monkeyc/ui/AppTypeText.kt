package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.AppType

/**
 * The name of a project's kind, as it should appear in a sentence.
 *
 * A manifest spells the kind as an identifier — `watch-app`, `audio-content-provider-app` — and
 * those identifiers had been reaching the user unchanged, in sentences that then read "runs a
 * watch-app". The SDK ships a display name for each in `projectInfo.xml`, which is what the type
 * combo above the message is already showing, so the message can say the same thing.
 *
 * The id is the fallback rather than an error: an SDK too old to carry `projectInfo.xml` should
 * lose the polish and keep the sentence.
 */
internal fun appTypeLabel(manifestAppType: String?, known: List<AppType>): String? =
    manifestAppType?.let { id -> known.firstOrNull { it.id == id }?.name ?: id }

/**
 * "a" or "an", for a name that is not known until run time.
 *
 * Only ever applied to an app type's display name, and the SDK ships one — "Audio Content Provider
 * App" — that begins with a vowel, so the article cannot be written into the sentence.
 */
internal fun article(word: String): String =
    if (word.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
