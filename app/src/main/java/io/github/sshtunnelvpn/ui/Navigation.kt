package io.github.sshtunnelvpn.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.sshtunnelvpn.ui.apps.AppPickerScreen
import io.github.sshtunnelvpn.ui.home.HomeScreen
import io.github.sshtunnelvpn.ui.hosts.KnownHostsScreen
import io.github.sshtunnelvpn.ui.logs.LogsScreen
import io.github.sshtunnelvpn.ui.profiles.ProfileEditScreen
import io.github.sshtunnelvpn.ui.profiles.ProfilesScreen
import io.github.sshtunnelvpn.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Home : Route
    @Serializable data object Profiles : Route
    @Serializable data class EditProfile(val id: String? = null) : Route
    @Serializable data object Settings : Route
    @Serializable data object AppPicker : Route
    @Serializable data object Logs : Route
    @Serializable data object KnownHosts : Route
}

fun NavBackStack<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}

@Composable
fun AppNavigation(connectRequest: Int) {
    val backStack = rememberNavBackStack(Route.Home)
    val navigate: (Route) -> Unit = { backStack.add(it) }
    val back: () -> Unit = { backStack.pop() }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = {
            (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { -it / 8 } + fadeOut(tween(200)))
        },
        popTransitionSpec = {
            (slideInHorizontally(tween(300)) { -it / 8 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(200)))
        },
        predictivePopTransitionSpec = {
            (slideInHorizontally(tween(300)) { -it / 8 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(200)))
        },
        entryProvider = entryProvider {
            entry<Route.Home> { HomeScreen(navigate = navigate, connectRequest = connectRequest) }
            entry<Route.Profiles> { ProfilesScreen(navigate = navigate, onBack = back) }
            entry<Route.EditProfile> { ProfileEditScreen(profileId = it.id, onBack = back) }
            entry<Route.Settings> { SettingsScreen(navigate = navigate, onBack = back) }
            entry<Route.AppPicker> { AppPickerScreen(onBack = back) }
            entry<Route.Logs> { LogsScreen(onBack = back) }
            entry<Route.KnownHosts> { KnownHostsScreen(onBack = back) }
        },
    )
}
