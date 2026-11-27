package org.jellyfin.mobile.ui.screens.connect

import androidx.compose.runtime.snapshots.SnapshotStateList
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.setup.ConnectionHelper

data class ServerSuggestion(
    val type: Type,
    val name: String,
    val address: String,
    /**
     * A timestamp for this suggestion, used for sorting.
     * For discovered servers, this should be the discovery time,
     * for saved servers, this should be the last used time.
     */
    val timestamp: Long,
) {
    enum class Type {
        DISCOVERED,
        SAVED,
    }
}

/**
 * Fills [suggestions] with the servers used before, then puts first the ones found in the local network as they
 * show up (a found server replaces the saved one with its address). Runs while the discovery lasts.
 */
suspend fun collectServerSuggestions(
    apiClientController: ApiClientController,
    connectionHelper: ConnectionHelper,
    suggestions: SnapshotStateList<ServerSuggestion>,
) {
    apiClientController.loadPreviouslyUsedServers().mapTo(suggestions) { server ->
        ServerSuggestion(
            type = ServerSuggestion.Type.SAVED,
            name = server.hostname,
            address = server.hostname,
            timestamp = server.lastUsedTimestamp,
        )
    }

    connectionHelper.discoverServersAsFlow().collect { serverInfo ->
        suggestions.removeIf { existing -> existing.address == serverInfo.address }
        suggestions.add(
            index = 0,
            ServerSuggestion(
                type = ServerSuggestion.Type.DISCOVERED,
                name = serverInfo.name,
                address = serverInfo.address,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }
}
