package com.flashcards

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.flashcards.database.Deck
import com.flashcards.database.Flash
import com.flashcards.database.getStatsString
import com.flashcards.ui.theme.FlashcardsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Locale

class FlashActivity : ComponentActivity() {
    companion object {
        const val STATS_STR = "STATS"
    }

    var deck: Deck = Deck.dummy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deck = db().deck().getByID(intent.extras?.getInt(DECK_ID_INT)!!)!!
        if (deck.readFront) frontTTS = MyTTS(this, deck.frontLocale)
        if (deck.readBack) backTTS = MyTTS(this, deck.backLocale)
        if (deck.readHint || deck.useHintAsPronunciation) hintTTS = MyTTS(this, deck.hintLocale)
        setContent { FlashcardsTheme { Content() } }
    }

    override fun onPause() {
        super.onPause()
        resumeStopwatch = stopwatch.isRunning
        stopwatch.pause()
    }

    override fun onResume() {
        super.onResume()
        if (resumeStopwatch) stopwatch.start()
        card = db().card().getByID(card.id) ?: com.flashcards.database.Card.dummy
    }

    val stopwatch = Stopwatch()
    var resumeStopwatch = false

    var card by mutableStateOf(com.flashcards.database.Card.dummy)
    var showBack  = false

    val flashes = mutableListOf<Flash>()

    @Preview
    @Composable
    fun Content() {
        val fontSize = 64.sp
        val buttonFontSize = 24.sp

        val showHint = remember { mutableStateOf(true) }
        val showFront = remember { mutableStateOf(true) }
        val showBack = remember { mutableStateOf(true) }
        fun cardDone() = showFront.value && showBack.value

        fun nextCard() {
            val pair = deck.getRandomCard()
            card = pair.first
            this.showBack = pair.second
            showFront.value = !this.showBack
            showHint.value = card.hint == null
            showBack.value = this.showBack
            stopwatch.reset()
            stopwatch.start()
            if (this.showBack) speakBack() else speakFront()
        }
        if (!isPreview()) {
            if (db().card().countActive(deck.id) == 0) { finish(); return }
            if (card === com.flashcards.database.Card.dummy || db().card().getByID(card.id) == null)
                nextCard()
        }
        LaunchedEffect(showFront.value) { if (showFront.value) speakFront() }
        LaunchedEffect(showBack.value) { if (showBack.value) speakBack() }
        LaunchedEffect(showHint.value) { if (showHint.value) speakHint() }
        if (cardDone() && stopwatch.isRunning) stopwatch.pause()

        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (showHint.value) {
                            showFront.value = true
                            showBack.value = true
                        } else showHint.value = true
                    })
                }
                .pointerInput(Unit) {
//                    detectDragGestures(
//                        onDrag = { _, _ -> },
//                        onDragStart = {},
//                        onDragEnd = { if (cardDone()) nextCard() })
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            @Composable
            fun ButtonOrText(text: String, buttonText: String, state: MutableState<Boolean>) {
                Box(Modifier.fillMaxWidth()) {
                    Text(text,
                        // fontSize directly will do wierd things with multiline
                        style = TextStyle(fontSize = fontSize),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .hideIf(!state.value)
                        )
                    Button(modifier = Modifier
                        .align(Alignment.Center)
                        .hideIf(state.value),
                        onClick = { state.value = true }) {
                        Text(buttonText, fontSize = buttonFontSize)
                    }
                }
            }

            Button(modifier = Modifier.hideIf(!cardDone()),
                onClick = {
                    if (!cardDone()) return@Button
                    Intent(this@FlashActivity, EditCardActivity::class.java).apply {
                        putExtra(DECK_ID_INT, deck.id)
                        putExtra(CARD_ID_INT, card.id)
                        this@FlashActivity.startActivity(this)
                    }
                }
            ) {
                Text("Edit", fontSize = buttonFontSize)
            }

            Spacer(Modifier.weight(1f))
            ButtonOrText(card.front, "Show front", showFront)
            Spacer(Modifier.weight(1f))
            if (card.hint != null) {
                ButtonOrText(card.hint!!, "Show hint", showHint)
                Spacer(Modifier.weight(1f))
            }
            ButtonOrText(card.back, "Show back", showBack)
            Spacer(Modifier.weight(1f))

            Row(modifier = Modifier.hideIf(!cardDone())) {
                @Composable
                fun ResultButton(value: Boolean) {
                    val color = if (value) Color.Green else Color.Red
                    val text = if (value) "✔" else "✘"
                    Button({
                        if (!cardDone()) return@Button
                        db().flash().insert(
                            Flash(0,
                            card.id,
                            System.currentTimeMillis(),
                            this@FlashActivity.showBack,
                            stopwatch.getElapsedTimeMillis(),
                            value)
                            .apply {
                                Log.d("flash", this.toString())
                                flashes.add(this)
                            })
                        nextCard()
                    },
                        colors = ButtonDefaults.buttonColors(containerColor = color),
                        modifier = Modifier.size(96.dp)
                    ) {
                        Text(text, fontSize = 48.sp)
                    }

                }
                Spacer(Modifier.weight(2f))
                ResultButton(value = false)
                Spacer(Modifier.weight(1f))
                ResultButton(value = true)
                Spacer(Modifier.weight(2f))
            }
            Text(when {
                    !showHint.value -> "Tap to show hint"
                    !showFront.value -> "Tap to show front"
                    !showBack.value -> "Tap to show back"
                    else -> "Swipe for next"
                },
                fontSize = buttonFontSize,
                modifier = Modifier.hideIf(cardDone()))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(Activity.RESULT_OK, Intent().apply {
            if (flashes.any()) putExtra(STATS_STR, flashes.getStatsString())
        })
        super.onBackPressed()
    }

    private var frontTTS: MyTTS? = null
    private var hintTTS: MyTTS? = null
    private var backTTS: MyTTS? = null

    fun speakBack() { backTTS?.speak(card.back) }
    fun speakHint() { if (deck.readHint && card.hint != null) hintTTS?.speak(card.hint!!) }
    fun speakFront() {
        if (deck.useHintAsPronunciation && card.hint != null)
            hintTTS!!.speak(card.hint!!)   // hintTTS should always be not null here
        else frontTTS?.speak(card.front)
    }

    override fun onDestroy() {
        frontTTS?.close()
        backTTS?.close()
        hintTTS?.close()
        super.onDestroy()
    }
}

class MyTTS(context: Context, locale: String) : TextToSpeech.OnInitListener, Closeable {
    private val tts: TextToSpeech
    private val locale: Locale
    private var ttsInitd: Boolean = false

    init {
        tts = TextToSpeech(context, this, "com.google.android.tts")
        this.locale =
            Locale.getAvailableLocales().firstOrNull { it.toString() == locale }
                ?: throw Exception("Invalid locale: '$locale'")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e("TTS", "Initialization failed")
            return
        }

        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "Language not supported")
            return
        }

        ttsInitd = true
    }

    public fun speak(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            var i = 0
            while (i++ < 10 && !ttsInitd) delay(100)
            if (!ttsInitd) { Log.e("TTS", "TTS not initialized"); return@launch }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}