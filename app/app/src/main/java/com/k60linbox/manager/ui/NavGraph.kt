// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.k60linbox.manager.data.SettingsStore

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val DETAIL = "container/{name}"

    fun detail(name: String): String = "container/" + Uri.encode(name)
}

/**
 * 应用根：按「是否已完成首次引导」决定起点。
 *
 * 引导完成时把 onboarded 写进 DataStore，这里观察到的 startDestination 随之变为主界面，
 * NavHost 会用新的图重建回退栈，因此无需在引导页里手动 navigate。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context.applicationContext) }
    val onboarded by settings.onboardedOrNull.collectAsStateWithLifecycle(initialValue = null)

    val done = onboarded
    if (done == null) {
        LoadingScreen()
        return
    }

    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (done) Routes.MAIN else Routes.ONBOARDING,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinish = {})
        }
        composable(Routes.MAIN) {
            MainScreen(onOpen = { name -> nav.navigate(Routes.detail(name)) })
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            ContainerDetailScreen(name = name, onBack = { nav.popBackStack() })
        }
    }
}
