package org.nov0caina.sounder.online

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.nov0caina.sounder.online.db.OnlineDatabase
import org.nov0caina.sounder.online.db.OnlinePlaylistTrackDao
import org.nov0caina.sounder.online.db.RecentArtistsDao
import org.nov0caina.sounder.online.innertube.InnerTubeClient
import org.nov0caina.sounder.online.repository.OnlineMusicRepository
import org.nov0caina.sounder.online.repository.OnlineMusicRepositoryImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class OnlineModule {

    @Binds
    @Singleton
    abstract fun bindOnlineMusicRepository(
        impl: OnlineMusicRepositoryImpl
    ): OnlineMusicRepository

    @Binds
    @Singleton
    abstract fun bindOnlineSettings(
        impl: OnlineSettingsImpl
    ): OnlineSettings

    companion object {
        @Provides
        @Singleton
        fun provideOnlineDatabase(
            @ApplicationContext context: Context
        ): OnlineDatabase {
            return Room.databaseBuilder(
                context,
                OnlineDatabase::class.java,
                "auxio_online.db"
            ).fallbackToDestructiveMigration(true).build()
        }

        @Provides
        @Singleton
        fun provideRecentArtistsDao(database: OnlineDatabase): RecentArtistsDao {
            return database.recentArtistsDao()
        }

        @Provides
        @Singleton
        fun provideOnlinePlaylistTrackDao(database: OnlineDatabase): OnlinePlaylistTrackDao {
            return database.onlinePlaylistTrackDao()
        }

        @Provides
        @Singleton
        fun provideInnerTubeClient(onlineSettings: OnlineSettings): InnerTubeClient {
            return InnerTubeClient(onlineSettings)
        }
    }
}
