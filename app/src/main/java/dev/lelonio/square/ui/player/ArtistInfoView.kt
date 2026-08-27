package dev.lelonio.square.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.data.ArtistInfo
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * Who is playing, as Spotify's own "About" page tells it.
 *
 * Portrait, name, a number and a description. The number is the follower count
 * rather than the monthly listeners the official client shows, and that is a
 * deliberate limit rather than an oversight: monthly listeners exist only behind
 * the GraphQL gateway, addressed by a persisted-query hash Spotify retires every
 * time it rebuilds its web client. This page is assembled from the access
 * point's own metadata and the account's own Web API application, both of which
 * last as long as playback does.
 *
 * Every field may be missing, and the page is still a page without any one of
 * them: an artist with no biography is common, and a portrait that has not
 * arrived is not a reason to show a spinner over the name.
 */
@Composable
fun ArtistInfoView(
    artist: ArtistInfo?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (artist == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
            } else {
                Text(
                    stringResource(R.string.no_artist_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Artwork(
            url = artist.imageUrl,
            title = artist.name,
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape),
            corner = 56.dp,
            decodeSize = 112.dp,
        )

        Text(
            artist.name,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            textAlign = TextAlign.Center,
        )

        // One line of facts rather than a stack of labelled rows: this is a
        // caption under a portrait, and a table would make it a database entry.
        val facts = listOfNotNull(
            artist.followers.takeIf { it > 0 }
                ?.let { stringResource(R.string.followers_count, compactCount(it)) },
            artist.genres.take(2)
                .joinToString(" · ") { it.replaceFirstChar(Char::uppercase) }
                .takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        if (facts.isNotEmpty()) {
            Text(
                facts,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                textAlign = TextAlign.Center,
            )
        }

        if (artist.biography.isNotBlank()) {
            Text(
                artist.biography,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        } else if (!loading) {
            Text(
                stringResource(R.string.no_biography),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * A follower count the width of a caption.
 *
 * Thousands and millions, one decimal, and the decimal dropped when it is a
 * zero: "1.2M" and "48K" read at a glance where "1,203,914" is a number to be
 * parsed. The same shortening the artist page in the library uses.
 */
private fun compactCount(value: Int): String = when {
    value >= 1_000_000 -> trimZero(value / 1_000_000.0) + "M"
    value >= 1_000 -> trimZero(value / 1_000.0) + "K"
    else -> value.toString()
}

private fun trimZero(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString()
    else rounded.toString()
}
