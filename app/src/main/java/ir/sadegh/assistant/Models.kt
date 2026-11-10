package ir.sadegh.assistant

import android.content.Context
import java.io.File

object Models {
    const val DIR = "vosk-model-small-fa-0.42"
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
    fun ready(c: Context) = File(c.filesDir, "$DIR/am").exists()
}
