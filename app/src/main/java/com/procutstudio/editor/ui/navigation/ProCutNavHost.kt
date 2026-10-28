package com.procutstudio.editor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.procutstudio.editor.ui.audio.AudioMixerScreen
import com.procutstudio.editor.ui.create.NewProjectScreen
import com.procutstudio.editor.ui.editor.EditorScreen
import com.procutstudio.editor.ui.export.ExportFlowScreen
import com.procutstudio.editor.ui.home.HomeScreen
import com.procutstudio.editor.ui.settings.SettingsScreen
import com.procutstudio.editor.ui.text.TextEditorScreen

/**
 * Single navigation graph for the whole app.
 *
 * The export flow is a nested graph: its three screens (settings → progress → success) share one
 * [ExportViewModel] scoped to the parent graph entry, so a running export survives navigation
 * between them and can never be started twice.
 */
@Composable
fun ProCutNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewProject = { navController.navigate(Routes.NEW_PROJECT) },
                onOpenProject = { projectId -> navController.navigate(Routes.editor(projectId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.NEW_PROJECT) {
            NewProjectScreen(
                onBack = { navController.popBackStack() },
                onProjectReady = { projectId ->
                    navController.navigate(Routes.editor(projectId)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            EditorScreen(
                onBack = { navController.popBackStack() },
                onOpenTextEditor = { textId -> navController.navigate(Routes.textEditor(projectId, textId)) },
                onOpenAudioMixer = { navController.navigate(Routes.audioMixer(projectId)) },
                onExport = { navController.navigate(Routes.export(projectId)) },
            )
        }

        composable(
            route = Routes.TEXT_EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("textId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            TextEditorScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.AUDIO_MIXER,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) {
            AudioMixerScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.EXPORT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) {
            ExportFlowScreen(
                onClose = { navController.popBackStack() },
                onOpenEditor = { projectId ->
                    navController.navigate(Routes.editor(projectId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBackToProjects = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
