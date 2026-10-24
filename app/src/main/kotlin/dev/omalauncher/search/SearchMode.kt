package dev.omalauncher.search

/**
 * What Soma's search looks through (Boss, 2026-10-24: "Apple Store" brought a flood of files and photos).
 * Quick is what every newly opened search starts on: apps, Soma, settings, people, the calculator, the clipboard and
 * the web; no files and no photos. Files and Photos are asked for on purpose, from the row under the field, and the
 * typed words stay as they are when he changes it. A search opened again starts on Quick again.
 */
enum class SearchMode(val label: String) {
    Quick("Quick"),
    Files("Files"),
    Notes("Notes"),
    Photos("Photos"),
    ;

    /** The phone's file index and the Mac Pro's Spotlight are searched: names first, what is in them below. */
    val files get() = this == Files

    /** Soma Notes, every word in a note's title or text: his notes, his agents', projects' Brief and Current state. */
    val notes get() = this == Notes

    /** Photos and videos, by their words and by what is in them. */
    val photos get() = this == Photos

    /** Apps, Soma, settings, people, the calculator, the clipboard: what Quick is for. */
    val quick get() = this == Quick

    /** The fewest letters before the work starts. Asked for on purpose, photos start sooner than they used to. */
    val minimumLetters get() = if (this == Photos) 2 else 1

    /**
     * Photos show unless the words clearly name an app, a command or a person found. That guess is only for a
     * search that didn't ask for photos: in Photos he did, so they always show.
     */
    fun photosShown(wordsNameSomething: Boolean) = photos && (this == Photos || !wordsNameSomething)

    companion object {
        /** What a newly opened search starts on, whatever the last one was left on. */
        val opening = Quick
    }
}
