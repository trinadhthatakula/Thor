package com.valhalla.thor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.thor.ui.theme.ThorTheme

class ScriptRunner : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThorTheme {
                var outPutString by remember {
                    mutableStateOf("")
                }
                LaunchedEffect(Unit) {
                    try {
                        Runtime.getRuntime().exec("su").let { suProcess ->
                            val outputStream = suProcess.outputStream
                            if (suProcess == null) {
                                outPutString = "Failed to get root access"
                            } else {
                                outputStream.write("sh /data/data/com.valhalla.thor/files/reinstaller.sh".toByteArray())
                                outputStream.flush()
                            }
                            outputStream.write("exit\n".toByteArray())
                            outputStream.flush()
                        }
                    }catch (e: Exception){
                        outPutString = e.message.toString()
                    }
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        Text(
                            "Script Runner",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(5.dp)
                        )
                        Text(
                            outPutString,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxSize().padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}
