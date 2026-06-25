package com.valhalla.thor.presentation.permission

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppPermission
import com.valhalla.thor.presentation.utils.AppIconModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PermissionManagerScreen(
    packageName: String,
    appName: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    onBack: () -> Unit,
    viewModel: PermissionManagerViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(packageName) {
        viewModel.loadPermissions(packageName, appName)
    }

    LaunchedEffect(state.errorMessage, state.successMessage) {
        state.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
        state.successMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }

    val animatedVisibilityScope = LocalNavAnimatedContentScope.current

    Scaffold(
        topBar = {
            PermissionTopAppBar(
                appName = appName,
                packageName = packageName,
                onBack = onBack,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Read-Only mode banner
            AnimatedVisibility(
                visible = !state.isPrivilegeMode && !state.isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ReadOnlyBanner()
            }

            // Search Bar
            SearchBar(
                query = state.searchQuery,
                onQueryChange = viewModel::updateSearchQuery
            )

            // Category Filter Navigation
            var selectedTab by remember { mutableIntStateOf(0) }
            CategorySelector(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                // Filter the permissions
                val filteredList = state.permissions.filter {
                    it.name.contains(state.searchQuery, ignoreCase = true) ||
                            it.label.contains(state.searchQuery, ignoreCase = true)
                }

                // Split into categories
                val runtimePermissions = filteredList.filter { it.isRuntime }

                @Suppress("DEPRECATION")
                val normalPermissions = filteredList.filter {
                    if (it.isRuntime) return@filter false
                    val base =
                        it.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
                    base == android.content.pm.PermissionInfo.PROTECTION_NORMAL ||
                            base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                }

                @Suppress("DEPRECATION")
                val signaturePermissions = filteredList.filter {
                    if (it.isRuntime) return@filter false
                    val base =
                        it.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
                    base == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE ||
                            base == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM ||
                            base == android.content.pm.PermissionInfo.PROTECTION_INTERNAL
                }
                val displayedLists = when (selectedTab) {
                    0 -> Triple(runtimePermissions, normalPermissions, signaturePermissions)
                    1 -> Triple(runtimePermissions, emptyList(), emptyList())
                    2 -> Triple(emptyList(), normalPermissions, emptyList())
                    else -> Triple(emptyList(), emptyList(), signaturePermissions)
                }

                if (filteredList.isEmpty() || (displayedLists.first.isEmpty() && displayedLists.second.isEmpty() && displayedLists.third.isEmpty())) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.permissions_no_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (displayedLists.first.isNotEmpty()) {
                            stickyHeader {
                                CategoryHeader(title = stringResource(R.string.permissions_sensitive_category))
                            }
                            items(displayedLists.first, key = { it.name }) { permission ->
                                PermissionRow(
                                    permission = permission,
                                    isPrivilegeMode = state.isPrivilegeMode,
                                    onToggle = { checked ->
                                        viewModel.togglePermission(permission.name, checked)
                                    }
                                )
                            }
                        }

                        if (displayedLists.second.isNotEmpty()) {
                            stickyHeader {
                                CategoryHeader(title = stringResource(R.string.permissions_normal_category))
                            }
                            items(displayedLists.second, key = { it.name }) { permission ->
                                PermissionRow(
                                    permission = permission,
                                    isPrivilegeMode = state.isPrivilegeMode,
                                    onToggle = { checked ->
                                        viewModel.togglePermission(permission.name, checked)
                                    }
                                )
                            }
                        }

                        if (displayedLists.third.isNotEmpty()) {
                            stickyHeader {
                                CategoryHeader(title = stringResource(R.string.permissions_signature_category))
                            }
                            items(displayedLists.third, key = { it.name }) { permission ->
                                PermissionRow(
                                    permission = permission,
                                    isPrivilegeMode = state.isPrivilegeMode,
                                    onToggle = { checked ->
                                        viewModel.togglePermission(permission.name, checked)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionTopAppBar(
    appName: String,
    packageName: String,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.round_close),
                contentDescription = stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // App Icon
        val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "icon-$packageName"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        } else {
            Modifier
        }
        AsyncImage(
            model = AppIconModel(packageName),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(sharedModifier)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val textSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "name-$packageName"),
                        animatedVisibilityScope = animatedVisibilityScope
                    ).skipToLookaheadSize()
                }
            } else {
                Modifier
            }
            Text(
                text = appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(textSharedModifier)
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = com.valhalla.thor.presentation.theme.firaMonoFontFamily
            )
        }
    }
}

@Composable
private fun ReadOnlyBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    )
                )
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.permissions_read_only_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.permissions_read_only_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.permissions_search)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.round_search),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            painter = painterResource(R.drawable.round_close),
                            contentDescription = stringResource(R.string.cd_clear),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun CategorySelector(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("All", "Sensitive", "Standard", "System")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            val bgColor =
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                    alpha = 0.6f
                )
            val textColor =
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bgColor)
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun PermissionRow(
    permission: AppPermission,
    isPrivilegeMode: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable(enabled = isPrivilegeMode) {
                onToggle(!permission.isGranted)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Label takes remaining space; badge is always measured first so it's never clipped
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = permission.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (permission.isRuntime) {
                    StatusBadge(
                        text = stringResource(R.string.sensitive),
                        color = MaterialTheme.colorScheme.errorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            // Allow 2 lines so long reverse-domain names can wrap instead of truncate
            Text(
                text = permission.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = com.valhalla.thor.presentation.theme.firaMonoFontFamily
            )

            if (permission.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = permission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (isPrivilegeMode) {
            Switch(
                checked = permission.isGranted,
                onCheckedChange = { isChecked ->
                    onToggle(isChecked)
                }
            )
        } else {
            val isGranted = permission.isGranted
            val chipText =
                stringResource(if (isGranted) R.string.permission_state_granted else R.string.permission_state_denied)
            val chipColor =
                if (isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
            val chipTextColor =
                if (isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

            Text(
                text = chipText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                softWrap = false,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(chipColor)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = chipTextColor
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 9.sp
    )
}
