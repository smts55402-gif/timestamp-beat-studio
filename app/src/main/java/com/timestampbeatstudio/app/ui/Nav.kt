package com.timestampbeatstudio.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.timestampbeatstudio.app.AppContainer

object Routes {
    const val HOME = "home"
    const val PROCESSING = "processing/{projectId}"
    const val RESULT = "result/{projectId}"
    const val SETTINGS = "settings"

    fun processing(projectId: Long): String = "processing/$projectId"
    fun result(projectId: Long): String = "result/$projectId"
}

@Suppress("UNCHECKED_CAST")
fun <VM : ViewModel> vmFactory(create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = vmFactory { HomeViewModel(container.repository, container.audioDecoder) }
            )
            HomeScreen(
                viewModel = vm,
                onOpenProcessing = { id -> navController.navigate(Routes.processing(id)) },
                onOpenResult = { id -> navController.navigate(Routes.result(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(
            route = Routes.PROCESSING,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { entry ->
            val projectId = entry.arguments?.getLong("projectId") ?: return@composable
            val vm: ProcessingViewModel = viewModel(
                key = "processing-$projectId",
                factory = vmFactory {
                    ProcessingViewModel(projectId, container.repository, container.audioDecoder, container.engineFactory)
                }
            )
            ProcessingScreen(
                viewModel = vm,
                onDone = { id ->
                    navController.navigate(Routes.result(id)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
                onCancelled = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { entry ->
            val projectId = entry.arguments?.getLong("projectId") ?: return@composable
            val appContext = LocalContext.current.applicationContext
            val vm: ResultViewModel = viewModel(
                key = "result-$projectId",
                factory = vmFactory {
                    ResultViewModel(projectId, container.repository, container.exportManager, appContext)
                }
            )
            ResultScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = container.settings,
                modelDownloader = container.modelDownloader,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
