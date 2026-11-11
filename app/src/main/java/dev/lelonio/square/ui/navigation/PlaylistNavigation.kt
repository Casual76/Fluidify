package dev.lelonio.square.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import dev.lelonio.square.data.CatalogPlaylist

internal object PlaylistDestination {
    const val route = "playlist?context={context}&authority={authority}"
    val arguments get() = listOf(
        navArgument("context") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("authority") { type = NavType.StringType; nullable = true; defaultValue = null },
    )
    fun route(uri: String, authority: String?) =
        "playlist?context=${Uri.encode(uri)}&authority=${Uri.encode(authority.orEmpty())}"
    fun context(entry: NavBackStackEntry): String? = entry.arguments?.getString("context")
    fun authority(entry: NavBackStackEntry): String? = entry.arguments?.getString("authority")?.takeIf { it.isNotEmpty() }
}

/** Cleared by Navigation AFTER the pop animation; configuration recreation retains this lease. */
internal class PlaylistEntryLease(private val release: () -> Unit) : ViewModel() {
    override fun onCleared() = release()
}

internal fun retainPlaylistEntry(entry: NavBackStackEntry, release: (String) -> Unit) {
    val id = entry.id // retain an identifier, never a recreated entry or its Context
    ViewModelProvider(entry, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return PlaylistEntryLease { release(id) } as T
        }
    })[PlaylistEntryLease::class.java]
}

/** Selection follows committed Navigation events, never composition/gesture preview lifecycles. */
@Composable
internal fun PlaylistNavigationBinding(
    controller: NavHostController,
    revision: Long,
    fallback: String,
    select: (String?, String?, String?) -> Boolean,
    release: (String) -> Unit,
    changed: () -> Unit,
) {
    val selection: (NavBackStackEntry?) -> Unit = { entry ->
        if (entry?.destination?.route == PlaylistDestination.route) {
            retainPlaylistEntry(entry, release)
            if (!select(entry.id, PlaylistDestination.context(entry), PlaylistDestination.authority(entry))) {
                controller.navigate(fallback) {
                    popUpTo(controller.graph.id)
                    launchSingleTop = true
                }
            }
        } else select(null, null, null)
        changed()
    }
    // Callbacks may change at recomposition without reinstalling a destination listener mid-pop.
    val currentSelect = androidx.compose.runtime.rememberUpdatedState(selection)
    DisposableEffect(controller) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
            currentSelect.value(controller.currentBackStackEntry)
        }
        controller.addOnDestinationChangedListener(listener)
        onDispose { controller.removeOnDestinationChangedListener(listener) }
    }
    LaunchedEffect(controller, revision) { currentSelect.value(controller.currentBackStackEntry) }
}

internal fun NavHostController.pushPlaylist(
    playlist: CatalogPlaylist,
    authority: String?,
    prepare: (CatalogPlaylist) -> Unit,
) {
    prepare(playlist)
    navigate(PlaylistDestination.route(playlist.uri, authority))
}
