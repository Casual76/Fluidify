package dev.lelonio.square.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.lelonio.square.ui.theme.SquareTheme

/** Synthetic identity. Root owns rendering and validation in the app modal host. Not rendered here. */
@Preview(showBackground = true, locale = "it")
@Composable
private fun LegacyDownloadsChoicePreview() {
    SquareTheme { LegacyDownloadsChoiceContent("spotify-account-example", {}, {}) }
}

@Preview(showBackground = true, locale = "it", fontScale = 1.5f)
@Composable
private fun LegacyDownloadsChoiceErrorPreview() {
    SquareTheme {
        LegacyDownloadsChoiceContent("spotify-account-example", {}, {}, "Impossibile salvare. Riprova.")
    }
}
