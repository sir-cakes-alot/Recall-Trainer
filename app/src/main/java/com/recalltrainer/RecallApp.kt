package com.recalltrainer

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun RecallApp() {
    val navController = rememberNavController()
    RecallNavHost(navController = navController)
}

@Composable
fun RecallNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartNumbers = { navController.navigate("number_config") },
                onStartColors = { navController.navigate("simon_colors") },
                onStartObjects = { navController.navigate("simon_objects") },
                onViewStats = { navController.navigate("stats") },
                onDailyChallenge = { navController.navigate("daily_challenge") }
            )
        }
        composable("daily_challenge") {
            DailyChallengeScreen(onBack = { navController.popBackStack() })
        }
        composable("number_config") {
            NumberConfigScreen(
                onStartGame = { config ->
                    navController.navigate("number_game/${config.length}/${config.displayMs}/${config.delayMs}/${config.useAudio}/${config.audioSpeed}/${config.roundSize}/${config.hardMode}/${config.negFeedback}")
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("number_game/{length}/{displayMs}/{delayMs}/{useAudio}/{audioSpeed}/{roundSize}/{hardMode}/{negFeedback}") { backStackEntry ->
            val length = backStackEntry.arguments?.getString("length")?.toInt() ?: 5
            val displayMs = backStackEntry.arguments?.getString("displayMs")?.toLong() ?: 2000
            val delayMs = backStackEntry.arguments?.getString("delayMs")?.toLong() ?: 1500
            val useAudio = backStackEntry.arguments?.getString("useAudio")?.toBoolean() ?: false
            val audioSpeed = backStackEntry.arguments?.getString("audioSpeed")?.toFloat() ?: 1.0f
            val roundSize = backStackEntry.arguments?.getString("roundSize")?.toInt() ?: 20
            val hardMode = backStackEntry.arguments?.getString("hardMode")?.toBoolean() ?: false
            val negFeedback = backStackEntry.arguments?.getString("negFeedback")?.toBoolean() ?: true

            NumberGameScreen(
                sequenceLength = length,
                displayDurationMs = displayMs,
                inputDelayMs = delayMs,
                useAudio = useAudio,
                audioSpeed = audioSpeed,
                roundSize = roundSize,
                hardMode = hardMode,
                negFeedback = negFeedback,
                onFinish = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
        composable("simon_colors") {
            SimonColorScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("simon_objects") {
            SimonObjectScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("stats") {
            StatsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
