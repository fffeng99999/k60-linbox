// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.k60linbox.manager.data.SettingsStore
import kotlinx.coroutines.flow.catch

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

    // 注意：不能拿「读到的值是否为 null」当加载态——首次安装时 DataStore 读得到空 Preferences，
    // onboarded 本身就是 null，那样会永远停在加载页（真机实测踩到过）。
    // 所以单独用一个 loaded 标记来区分「还没读到」与「读到 false」。
    var loaded by remember { mutableStateOf(false) }
    var onboarded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings.onboarded
            .catch {
                // 读设置失败就当作没引导过，进入引导页，别把用户锁在加载页
                loaded = true
                onboarded = false
            }
            .collect {
                onboarded = it
                loaded = true
            }
    }

    if (!loaded) {
        LoadingScreen()
        return
    }

    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (onboarded) Routes.MAIN else Routes.ONBOARDING,
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
