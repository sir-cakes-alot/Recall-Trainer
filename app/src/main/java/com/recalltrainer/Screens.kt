package com.recalltrainer

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random as KotlinRandom

// Data classes
data class NumberConfig(
    val length: Int = 5,
    val displayMs: Long = 2000,
    val delayMs: Long = 1500,
    val useAudio: Boolean = false,
    val audioSpeed: Float = 1.0f,
    val roundSize: Int = 20,
    val hardMode: Boolean = false,
    val negFeedback: Boolean = true
)

data class LogEntry(
    val timestamp: Long,
    val mode: String,
    val length: Int,
    val sequence: String,
    val userResponse: String,
    val correct: Boolean
)

// Simple file logger & Settings Manager
object GameLogger {
    private const val PREFS_NAME = "recall_prefs"
    private const val KEY_COINS = "coins"
    private const val KEY_MULTIPLIER = "multiplier"
    private const val KEY_LAST_DAILY = "last_daily"
    private const val KEY_DAILY_SEQ = "daily_seq"
    private const val KEY_DAILY_TS = "daily_ts"

    fun log(context: Context, entry: LogEntry) {
        val logFile = File(context.filesDir, "recall_logs.csv")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(entry.timestamp))
        val line = "$date,${entry.mode},${entry.length},\"${entry.sequence}\",\"${entry.userResponse}\",${entry.correct}\n"
        logFile.appendText(line)
        
        if (entry.correct) {
            val mult = getDailyMultiplier(context)
            addCoins(context, (10 * mult).toInt())
        }
    }

    fun getCoins(context: Context): Int = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_COINS, 100)
    fun addCoins(context: Context, amount: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_COINS, getCoins(context) + amount).apply()
    }
    fun spendCoins(context: Context, amount: Int): Boolean {
        val current = getCoins(context)
        if (current >= amount) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_COINS, current - amount).apply()
            return true
        }
        return false
    }

    fun saveConfig(context: Context, config: NumberConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("num_len", config.length)
            putLong("num_disp", config.displayMs)
            putLong("num_delay", config.delayMs)
            putBoolean("num_audio", config.useAudio)
            putFloat("num_speed", config.audioSpeed)
            putInt("num_round", config.roundSize)
            putBoolean("num_hard", config.hardMode)
            putBoolean("num_neg", config.negFeedback)
        }.apply()
    }

    fun loadConfig(context: Context): NumberConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return NumberConfig(
            length = prefs.getInt("num_len", 5),
            displayMs = prefs.getLong("num_disp", 2000L),
            delayMs = prefs.getLong("num_delay", 1500L),
            useAudio = prefs.getBoolean("num_audio", false),
            audioSpeed = prefs.getFloat("num_speed", 1.0f),
            roundSize = prefs.getInt("num_round", 20),
            hardMode = prefs.getBoolean("num_hard", false),
            negFeedback = prefs.getBoolean("num_neg", true)
        )
    }

    fun getDailyMultiplier(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDaily = prefs.getString(KEY_LAST_DAILY, "")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return if (lastDaily == today) 1.1f else 1.0f
    }

    fun setDailyComplete(context: Context) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_DAILY, today).apply()
    }

    fun getDailyChallenge(context: Context): Pair<String, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDay = prefs.getString("challenge_day", "")
        if (savedDay != today) {
            val newSeq = (1..8).joinToString("") { KotlinRandom.nextInt(10).toString() }
            val now = System.currentTimeMillis()
            prefs.edit().putString("challenge_day", today)
                .putString(KEY_DAILY_SEQ, newSeq)
                .putLong(KEY_DAILY_TS, now).apply()
            return newSeq to now
        }
        return prefs.getString(KEY_DAILY_SEQ, "")!! to prefs.getLong(KEY_DAILY_TS, 0L)
    }

    fun getLogs(context: Context): List<LogEntry> {
        val logFile = File(context.filesDir, "recall_logs.csv")
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split(",")
                if (parts.size < 6) return@mapNotNull null
                val dateStr = parts[0]
                val ts = try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(dateStr)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) { System.currentTimeMillis() }
                LogEntry(
                    timestamp = ts,
                    mode = parts[1],
                    length = parts[2].toIntOrNull() ?: 0,
                    sequence = parts[3].trim('"'),
                    userResponse = parts[4].trim('"'),
                    correct = parts[5].toBooleanStrictOrNull() ?: false
                )
            } catch (e: Exception) { null }
        }.sortedByDescending { it.timestamp }
    }

    fun clearLogs(context: Context) {
        File(context.filesDir, "recall_logs.csv").delete()
    }
}

