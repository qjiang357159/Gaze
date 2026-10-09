package com.gaze.reader

import android.app.Application
import com.gaze.reader.data.AppContainer

class GazeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
