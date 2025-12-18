package com.valhalla.thor.presentation.widgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

@Composable
fun TypeWriterText(
    modifier: Modifier = Modifier,
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight = FontWeight.Normal,
    fontStyle: FontStyle = FontStyle.Normal,
    maxLines: Int = 1,
    delay: Long = 100,
    delayOnLoop: Long = 2000,
    loop: Boolean = false,
    reverse: Boolean = true,
    textAlign: TextAlign = TextAlign.Center,
    softWrap: Boolean = false,
    onEnd: () -> Unit = {}
) {

    var textCharList by remember {
        mutableStateOf(
           emptyList<String>()
        )
    }

    var textToDisplay by remember { mutableStateOf("") }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isReversing by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        textCharList = emptyList()
        text.codePoints().forEach {
            textCharList += Char(it).toString()
        }
        textToDisplay = ""
        currentIndex = 0
        isReversing = false
        while (currentIndex < textCharList.size || (isReversing && currentIndex > 0)) {
            if (!isReversing) {
                currentIndex++
                textToDisplay += textCharList[currentIndex - 1]
            } else {
                currentIndex--
                textToDisplay = textCharList.subList(0, currentIndex).joinToString("")
            }
            if (currentIndex == textCharList.size || currentIndex == 0) {
                onEnd()
                if (loop) {
                    if (reverse) {
                        isReversing = !isReversing
                    } else {
                        textToDisplay = ""
                        currentIndex = 0
                    }
                    delay(delayOnLoop)
                }
            } else
                delay(delay)
        }
    }

    Text(
        text = textToDisplay,
        style = style,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        maxLines = maxLines,
        textAlign = textAlign,
        modifier = modifier
    )

}

@Preview(showBackground = true)
@Composable
fun TypeWriterTextPreview() {
    TypeWriterText(text = "Hello, World! \uD83D\uDC9C\uD83D\uDC4B", loop = true, reverse = true)
}