// ==================== HOME SCREEN ====================
@Composable
fun HomeScreen(
    onStartNumbers: () -> Unit,
    onStartColors: () -> Unit,
    onStartObjects: () -> Unit,
    onViewStats: () -> Unit,
    onDailyChallenge: () -> Unit
) {
    val context = LocalContext.current
    var coins by remember { mutableIntStateOf(GameLogger.getCoins(context)) }
    val multiplier = GameLogger.getDailyMultiplier(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFFFFD700).copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp)) {
                Text("💰 $coins coins ${if(multiplier > 1.0f) " (x1.1 active)" else ""}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
            }
        }

        Text(
            "Recall Trainer",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Text("Train your brain every day", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))

        Button(onClick = onDailyChallenge, modifier = Modifier.fillMaxWidth().height(72.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
            Text("📅 DAILY CHALLENGE", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(16.dp))

        Button(onClick = onStartNumbers, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("🔢 Number Sequences", fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStartColors, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("🎨 Color Simon", fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStartObjects, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("🖼️ Object Simon", fontSize = 18.sp)
        }
        Spacer(Modifier.height(32.dp))

        OutlinedButton(onClick = onViewStats, modifier = Modifier.fillMaxWidth()) {
            Text("View Stats & Logs")
        }
    }
}

// ==================== NUMBER CONFIG ====================
@Composable
fun NumberConfigScreen(
    onStartGame: (NumberConfig) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val saved = remember { GameLogger.loadConfig(context) }

    var length by remember { mutableIntStateOf(saved.length) }
    var displayMs by remember { mutableLongStateOf(saved.displayMs) }
    var delayMs by remember { mutableLongStateOf(saved.delayMs) }
    var useAudio by remember { mutableStateOf(saved.useAudio) }
    var audioSpeed by remember { mutableFloatStateOf(saved.audioSpeed) }
    var roundSize by remember { mutableIntStateOf(saved.roundSize) }
    var hardMode by remember { mutableStateOf(saved.hardMode) }
    var negFeedback by remember { mutableStateOf(saved.negFeedback) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Number Sequence Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Text("Sequence Length: $length digits")
        Slider(
            value = length.toFloat(),
            onValueChange = { length = it.toInt() },
            valueRange = 4f..10f,
            steps = 5
        )

        Spacer(Modifier.height(16.dp))
        Text("Display Time: ${displayMs / 1000.0}s")
        Slider(
            value = displayMs.toFloat(),
            onValueChange = { displayMs = it.toLong() },
            valueRange = 800f..5000f,
            steps = 8
        )

        Spacer(Modifier.height(16.dp))
        Text("Delay before typing: ${delayMs / 1000.0}s")
        Slider(
            value = delayMs.toFloat(),
            onValueChange = { delayMs = it.toLong() },
            valueRange = 500f..3000f,
            steps = 4
        )

        Spacer(Modifier.height(16.dp))
        Text("Round Size: $roundSize sequences")
        Slider(
            value = roundSize.toFloat(),
            onValueChange = { roundSize = it.toInt() },
            valueRange = 10f..30f,
            steps = 3
        )

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useAudio, onCheckedChange = { useAudio = it })
            Text("Audio only (no visual numbers)")
        }

        if (useAudio) {
            Spacer(Modifier.height(16.dp))
            Text("Gap between numbers: ${String.format(Locale.US, "%.0f", (400 / audioSpeed))}ms")
            Slider(
                value = audioSpeed,
                onValueChange = { audioSpeed = it },
                valueRange = 0.5f..2.5f,
                steps = 19
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = hardMode, onCheckedChange = { hardMode = it })
            Spacer(Modifier.width(8.dp))
            Text("Hard Mode (Distractions!)")
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = negFeedback, onCheckedChange = { negFeedback = it })
            Spacer(Modifier.width(8.dp))
            Text("Negative Feedback (Enabled)")
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                val cfg = NumberConfig(length, displayMs, delayMs, useAudio, audioSpeed, roundSize, hardMode, negFeedback)
                GameLogger.saveConfig(context, cfg)
                onStartGame(cfg)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Start Round", fontSize = 18.sp)
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

// ==================== NUMBER GAME ====================
@Composable
fun NumberGameScreen(
    sequenceLength: Int,
    displayDurationMs: Long,
    inputDelayMs: Long,
    useAudio: Boolean,
    audioSpeed: Float,
    roundSize: Int,
    hardMode: Boolean,
    negFeedback: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var coins by remember { mutableIntStateOf(GameLogger.getCoins(context)) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    var currentTrial by remember { mutableStateOf(0) }
    var currentSequence by remember { mutableStateOf("") }
    var isShowing by remember { mutableStateOf(false) }
    var isDistracting by remember { mutableStateOf(false) }
    var triviaQuestion by remember { mutableStateOf("") }
    var triviaInput by remember { mutableStateOf("") }
    var triviaAnswer by remember { mutableStateOf("") }
    var isWaitingInput by remember { mutableStateOf(false) }
    var userInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var bannerMessage by remember { mutableStateOf("") }
    var bannerColor by remember { mutableStateOf(Color.Transparent) }
    var correctCount by remember { mutableStateOf(0) }
    var gameOver by remember { mutableStateOf(false) }
    var repeatTrigger by remember { mutableIntStateOf(0) }

    val sequences = remember { mutableStateListOf<String>() }
    val responses = remember { mutableStateListOf<String>() }
    val corrects = remember { mutableStateListOf<Boolean>() }
    val usedTriviaIndices = remember { mutableStateListOf<Int>() }

    fun generateSequence(): String {
        return (1..sequenceLength).joinToString("") { Random().nextInt(10).toString() }
    }

    fun playSequence(seq: String) {
        if (useAudio && ttsReady) {
            tts.setSpeechRate(1.0f)
            seq.forEachIndexed { index, digit ->
                tts.speak(digit.toString(), TextToSpeech.QUEUE_ADD, null, "digit$index")
                val baseGap = 400L
                val gap = (baseGap / audioSpeed).toLong().coerceAtLeast(0L)
                if (index < seq.length - 1) {
                    tts.playSilentUtterance(gap, TextToSpeech.QUEUE_ADD, null)
                }
            }
        }
    }

    fun startNextTrial() {
        if (currentTrial >= roundSize) {
            gameOver = true
            return
        }
        val seq = generateSequence()
        currentSequence = seq
        userInput = ""
        feedback = ""
        playSequence(seq)
    }

    LaunchedEffect(currentTrial, ttsReady) {
        if (!gameOver && (currentTrial > 0 || ttsReady || !useAudio)) {
            startNextTrial()
        }
    }

    data class Trivia(val question: String, val answer: String, val options: List<String>? = null)
    val triviaPool = remember {
        listOf(
            Trivia("2 + 2 = ?", "4"), Trivia("10 - 3 = ?", "7"), Trivia("3 x 3 = ?", "9"),
            Trivia("15 + 5 = ?", "20"), Trivia("12 / 4 = ?", "3"), Trivia("7 + 8 = ?", "15"),
            Trivia("20 - 6 = ?", "14"), Trivia("5 x 4 = ?", "20"), Trivia("9 + 9 = ?", "18"),
            Trivia("100 / 10 = ?", "10"), Trivia("13 + 7 = ?", "20"), Trivia("25 - 5 = ?", "20"),
            Trivia("Color of grass?", "Green", listOf("Red", "Blue", "Green", "Yellow")),
            Trivia("Opposite of Up?", "Down", listOf("Left", "Right", "Up", "Down")),
            Trivia("Capital of France?", "Paris", listOf("London", "Paris", "Berlin", "Rome")),
            Trivia("Is fire hot?", "Yes", listOf("Yes", "No", "Maybe", "Only on Sundays")),
            Trivia("Sun color?", "Yellow", listOf("Blue", "Yellow", "Purple", "Green")),
            Trivia("Water state?", "Liquid", listOf("Solid", "Liquid", "Gas", "Plasma")),
            Trivia("Ocean color?", "Blue", listOf("Red", "Blue", "Yellow", "Black")),
            Trivia("Earth shape?", "Round", listOf("Flat", "Square", "Round", "Triangle")),
            Trivia("Sky color?", "Blue", listOf("Green", "Blue", "Red", "Brown")),
            Trivia("Milk color?", "White", listOf("White", "Black", "Pink", "Yellow")),
            Trivia("Ice temperature?", "Cold", listOf("Hot", "Cold", "Warm", "Boiling")),
            Trivia("Sugar taste?", "Sweet", listOf("Sour", "Sweet", "Bitter", "Salty")),
            Trivia("Lemon taste?", "Sour", listOf("Sweet", "Bitter", "Sour", "Spicy")),
            Trivia("Ant size?", "Small", listOf("Large", "Small", "Medium", "Huge")),
            Trivia("Elephant size?", "Large", listOf("Tiny", "Small", "Medium", "Large")),
            Trivia("Bird action?", "Fly", listOf("Swim", "Fly", "Crawl", "Talk")),
            Trivia("Fish action?", "Swim", listOf("Run", "Fly", "Swim", "Climb")),
            Trivia("Tree part?", "Leaf", listOf("Wheel", "Leaf", "Engine", "Window")),
            Trivia("Rain comes from?", "Clouds", listOf("Clouds", "Ground", "Sun", "Moon")),
            Trivia("Cat sound?", "Meow", listOf("Bark", "Meow", "Moo", "Chirp")),
            Trivia("Dog sound?", "Bark", listOf("Bark", "Meow", "Moo", "Chirp")),
            Trivia("Night light?", "Moon", listOf("Sun", "Moon", "Flashlight", "Stars")),
            Trivia("Day light?", "Sun", listOf("Sun", "Moon", "Flashlight", "Stars")),
            Trivia("Winter weather?", "Snow", listOf("Snow", "Hot", "Sunny", "Tropical")),
            Trivia("Bee sound?", "Buzz", listOf("Buzz", "Hiss", "Roar", "Click")),
            Trivia("Book purpose?", "Read", listOf("Eat", "Read", "Sleep", "Drive")),
            Trivia("Shoes go on?", "Feet", listOf("Hands", "Feet", "Head", "Ears")),
            Trivia("Hat goes on?", "Head", listOf("Hands", "Feet", "Head", "Ears"))
        )
    }

    var triviaOptions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(currentSequence, repeatTrigger) {
        if (currentSequence.isNotEmpty()) {
            isShowing = true
            isDistracting = false
            isWaitingInput = false
            delay(displayDurationMs)
            isShowing = false
            
            if (hardMode) {
                if (usedTriviaIndices.size >= triviaPool.size) usedTriviaIndices.clear()
                val available = triviaPool.indices.filter { it !in usedTriviaIndices }
                val idx = available.random()
                usedTriviaIndices.add(idx)
                val t = triviaPool[idx]
                triviaQuestion = t.question
                triviaAnswer = t.answer
                triviaOptions = t.options?.shuffled() ?: emptyList()
                triviaInput = ""
                isDistracting = true
            } else {
                delay(inputDelayMs)
                isWaitingInput = true
            }
        }
    }

    fun submitAnswer() {
        if (userInput.isBlank()) return
        val correct = userInput.trim() == currentSequence
        if (correct) {
            correctCount++
            coins = GameLogger.getCoins(context)
            bannerMessage = listOf("Brilliant!", "Unstoppable!", "Great memory!", "Perfect!").random()
            bannerColor = Color(0xFF4CAF50)
        } else {
            if (negFeedback) {
                bannerMessage = listOf("Pathetic.", "Try harder next time.", "Memory like a goldfish.", "Disappointing.").random()
                bannerColor = Color(0xFFF44336)
            } else {
                bannerMessage = "Not quite. Keep practicing!"
                bannerColor = Color(0xFF757575)
            }
        }

        sequences.add(currentSequence)
        responses.add(userInput.trim())
        corrects.add(correct)

        GameLogger.log(
            context,
            LogEntry(
                timestamp = System.currentTimeMillis(),
                mode = if (useAudio) "Numbers-Audio" else "Numbers-Visual",
                length = sequenceLength,
                sequence = currentSequence,
                userResponse = userInput.trim(),
                correct = correct
            )
        )

        feedback = if (correct) "✅ Correct!" else "❌ Incorrect. Correct was $currentSequence"
        isWaitingInput = false
    }

    fun submitTrivia(ans: String) {
        if (ans.trim().lowercase() == triviaAnswer.lowercase()) {
            isDistracting = false
            isWaitingInput = true
        } else {
            isDistracting = false
            userInput = "WRONG_TRIVIA"
            submitAnswer()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFFFFD700).copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp)) {
                Text("💰 $coins", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
            }
        }

        Text("Number Recall - ${if (useAudio) "Audio" else "Visual"}", style = MaterialTheme.typography.headlineSmall)
        Text("Trial ${currentTrial + 1} / $roundSize   |   Correct: $correctCount", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(32.dp))

        when {
            gameOver -> {
                Text("Round Complete!", style = MaterialTheme.typography.headlineMedium)
                Text("Score: $correctCount / $roundSize")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onFinish) { Text("Back to Menu") }
            }
            isShowing -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFFE3F2FD), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (useAudio) {
                        Text(
                            "🔊",
                            fontSize = 64.sp,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            currentSequence,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Text(if (useAudio) "Listen..." else "Memorize...", style = MaterialTheme.typography.bodyLarge)
            }
            isDistracting -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .background(Color.Black, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("QUICK TRIVIA!", color = Color.Red, fontWeight = FontWeight.Bold)
                        Text(triviaQuestion, color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        
                        if (triviaOptions.isEmpty()) {
                            OutlinedTextField(
                                value = triviaInput,
                                onValueChange = { triviaInput = it },
                                modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(4.dp)),
                                placeholder = { Text("Answer...") },
                                singleLine = true
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { submitTrivia(triviaInput) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Submit Answer")
                            }
                        } else {
                            // Multiple Choice
                            triviaOptions.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { option ->
                                        Button(
                                            onClick = { submitTrivia(option) },
                                            modifier = Modifier.weight(1f).height(60.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                        ) {
                                            Text(option, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                Text("Think fast!", style = MaterialTheme.typography.bodyLarge, color = Color.Red)
            }
            !isWaitingInput && !isShowing && feedback.isNotEmpty() -> {
                Text(feedback, fontSize = 22.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    if (currentTrial + 1 < roundSize) {
                        currentTrial++
                    } else {
                        gameOver = true
                    }
                }) {
                    Text(if (currentTrial + 1 < roundSize) "Next Sequence" else "Finish Round")
                }
            }
            isWaitingInput -> {
                Text("What was the number?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { if (it.length <= sequenceLength && it.all { c -> c.isDigit() }) userInput = it },
                    label = { Text("Enter the number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { submitAnswer() },
                    enabled = userInput.length == sequenceLength,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Submit")
                }
                
                Spacer(Modifier.height(16.dp))
                if (GameLogger.getCoins(context) >= 50) {
                    OutlinedButton(
                        onClick = {
                            if (GameLogger.spendCoins(context, 50)) {
                                coins = GameLogger.getCoins(context)
                                playSequence(currentSequence)
                                repeatTrigger++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Repeat (50 💰)")
                    }
                }
            }
            else -> {
                Text("Get ready...", fontSize = 20.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        if (bannerMessage.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = bannerColor.copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    bannerMessage,
                    modifier = Modifier.padding(16.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("Exit Round") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun DistractionContent() {
    val random = remember { KotlinRandom(System.currentTimeMillis()) }
    var phase by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(Unit) {
        while(true) {
            phase = (phase + 1) % 10
            delay(150)
        }
    }
    
    Box(Modifier.fillMaxSize()) {
        repeat(5) {
            Text(
                text = random.nextInt(100).toString(),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = (20 + random.nextInt(30)).sp,
                modifier = Modifier.align(
                    BiasAlignment(
                        horizontalBias = (random.nextFloat() * 2 - 1),
                        verticalBias = (random.nextFloat() * 2 - 1)
                    )
                )
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            repeat(3) {
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.3f),
                    radius = (50 + random.nextInt(50)).dp.toPx(),
                    center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                )
            }
        }
    }
}

// ==================== SIMON COLOR ====================
@Composable
fun SimonColorScreen(onBack: () -> Unit) {
    val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow)
    val colorNames = listOf("Red", "Blue", "Green", "Yellow")

    val context = LocalContext.current
    var coins by remember { mutableIntStateOf(GameLogger.getCoins(context)) }
    var sequence by remember { mutableStateOf(listOf<Int>()) }
    var playerSequence by remember { mutableStateOf(listOf<Int>()) }
    var isPlaying by remember { mutableStateOf(false) }
    var highlightedIndex by remember { mutableIntStateOf(-1) }
    var level by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("Press Start") }
    var gameActive by remember { mutableStateOf(false) }
    var hardMode by remember { mutableStateOf(false) }
    var shuffledIndices by remember { mutableStateOf((0..3).toList()) }

    fun startNewGame() {
        sequence = listOf(Random().nextInt(4))
        playerSequence = emptyList()
        level = 1
        gameActive = true
        message = "Watch the pattern"
        shuffledIndices = if (hardMode) (0..3).shuffled() else (0..3).toList()
        isPlaying = true
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            message = "Watch the pattern"
            delay(800)
            for (index in sequence) {
                highlightedIndex = index
                delay(600)
                highlightedIndex = -1
                delay(200)
            }
            isPlaying = false
            playerSequence = emptyList()
            message = "Your turn - repeat the pattern"
        }
    }

    fun onColorClick(index: Int) {
        if (isPlaying || !gameActive) return

        val newPlayer = playerSequence + index
        playerSequence = newPlayer

        if (newPlayer.last() != sequence[newPlayer.size - 1]) {
            message = "Wrong! Pattern was ${sequence.map { colorNames[it] }.joinToString(" → ")}"
            gameActive = false
            // log it
            GameLogger.log(
                context,
                LogEntry(System.currentTimeMillis(), "Simon-Colors", sequence.size, sequence.joinToString(), newPlayer.joinToString(), false)
            )
            return
        }

        if (newPlayer.size == sequence.size) {
            // Success, add one more
            sequence = sequence + Random().nextInt(4)
            level++
            playerSequence = emptyList()
            message = "Great! Level $level"
            if (hardMode) {
                shuffledIndices = (0..3).shuffled()
            }
            GameLogger.log(
                context,
                LogEntry(System.currentTimeMillis(), "Simon-Colors", sequence.size - 1, sequence.dropLast(1).joinToString(), newPlayer.joinToString(), true)
            )
            isPlaying = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFFFFD700).copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp)) {
                Text("💰 $coins", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
            }
        }

        Text("Color Simon", style = MaterialTheme.typography.headlineMedium)
        Text("Level: $level", style = MaterialTheme.typography.titleLarge)
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = hardMode, onCheckedChange = { hardMode = it }, enabled = !gameActive)
            Text("Hard Mode (Shuffle positions)")
        }

        Text(message, modifier = Modifier.padding(8.dp))

        Spacer(Modifier.height(24.dp))

        // 2x2 Color grid
        Column {
            Row {
                ColorButton(color = colors[shuffledIndices[0]], enabled = !isPlaying && gameActive, isHighlighted = highlightedIndex == shuffledIndices[0], onClick = { onColorClick(shuffledIndices[0]) })
                ColorButton(color = colors[shuffledIndices[1]], enabled = !isPlaying && gameActive, isHighlighted = highlightedIndex == shuffledIndices[1], onClick = { onColorClick(shuffledIndices[1]) })
            }
            Row {
                ColorButton(color = colors[shuffledIndices[2]], enabled = !isPlaying && gameActive, isHighlighted = highlightedIndex == shuffledIndices[2], onClick = { onColorClick(shuffledIndices[2]) })
                ColorButton(color = colors[shuffledIndices[3]], enabled = !isPlaying && gameActive, isHighlighted = highlightedIndex == shuffledIndices[3], onClick = { onColorClick(shuffledIndices[3]) })
            }
        }

        Spacer(Modifier.height(32.dp))

        if (!gameActive) {
            Button(onClick = { startNewGame() }) { Text("Start New Game") }
        } else {
            if (!isPlaying && coins >= 50) {
                OutlinedButton(
                    onClick = {
                        if (GameLogger.spendCoins(context, 50)) {
                            coins = GameLogger.getCoins(context)
                            isPlaying = true
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("Repeat Pattern (50 💰)")
                }
            }
            Text("Watch carefully then tap the colors in order")
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Back to Menu") }
    }
}

@Composable
fun ColorButton(color: Color, enabled: Boolean, isHighlighted: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(140.dp)
            .padding(8.dp)
            .background(color.copy(alpha = if (enabled || isHighlighted) 1f else 0.4f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isHighlighted) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}

// ==================== SIMON OBJECTS ====================
@Composable
fun SimonObjectScreen(onBack: () -> Unit) {
    val allObjects = listOf("🍎", "🐶", "🚗", "⭐", "🌳", "🐱", "✈️", "🏀")

    val context = LocalContext.current
    var coins by remember { mutableIntStateOf(GameLogger.getCoins(context)) }
    var numSymbols by remember { mutableIntStateOf(4) }
    var sequence by remember { mutableStateOf(listOf<Int>()) }
    var playerSequence by remember { mutableStateOf(listOf<Int>()) }
    var isPlaying by remember { mutableStateOf(false) }
    var highlightedIndex by remember { mutableIntStateOf(-1) }
    var level by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("Choose set size and Start") }
    var gameActive by remember { mutableStateOf(false) }
    var hardMode by remember { mutableStateOf(false) }
    var shuffledIndices by remember { mutableStateOf((0 until numSymbols).toList()) }
    val currentSymbols = allObjects.take(numSymbols)

    fun startNewGame() {
        sequence = listOf(Random().nextInt(numSymbols))
        playerSequence = emptyList()
        level = 1
        gameActive = true
        shuffledIndices = if (hardMode) (0 until numSymbols).shuffled() else (0 until numSymbols).toList()
        isPlaying = true
        message = "Watch the objects"
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            message = "Watch the objects"
            delay(1000)
            for (index in sequence) {
                highlightedIndex = index
                delay(800)
                highlightedIndex = -1
                delay(200)
            }
            isPlaying = false
            playerSequence = emptyList()
            message = "Repeat the pattern"
        }
    }

    fun onObjectClick(index: Int) {
        if (isPlaying || !gameActive) return
        val newSeq = playerSequence + index
        playerSequence = newSeq

        if (newSeq.last() != sequence[newSeq.size-1]) {
            message = "Incorrect! Sequence was ${sequence.map { currentSymbols[it] }.joinToString(" ")}"
            gameActive = false
            GameLogger.log(context, LogEntry(System.currentTimeMillis(), "Simon-Objects($numSymbols)", sequence.size, sequence.joinToString(), newSeq.joinToString(), false))
            return
        }

        if (newSeq.size == sequence.size) {
            sequence = sequence + Random().nextInt(numSymbols)
            level++
            playerSequence = emptyList()
            message = "Good! Level $level"
            if (hardMode) {
                shuffledIndices = (0 until numSymbols).shuffled()
            }
            GameLogger.log(context, LogEntry(System.currentTimeMillis(), "Simon-Objects($numSymbols)", sequence.size-1, sequence.dropLast(1).joinToString(), newSeq.joinToString(), true))
            isPlaying = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFFFFD700).copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp)) {
                Text("💰 $coins", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
            }
        }

        Text("Object Simon", style = MaterialTheme.typography.headlineMedium)
        Text("Using $numSymbols objects | Level: $level")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = hardMode, onCheckedChange = { hardMode = it }, enabled = !gameActive)
            Text("Hard Mode (Shuffle positions)")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Set size:")
            Slider(
                value = numSymbols.toFloat(),
                onValueChange = { numSymbols = it.toInt().coerceIn(4, 8) },
                valueRange = 4f..8f,
                steps = 3,
                modifier = Modifier.width(200.dp),
                enabled = !gameActive
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(message, fontSize = 18.sp)

        Spacer(Modifier.height(20.dp))

        // Grid of objects
        val cols = if (numSymbols <= 4) 2 else 4
        val chunkedIndices = shuffledIndices.chunked(cols)

        chunkedIndices.forEach { rowIndices ->
            Row {
                rowIndices.forEach { idx ->
                    ObjectButton(
                        text = currentSymbols[idx],
                        enabled = !isPlaying && gameActive,
                        isHighlighted = highlightedIndex == idx,
                        onClick = { onObjectClick(idx) }
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        if (!gameActive) {
            Button(onClick = ::startNewGame) { Text("Start") }
        } else {
            if (!isPlaying && coins >= 50) {
                OutlinedButton(
                    onClick = {
                        if (GameLogger.spendCoins(context, 50)) {
                            coins = GameLogger.getCoins(context)
                            isPlaying = true
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("Repeat Pattern (50 💰)")
                }
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun ObjectButton(text: String, enabled: Boolean, isHighlighted: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(90.dp)
            .padding(6.dp)
            .background(
                if (isHighlighted) Color(0xFFFFEB3B) else if (enabled) Color(0xFFE8F5E9) else Color.LightGray,
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 36.sp)
    }
}

// ==================== STATS SCREEN ====================
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(GameLogger.getLogs(context)) }
    var selectedMode by remember { mutableStateOf("All") }

    val filteredLogs = if (selectedMode == "All") logs else logs.filter { it.mode.startsWith(selectedMode) }

    // Group logs into sessions
    // For Simon games, a session is a single LogEntry (the end of the game).
    // For Numbers, we group sequential entries that happened very close together (within 1 min).
    val sessions = remember(filteredLogs) {
        val list = mutableListOf<List<LogEntry>>()
        if (filteredLogs.isEmpty()) return@remember list
        
        var currentSession = mutableListOf<LogEntry>()
        for (i in filteredLogs.indices) {
            val entry = filteredLogs[i]
            if (currentSession.isEmpty()) {
                currentSession.add(entry)
            } else {
                val prev = currentSession.last()
                val diff = Math.abs(entry.timestamp - prev.timestamp)
                // Same mode and within 2 minutes = same session
                if (entry.mode == prev.mode && diff < 120_000) {
                    currentSession.add(entry)
                } else {
                    list.add(currentSession)
                    currentSession = mutableListOf(entry)
                }
            }
        }
        if (currentSession.isNotEmpty()) list.add(currentSession)
        list
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { 
                showClearDialog = false
                pinInput = ""
                pinError = false
            },
            title = { Text("Clear All Logs?") },
            text = {
                Column {
                    Text("This will permanently delete all your game history. Enter PIN to confirm.")
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { 
                            pinInput = it
                            pinError = false
                        },
                        label = { Text("Enter PIN") },
                        isError = pinError,
                        supportingText = if (pinError) { { Text("Incorrect PIN") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput == "6969") {
                            GameLogger.clearLogs(context)
                            logs = emptyList()
                            showClearDialog = false
                            pinInput = ""
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showClearDialog = false
                    pinInput = ""
                    pinError = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Statistics", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // Mode Selector
        val modes = listOf("All", "Numbers", "Simon-Colors", "Simon-Objects")
        ScrollableTabRow(
            selectedTabIndex = modes.indexOf(selectedMode).coerceAtLeast(0),
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            modes.forEach { mode ->
                Tab(
                    selected = selectedMode == mode,
                    onClick = { selectedMode = mode },
                    text = { Text(mode) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (filteredLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No data for this mode yet.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    SummaryCards(filteredLogs, selectedMode)
                }

                item {
                    Text(
                        "Progress Trend",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    AccuracyChart(filteredLogs, selectedMode)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Session History", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            showClearDialog = true
                        }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                items(sessions) { session ->
                    SessionItem(session)
                }
            }
        }
    }
}

@Composable
fun SummaryCards(logs: List<LogEntry>, mode: String) {
    val total = logs.size
    val bestScore = if (logs.isNotEmpty()) logs.maxOf { it.length } else 0
    val isSimon = mode.contains("Simon")

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isSimon) {
            val correct = logs.count { it.correct }
            val accuracy = if (total > 0) (correct * 100 / total) else 0
            StatCard("Accuracy", "$accuracy%", Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer)
        }
        StatCard("Total Trials", "$total", Modifier.weight(1f), MaterialTheme.colorScheme.secondaryContainer)
        StatCard("Best Length", "$bestScore", Modifier.weight(1f), MaterialTheme.colorScheme.tertiaryContainer)
    }
}

@Composable
fun SessionItem(entries: List<LogEntry>) {
    var expanded by remember { mutableStateOf(false) }
    val first = entries.first()
    val timeStr = SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(first.timestamp))
    val mode = first.mode
    val isSimon = mode.contains("Simon")
    
    val totalTrials = entries.size
    val correctTrials = entries.count { it.correct }
    val maxLen = entries.maxOf { it.length }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(mode, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    if (isSimon) {
                        Text("Level $maxLen", fontWeight = FontWeight.ExtraBold)
                    } else {
                        Text("$correctTrials / $totalTrials", fontWeight = FontWeight.Bold)
                        Text("Best: $maxLen", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (entry.correct) "✓" else "✗",
                            color = if (entry.correct) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            "Len ${entry.length}: ${entry.sequence} vs ${entry.userResponse}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, containerColor: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AccuracyChart(logs: List<LogEntry>, mode: String) {
    val sdf = SimpleDateFormat("MMM dd", Locale.US)
    val daySdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val isSimon = mode.contains("Simon")

    // Group by day
    val dailyStats = logs.groupBy { daySdf.format(Date(it.timestamp)) }
        .mapValues { (_, entries) ->
            if (isSimon) {
                entries.maxOf { it.length }.toFloat()
            } else {
                entries.count { it.correct }.toFloat() / entries.size
            }
        }
        .toList()
        .sortedBy { it.first }
        .takeLast(7)

    if (dailyStats.isEmpty()) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val maxVal = if (isSimon) (dailyStats.maxOf { it.second } + 2).coerceAtLeast(10f) else 1f

    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val spacing = width / (dailyStats.size.coerceAtLeast(1))
                val maxAccuracy = 1f

                // Draw horizontal grid lines
                for (i in 0..4) {
                    val y = height - (i * height / 4)
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.5f),
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Draw line and points
                val points = dailyStats.mapIndexed { index, (_, value) ->
                    androidx.compose.ui.geometry.Offset(
                        x = index * spacing + spacing / 2,
                        y = height - (value * height / maxVal)
                    )
                }

                if (points.size > 1) {
                    for (i in 0 until points.size - 1) {
                        drawLine(
                            color = primaryColor,
                            start = points[i],
                            end = points[i+1],
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }

                points.forEach { point ->
                    drawCircle(
                        color = primaryColor,
                        radius = 4.dp.toPx(),
                        center = point
                    )
                }
            }
            
            // Labels
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                dailyStats.forEach { (dateStr, _) ->
                    val displayDate = try {
                        sdf.format(daySdf.parse(dateStr)!!)
                    } catch (e: Exception) { "" }
                    Text(displayDate, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun HistoryItem(entry: LogEntry) {
    val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(entry.timestamp))
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.correct) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${entry.mode} • Len ${entry.length}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Seq: ${entry.sequence} | You: ${entry.userResponse}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(timeStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Spacer(Modifier.width(8.dp))
            Text(
                if (entry.correct) "✓" else "✗",
                style = MaterialTheme.typography.titleLarge,
                color = if (entry.correct) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
    }
}
// ==================== DAILY CHALLENGE ====================
@Composable
fun DailyChallengeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val challenge = remember { GameLogger.getDailyChallenge(context) }
    val sequence = challenge.first
    val startTime = challenge.second
    val oneHourMs = 60 * 60 * 1000L
    val viewLimitMs = 60 * 1000L
    
    val currentTime = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var userInput by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while(!isSubmitted) {
            currentTime.longValue = System.currentTimeMillis()
            delay(1000)
        }
    }

    val timeLeftToView = (startTime + viewLimitMs) - currentTime.longValue
    val canView = timeLeftToView > 0
    val timeLeftToUnlock = (startTime + oneHourMs) - currentTime.longValue
    val isLocked = timeLeftToUnlock > 0

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Daily Challenge", style = MaterialTheme.typography.headlineLarge)
        
        Spacer(Modifier.height(32.dp))
        
        if (isSubmitted) {
            Text(resultMessage, style = MaterialTheme.typography.headlineSmall, color = if(isSuccess) Color(0xFF4CAF50) else Color.Red)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("Back to Home") }
        } else {
            if (canView) {
                Text("MEMORIZE THIS NOW", color = Color.Red, fontWeight = FontWeight.Bold)
                Text("Time remaining: ${timeLeftToView / 1000}s", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(sequence, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("It will disappear in 60 seconds!", style = MaterialTheme.typography.bodySmall)
            } else if (isLocked) {
                val minutes = (timeLeftToUnlock / (1000 * 60)) % 60
                val seconds = (timeLeftToUnlock / 1000) % 60
                
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                Spacer(Modifier.height(16.dp))
                Text("Sequence Hidden", style = MaterialTheme.typography.headlineSmall)
                Text("Unlocks in ${minutes}m ${seconds}s", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                Text("Hope you remembered it!", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("TIME IS UP!", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                Text("Enter the sequence you saw earlier", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    label = { Text("Enter the sequence") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        isSubmitted = true
                        if (userInput == sequence) {
                            isSuccess = true
                            resultMessage = "AMAZING! Daily challenge complete. +10% points active!"
                            GameLogger.addCoins(context, 500)
                            GameLogger.setDailyComplete(context)
                        } else {
                            isSuccess = false
                            resultMessage = "Wrong! Better luck tomorrow."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Submit Challenge")
                }
            }
        }
        
        if (!isSubmitted) {
            Spacer(Modifier.height(48.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
