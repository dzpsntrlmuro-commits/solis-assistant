package com.temiztube.app

import android.app.Application
import com.temiztube.app.data.NewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class TemizTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            NewPipeDownloader.getInstance(),
            Localization("tr"),
            ContentCountry("TR")
        )
    }
}
