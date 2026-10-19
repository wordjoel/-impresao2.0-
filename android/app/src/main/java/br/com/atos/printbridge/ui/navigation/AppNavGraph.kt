package br.com.atos.printbridge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.atos.printbridge.ui.about.AboutScreen
import br.com.atos.printbridge.ui.history.HistoryScreen
import br.com.atos.printbridge.ui.home.HomeScreen
import br.com.atos.printbridge.ui.home.IntroScreen
import br.com.atos.printbridge.ui.labels.LabelEditorScreen
import br.com.atos.printbridge.ui.labels.LabelPreviewScreen
import br.com.atos.printbridge.ui.labels.LabelsScreen
import br.com.atos.printbridge.ui.printers.PrinterDiscoveryScreen
import br.com.atos.printbridge.ui.printers.PrinterFormScreen
import br.com.atos.printbridge.ui.printers.PrintersScreen
import br.com.atos.printbridge.ui.queue.QueueScreen
import br.com.atos.printbridge.ui.settings.SettingsScreen

object Routes {
    const val INTRO = "intro"
    const val HOME = "home"
    const val PRINTERS = "printers"
    const val PRINTER_FORM = "printer_form?printerId={printerId}&ip={ip}&port={port}&model={model}"
    const val PRINTER_DISCOVERY = "printer_discovery"
    const val QUEUE = "queue"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val LABELS = "labels"
    const val LABEL_EDITOR = "label_editor?templateId={templateId}"
    const val LABEL_PREVIEW = "label_preview?templateId={templateId}"

    fun printerForm(printerId: String? = null) =
        if (printerId != null) "printer_form?printerId=$printerId" else "printer_form"

    /** Formulário manual pré-preenchido com o host:porta que a descoberta encontrou (RAW não confirmado). */
    fun printerFormFromDiscovery(ip: String, port: Int, model: String?) =
        "printer_form?ip=$ip&port=$port" + (model?.let { "&model=$it" } ?: "")

    fun labelEditor(templateId: String? = null) =
        if (templateId != null) "label_editor?templateId=$templateId" else "label_editor"

    fun labelPreview(templateId: String) = "label_preview?templateId=$templateId"
}

// Chave do resultado entregue de volta à lista de impressoras após salvar o formulário.
private const val PRINTER_SAVED_MESSAGE = "printer_saved_message"

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.INTRO) {
        composable(Routes.INTRO) {
            IntroScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.INTRO) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToQueue = { navController.navigate(Routes.QUEUE) },
                onNavigateToPrinters = { navController.navigate(Routes.PRINTERS) },
                onNavigateToHistory = { navController.navigate(Routes.HISTORY) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToLabels = { navController.navigate(Routes.LABELS) },
            )
        }
        composable(Routes.LABELS) {
            LabelsScreen(
                onBack = { navController.popBackStack() },
                onCreate = { navController.navigate(Routes.labelEditor()) },
                onEdit = { id -> navController.navigate(Routes.labelEditor(id)) },
                onPreview = { id -> navController.navigate(Routes.labelPreview(id)) },
            )
        }
        composable(
            route = Routes.LABEL_EDITOR,
            arguments = listOf(navArgument("templateId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) {
            LabelEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.LABEL_PREVIEW,
            arguments = listOf(navArgument("templateId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) {
            LabelPreviewScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PRINTERS) { entry ->
            val savedMessage by entry.savedStateHandle
                .getStateFlow<String?>(PRINTER_SAVED_MESSAGE, null)
                .collectAsState()
            PrintersScreen(
                onAddPrinter = { navController.navigate(Routes.printerForm()) },
                onEditPrinter = { id -> navController.navigate(Routes.printerForm(id)) },
                onDiscover = { navController.navigate(Routes.PRINTER_DISCOVERY) },
                onBack = { navController.popBackStack() },
                savedMessage = savedMessage,
                onSavedMessageShown = { entry.savedStateHandle[PRINTER_SAVED_MESSAGE] = null },
            )
        }
        composable(Routes.PRINTER_DISCOVERY) {
            PrinterDiscoveryScreen(
                onBack = { navController.popBackStack() },
                onConfigureManually = { ip, port, model -> navController.navigate(Routes.printerFormFromDiscovery(ip, port, model)) },
            )
        }
        composable(
            route = Routes.PRINTER_FORM,
            arguments = listOf(
                navArgument("printerId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("ip") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("port") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("model") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            PrinterFormScreen(
                onSaved = { message ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PRINTER_SAVED_MESSAGE, message)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.QUEUE) {
            QueueScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
