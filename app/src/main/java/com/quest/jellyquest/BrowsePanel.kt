package com.quest.jellyquest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.button.PrimaryButton
import com.meta.spatial.uiset.button.SecondaryButton
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.quest.jellyquest.streaming.AuthState
import com.quest.jellyquest.streaming.JellyfinClient
import com.quest.jellyquest.streaming.JellyfinItem
import com.quest.jellyquest.streaming.PlaybackReporter
import kotlinx.coroutines.launch
import java.util.UUID

enum class BrowseTab { BROWSE, THEATER }

/**
 * Tabbed panel combining media browsing and theater configuration.
 * Uses Quick Connect for authentication — no typing passwords in VR.
 */
@Composable
fun BrowsePanel(
    jellyfinClient: JellyfinClient,
    onMediaSelected: (JellyfinItem) -> Unit,
    currentScreen: ScreenConfig,
    onTheaterSelected: (theater: TheaterExperience, seat: SeatPosition) -> Unit,
    spatialAudioEnabled: Boolean = true,
    onSpatialAudioToggled: (Boolean) -> Unit = {},
    roomAcousticsEnabled: Boolean = true,
    onRoomAcousticsToggled: (Boolean) -> Unit = {},
) {
    val authState by jellyfinClient.authState.collectAsState()
    val errorMessage by jellyfinClient.errorMessage.collectAsState()
    val serverUrl by jellyfinClient.serverUrl.collectAsState()
    // Scope at this level survives child composable transitions (prompt -> waiting -> browser)
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(BrowseTab.BROWSE) }

    SpatialTheme(colorScheme = draculaSpatialColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(24.dp),
        ) {
            // Tab bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (activeTab == BrowseTab.BROWSE) {
                        PrimaryButton(label = "Browse", expanded = true, onClick = {})
                    } else {
                        SecondaryButton(label = "Browse", expanded = true, onClick = { activeTab = BrowseTab.BROWSE })
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (activeTab == BrowseTab.THEATER) {
                        PrimaryButton(label = "Theater", expanded = true, onClick = {})
                    } else {
                        SecondaryButton(label = "Theater", expanded = true, onClick = { activeTab = BrowseTab.THEATER })
                    }
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            // Tab content
            when (activeTab) {
                BrowseTab.BROWSE -> {
                    when (authState) {
                        AuthState.DISCONNECTED, AuthState.ERROR -> {
                            // Scan the local network for a Jellyfin server when the prompt appears.
                            LaunchedEffect(Unit) { jellyfinClient.discoverServer() }
                            QuickConnectPrompt(
                                serverUrl = serverUrl,
                                onConnect = {
                                    scope.launch { jellyfinClient.startQuickConnect() }
                                },
                                errorMessage = if (authState == AuthState.ERROR) errorMessage else null,
                            )
                        }
                        AuthState.QUICK_CONNECT_PENDING -> {
                            QuickConnectWaiting(jellyfinClient = jellyfinClient)
                        }
                        AuthState.AUTHENTICATED -> {
                            LibraryBrowser(
                                jellyfinClient = jellyfinClient,
                                onMediaSelected = onMediaSelected,
                            )
                        }
                    }
                }
                BrowseTab.THEATER -> {
                    TheaterPickerContent(
                        currentScreen = currentScreen,
                        onTheaterSelected = onTheaterSelected,
                        spatialAudioEnabled = spatialAudioEnabled,
                        onSpatialAudioToggled = onSpatialAudioToggled,
                        roomAcousticsEnabled = roomAcousticsEnabled,
                        onRoomAcousticsToggled = onRoomAcousticsToggled,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickConnectPrompt(
    serverUrl: String?,
    onConnect: () -> Unit,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "JellyQuest",
            style = SpatialTheme.typography.headline1Strong.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )

        Spacer(modifier = Modifier.size(8.dp))

        Text(
            text = serverUrl ?: "Searching for server…",
            style = SpatialTheme.typography.body2.copy(
                color = SpatialTheme.colorScheme.secondaryAlphaBackground,
            ),
        )

        Spacer(modifier = Modifier.size(24.dp))

        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = DraculaGreen,
                contentColor = Color.Black,
            ),
        ) {
            Text("Connect with Quick Connect")
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = errorMessage,
                style = SpatialTheme.typography.body2.copy(
                    color = Color(0xFFFF5555),
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QuickConnectWaiting(jellyfinClient: JellyfinClient) {
    val code by jellyfinClient.quickConnectCode.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Quick Connect",
            style = SpatialTheme.typography.headline2Strong.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )

        Spacer(modifier = Modifier.size(16.dp))

        if (code != null) {
            Text(
                text = "Enter this code in your Jellyfin dashboard:",
                style = SpatialTheme.typography.body1.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.size(16.dp))

            // Large code display
            Text(
                text = code!!,
                style = SpatialTheme.typography.headline1Strong.copy(
                    color = DraculaGreen,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )

            Spacer(modifier = Modifier.size(24.dp))

            Text(
                text = "Waiting for authorization...",
                style = SpatialTheme.typography.body2.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
            )
        } else {
            Text(
                text = "Connecting to server...",
                style = SpatialTheme.typography.body1.copy(
                    color = SpatialTheme.colorScheme.secondaryAlphaBackground,
                ),
            )
        }

        Spacer(modifier = Modifier.size(16.dp))

        Text(
            text = "Cancel",
            style = SpatialTheme.typography.body2.copy(color = Color(0xFFFF5555)),
            modifier = Modifier.clickable { jellyfinClient.disconnect() },
        )
    }
}

@Composable
private fun LibraryBrowser(
    jellyfinClient: JellyfinClient,
    onMediaSelected: (JellyfinItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentItems by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var breadcrumb by remember { mutableStateOf<List<Pair<String, UUID?>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var genreFilter by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    val gridState = rememberLazyGridState()

    // Use pre-fetched data if available, otherwise fetch on demand
    LaunchedEffect(Unit) {
        val cached = jellyfinClient.cachedLibraries.value
        if (cached != null) {
            currentItems = cached
        } else {
            isLoading = true
            currentItems = jellyfinClient.getLibraries()
            isLoading = false
        }
    }

    // Header
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = breadcrumb.lastOrNull()?.first ?: "Libraries",
            style = SpatialTheme.typography.headline2Strong.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
        )
        Text(
            text = "Disconnect",
            style = SpatialTheme.typography.body2.copy(color = Color(0xFFFF5555)),
            modifier = Modifier.clickable { jellyfinClient.disconnect() },
        )
    }

    // Back button when navigating
    if (breadcrumb.isNotEmpty()) {
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "< Back",
            style = SpatialTheme.typography.body1.copy(color = DraculaCyan),
            modifier = Modifier.clickable {
                val newBreadcrumb = breadcrumb.dropLast(1)
                val parentId = newBreadcrumb.lastOrNull()?.second
                // Check cache before fetching
                val cached = if (parentId != null) {
                    jellyfinClient.cachedItems.value[parentId]
                } else {
                    jellyfinClient.cachedLibraries.value
                }
                if (cached != null) {
                    breadcrumb = newBreadcrumb
                    currentItems = cached
                } else {
                    isLoading = true
                    scope.launch {
                        breadcrumb = newBreadcrumb
                        currentItems = if (parentId != null) {
                            jellyfinClient.getItems(parentId)
                        } else {
                            jellyfinClient.getLibraries()
                        }
                        isLoading = false
                    }
                }
            },
        )
    }

    Spacer(modifier = Modifier.size(10.dp))

    if (isLoading) {
        Text(
            text = "Loading...",
            style = SpatialTheme.typography.body1.copy(
                color = SpatialTheme.colorScheme.secondaryAlphaBackground,
            ),
        )
        return
    }

    val items = currentItems ?: emptyList()
    if (items.isEmpty()) {
        Text(
            text = "No items found",
            style = SpatialTheme.typography.body1.copy(
                color = SpatialTheme.colorScheme.secondaryAlphaBackground,
            ),
        )
        return
    }

    val openItem: (JellyfinItem) -> Unit = { item ->
        if (item.isFolder) {
            genreFilter = null
            val cachedChildren = jellyfinClient.cachedItems.value[item.id]
            if (cachedChildren != null) {
                currentItems = cachedChildren
                breadcrumb = breadcrumb + (item.name to item.id)
            } else {
                isLoading = true
                scope.launch {
                    val children = jellyfinClient.getItems(item.id)
                    currentItems = children
                    breadcrumb = breadcrumb + (item.name to item.id)
                    isLoading = false
                }
            }
        } else {
            onMediaSelected(item)
        }
    }

    // Sort chips, Jellyfin-style.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(SortMode.entries, key = { it.name }) { mode ->
            val selected = sortMode == mode
            Text(
                text = mode.label,
                style = SpatialTheme.typography.body2.copy(
                    color = if (selected) Color.Black else DraculaForeground,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) DraculaCyan else DraculaCurrentLine)
                    .clickable { sortMode = mode }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
    Spacer(modifier = Modifier.size(8.dp))

    // Genre filter chips — built from the genres present in the current list.
    val genres = remember(items) {
        items.flatMap { it.genres }.distinct().sorted()
    }
    if (genres.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(listOf<String?>(null) + genres, key = { it ?: "__all__" }) { genre ->
                val selected = genreFilter == genre
                Text(
                    text = genre ?: "All",
                    style = SpatialTheme.typography.body2.copy(
                        color = if (selected) Color.Black else DraculaForeground,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) DraculaPurple else DraculaCurrentLine)
                        .clickable { genreFilter = genre }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(modifier = Modifier.size(10.dp))
    }

    val displayed = remember(items, genreFilter, sortMode) {
        val filter = genreFilter
        val filtered = if (filter == null) items else items.filter { filter in it.genres }
        when (sortMode) {
            SortMode.NAME -> filtered.sortedBy { it.sortKey.lowercase() }
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.dateCreatedMs }
            SortMode.RATING -> filtered.sortedByDescending { it.communityRating }
            // Unwatched first, A–Z within each group (sortedBy is stable).
            SortMode.UNWATCHED -> filtered.sortedBy { it.sortKey.lowercase() }.sortedBy { it.played }
        }
    }

    // Poster grid with a single A–Z jump strip on the right, Jellyfin-style.
    // The strip only makes sense in A–Z order, so it hides for other sorts.
    Row(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        ) {
            items(displayed, key = { it.id }) { item ->
                PosterCard(
                    item = item,
                    imageUrl = jellyfinClient.getImageUrl(item.id),
                    onClick = { openItem(item) },
                )
            }
        }
        if (sortMode == SortMode.NAME) {
            AlphabetStrip { letter -> scope.launch { gridState.scrollToLetter(displayed, letter) } }
        }
    }
}

private enum class SortMode(val label: String) {
    NAME("A–Z"),
    DATE_ADDED("Recently Added"),
    RATING("Top Rated"),
    UNWATCHED("Unwatched"),
}

/** Snap the grid to the first item at or after [letter] ('#' = non-alphabetic). */
private suspend fun LazyGridState.scrollToLetter(items: List<JellyfinItem>, letter: Char) {
    val index = items.indexOfFirst { item ->
        val c = item.sortKey.firstOrNull()?.uppercaseChar()
        when {
            c == null -> false
            letter == '#' -> !c.isLetter()
            else -> c.isLetter() && c >= letter
        }
    }
    if (index >= 0) {
        // Instant snap — animated scrolling across hundreds of grid rows
        // renders as seconds of janky fast-scroll in a VR panel.
        scrollToItem(index)
    }
}

@Composable
private fun AlphabetStrip(onLetter: (Char) -> Unit) {
    // Each letter takes an equal share of the strip's full height, so all 27
    // always fit regardless of panel size — no clipping past Q, and the tap
    // target is the whole slot, not just the glyph.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxHeight()
            .padding(start = 2.dp),
    ) {
        "#ABCDEFGHIJKLMNOPQRSTUVWXYZ".forEach { c ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onLetter(c) }
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = c.toString(),
                    style = SpatialTheme.typography.body2.copy(
                        color = DraculaCyan,
                        fontSize = 13.sp,
                        lineHeight = 13.sp,
                    ),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: JellyfinItem,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    val hasProgress = !item.isFolder && item.playbackPositionTicks > 0 && item.runTimeTicks > 0
    val fullyWatched = hasProgress && PlaybackReporter.isFullyWatched(
        item.playbackPositionTicks, item.runTimeTicks,
    )
    val progressPercent = if (hasProgress && !fullyWatched) {
        PlaybackReporter.computeProgressPercent(item.playbackPositionTicks, item.runTimeTicks)
    } else 0

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(DraculaCurrentLine),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (fullyWatched) {
                Text(
                    text = "✓",
                    style = SpatialTheme.typography.body2.copy(color = Color.Black),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DraculaGreen)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (progressPercent > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPercent / 100f)
                            .height(4.dp)
                            .background(DraculaPurple),
                    )
                }
            }
        }
        Text(
            text = item.name,
            style = SpatialTheme.typography.body2.copy(
                color = SpatialTheme.colorScheme.primaryAlphaBackground,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}
