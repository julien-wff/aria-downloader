# Interface icons

Material Symbols Rounded by Google, Apache 2.0.

Source: https://github.com/google/material-design-icons/tree/49d4db35df873165d6bd6ba09b063c7dafbac2f4/symbols/android

The selected 24 dp, weight 400 assets are bundled as Android vector drawables. Resource tint is
supplied by Compose; the back arrow is auto-mirrored. See
the [bundled license](material-symbols-LICENSE.txt). The vectors are packaged with the app.

The [Aria launcher mark](aria-icon.svg) is an original project asset. Its foreground geometry is
shared with the adaptive and monochrome launcher resources.

## Visual review

`ResponsiveUiTest` exercises 700 × 360 dp landscape, 1000 × 800 dp tablet and 360 × 740 dp at 150%
font scale with RTL layout. These are local Compose overrides; they do not change the device's
display settings. `ProductUiTest` also covers real IME visibility, focus traversal, autosave and
Activity recreation.

To collect optional PNG review captures, build and install the debug app and its test APK, then run
instrumentation directly with `-e captureUi true` and
`-e class com.julien_wff.aria_android.ResponsiveUiTest,com.julien_wff.aria_android.ProductUiTest`.
Use the runner `com.julien_wff.aria_android.test/androidx.test.runner.AndroidJUnitRunner`. Captures
are written to the debug app's cache as `ui-<case>.png`; retrieve them with
`adb exec-out run-as com.julien_wff.aria_android cat cache/ui-<case>.png`. Keyboard captures include
the system IME; the other captures contain only the app. Ordinary Gradle device tests may remove the
installed APKs and their caches afterward.
