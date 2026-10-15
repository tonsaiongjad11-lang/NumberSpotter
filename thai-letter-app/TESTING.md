# Checks performed before delivering project

- Kotlin source `ShapeMatcher.kt` and `RegionConfig.kt` compiled with kotlinc 1.9
  using minimal Android graphics/context stubs.
- Tested shape matcher with 44 Thai consonants rendered with Noto Sans Thai,
  an 800x1300 screenshot simulation, 25 cells, and orange cue with a larger font.
- 88 simulated trials (two randomized boards per character):
  **80 selected correct cell / 0 selected incorrect cell / 8 withheld highlight**
  (using config `cutoff=0.61`, `minGap=0.028`).
- Not yet compiled as Android APK because this runtime has no Android SDK/Gradle.
- Not yet tested on a physical device or a screenshot of the actual game.
- Some highly similar Thai glyphs are deliberately left unhighlighted
  when the score gap is too small.

Caution: This is a new implementation built from the user-specified behavior;
no original NumberHighlightAssistant.zip source archive was available for direct patching.
