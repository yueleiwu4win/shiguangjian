package cn.ray.daybook

import android.app.Application
import androidx.room.Room
import cn.ray.daybook.data.*

class DaybookApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, DaybookDatabase::class.java, "daybook.db").build() }
    val media by lazy { MediaFiles(this) }
    val repository by lazy { DaybookRepository(database, media) }
    val settings by lazy { SettingsRepository(this) }
    val backup by lazy { BackupService(this, repository, settings) }
}
